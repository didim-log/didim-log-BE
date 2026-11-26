package com.didimlog.problem.application.collector

import com.didimlog.admin.application.AdminAuditService
import com.didimlog.problem.application.category.ProblemLanguageDetector
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.JOB_INDEX_KEY
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.leaseKey
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.MAX_JOB_STATE_RETRIES
import com.didimlog.problem.application.collector.ProblemJobStatusCalculator.estimateRemaining
import com.didimlog.problem.application.collector.ProblemJobStatusCalculator.isTerminal
import com.didimlog.problem.application.collector.ProblemJobStatusCalculator.withQueuePositions
import com.didimlog.problem.application.collector.ProblemJobTargets.dropManifestTargets
import com.didimlog.problem.application.collector.ProblemJobTargets.filterProblemsByRange
import com.didimlog.problem.application.collector.ProblemJobTargets.isAfterCheckpoint
import com.didimlog.problem.application.collector.ProblemJobTargets.metadataTargetIds
import com.didimlog.problem.application.collector.ProblemJobTargets.orderProblems
import com.didimlog.problem.application.collector.ProblemJobTargets.sha256
import com.didimlog.problem.application.collector.ProblemJobTargets.targetIdAt
import com.didimlog.problem.application.collector.ProblemJobTargets.targetManifestConflict
import com.didimlog.problem.application.collector.ProblemJobTargets.targetPosition
import com.didimlog.problem.application.collector.ProblemJobTargets.validateAndCountTargetManifest
import com.didimlog.problem.application.collector.ProblemJobTargets.validateRange
import com.didimlog.problem.domain.Problem
import com.didimlog.admin.domain.AdminActionType
import com.didimlog.problem.domain.repository.ProblemDetailsUpdate
import com.didimlog.problem.domain.repository.ProblemRepository
import com.didimlog.problem.domain.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.problem.infra.crawler.BojCrawler
import com.didimlog.problem.infra.crawler.ProblemDetails
import com.didimlog.infra.solvedac.ProblemCategoryMapper
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcTierMapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import kotlin.math.min

/**
 * 문제 데이터 수집 서비스
 *
 * - 문제 수집 배치 실행
 * - 배치 상태 조회/목록/취소/재시도
 * - 운영 메트릭/감사 조회
 */
@Service
class ProblemCollectorService(
    private val solvedAcClient: SolvedAcClient,
    private val problemRepository: ProblemRepository,
    private val bojCrawler: BojCrawler,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val adminAuditService: AdminAuditService,
    private val pacer: ProblemCollectorPacer,
    private val recoveryState: ProblemCollectorRecoveryState,
    @param:Qualifier("taskExecutor")
    private val taskExecutor: Executor? = null,
    private val workerLeaseProperties: ProblemCollectorWorkerLeaseProperties =
        ProblemCollectorWorkerLeaseProperties(),
    private val workerIdentity: ProblemCollectorWorkerIdentity =
        ProblemCollectorWorkerIdentity(),
    @param:Qualifier(PROBLEM_COLLECTOR_HEARTBEAT_EXECUTOR)
    private val heartbeatExecutor: ScheduledExecutorService? = null,
    private val parallelProblemDetailsFetcher: ParallelProblemDetailsFetcher? = null
) {

    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)
    private val stateStore = ProblemJobStateStore(redisTemplate, objectMapper, workerLeaseProperties)
    private val manifestValidator = ProblemJobManifestValidator(redisTemplate, objectMapper, stateStore)
    private val auditLogger = ProblemJobAuditLogger(adminAuditService)
    private val workerLeaseCoordinator = ProblemJobWorkerLeaseCoordinator(
        redisTemplate = redisTemplate,
        objectMapper = objectMapper,
        stateStore = stateStore,
        manifestValidator = manifestValidator,
        workerLeaseProperties = workerLeaseProperties,
        workerIdentity = workerIdentity,
        heartbeatExecutor = heartbeatExecutor
    )
    private val executionRunner = ProblemJobExecutionRunner(
        taskExecutor = taskExecutor,
        workerLeaseProperties = workerLeaseProperties,
        stateStore = stateStore,
        workerLeaseCoordinator = workerLeaseCoordinator
    )

    companion object {
        private const val RESTART_ORPHAN_MESSAGE =
            "서버 재시작으로 실행 주체를 잃었습니다. 작업을 재시도해주세요."
    }

    private data class ProblemTarget(
        val problemId: String,
        val problem: Problem?
    )

    private data class JobProgress(
        val processedCount: Int,
        val successCount: Int,
        val failCount: Int
    )

    /**
     * Solved.ac API를 통해 문제 메타데이터를 수집하여 DB에 저장한다 (Upsert).
     */
    @Transactional
    fun collectMetadata(start: Int, end: Int) {
        log.info("문제 메타데이터 수집 시작: start=$start, end=$end")
        var successCount = 0
        var failCount = 0

        for (problemId in start..end) {
            pacer.pauseMetadata()
            try {
                upsertProblemMetadata(problemId)
                successCount++
            } catch (e: IllegalStateException) {
                failCount++
                if (e.message?.contains("찾을 수 없습니다") != true) {
                    log.warn("Failed to collect problem $problemId: ${e.message}")
                }
            } catch (e: Exception) {
                log.warn("Failed to collect problem $problemId: ${e.message}")
                failCount++
            }

        }

        log.info("문제 메타데이터 수집 완료: 성공=$successCount, 실패=$failCount")
    }

    /**
     * DB에서 descriptionHtml이 null인 문제들의 상세 정보를 크롤링하여 업데이트한다.
     */
    @Transactional
    fun collectDetailsBatch() {
        val problemsWithoutDetails = problemRepository.findByDescriptionHtmlIsNull()
        if (problemsWithoutDetails.isEmpty()) {
            return
        }

        for (problem in problemsWithoutDetails) {
            try {
                pacer.pauseDetails()
                val details = bojCrawler.crawlProblemDetails(problem.id.value) ?: continue
                if (updateProblemDetails(problem.id.value, details) == null) {
                    log.warn("문제 상세 정보 저장 대상 없음: problemId=${problem.id.value}")
                }
            } catch (e: Exception) {
                log.error("문제 상세 정보 수집 실패: problemId=${problem.id.value}, error=${e.message}", e)
            }
        }
    }

    fun collectMetadataAsync(start: Int, end: Int, createdBy: String = "system", ipAddress: String = "unknown"): String {
        if (start <= 0 || end <= 0 || start > end) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "유효하지 않은 범위입니다. start=$start, end=$end")
        }

        return startCollectMetadataWithManifest(
            explicitTargetIds = emptyList(),
            targetRange = JobRange(start, end),
            responseRange = JobRange(start, end),
            createdBy = createdBy,
            ipAddress = ipAddress
        )
            .jobId
    }

    fun collectDetailsBatchAsync(createdBy: String = "system", ipAddress: String = "unknown"): String {
        return startCollectDetailsWithTargets(
            problemRepository.findByDescriptionHtmlIsNull(),
            createdBy,
            ipAddress
        ).jobId
    }

    fun refreshDetailsBatchAsync(
        start: Int? = null,
        end: Int? = null,
        createdBy: String = "system",
        ipAddress: String = "unknown"
    ): String {
        validateRange(start, end)

        return startRefreshDetailsWithTargets(
            filterProblemsByRange(problemRepository.findAll(), start, end),
            createdBy,
            ipAddress,
            JobRange(start, end)
        ).jobId
    }

    fun updateLanguageBatchAsync(createdBy: String = "system", ipAddress: String = "unknown"): String {
        return startUpdateLanguageWithTargets(
            problemRepository.findAll(),
            createdBy,
            ipAddress
        ).jobId
    }

    fun getMetadataCollectJobStatus(jobId: String): JobStatusUnifiedResponse? {
        return getTypedJob(jobId, ProblemJobType.COLLECT_METADATA)
    }

    fun getDetailsCollectJobStatus(jobId: String): JobStatusUnifiedResponse? {
        return getTypedJob(jobId, ProblemJobType.COLLECT_DETAILS)
    }

    fun getDetailsRefreshJobStatus(jobId: String): JobStatusUnifiedResponse? {
        return getTypedJob(jobId, ProblemJobType.REFRESH_DETAILS)
    }

    fun getLanguageUpdateJobStatus(jobId: String): JobStatusUnifiedResponse? {
        return getTypedJob(jobId, ProblemJobType.UPDATE_LANGUAGE)
    }

    fun getJob(jobId: String): JobStatusUnifiedResponse? {
        val raw = stateStore.readJob(jobId) ?: return null
        return withQueuePosition(raw)
    }

    fun failOrphanedJobsDuringStartup(): Int {
        check(!workerLeaseProperties.enabled) {
            "worker lease가 활성화된 동안 시작 orphan 실패 처리를 실행할 수 없습니다."
        }
        check(!recoveryState.isReady()) {
            "재시작 orphan 작업 복구는 작업 생성 gate가 닫힌 시작 단계에서만 실행할 수 있습니다."
        }
        val jobIds = redisTemplate.opsForZSet()
            .reverseRange(JOB_INDEX_KEY, 0, -1)
            .orEmpty()

        return jobIds.count { jobId ->
            stateStore.markFailed(
                jobId = jobId,
                errorCode = ErrorCode.WORKER_UNAVAILABLE.code,
                message = RESTART_ORPHAN_MESSAGE
            )
        }
    }

    fun submitRecoverableJobs(): Int {
        if (!workerLeaseProperties.enabled) {
            return 0
        }

        return stateStore.loadAllJobsPruningStaleIndex()
            .asSequence()
            .filter { job ->
                job.status == JobStatus.PENDING || job.status == JobStatus.RUNNING
            }
            .count { job -> submitRecoverableJob(job) }
    }

    fun getJobs(
        type: ProblemJobType?,
        status: JobStatus?,
        from: Long?,
        to: Long?,
        page: Int,
        size: Int
    ): JobPageResponse<JobStatusUnifiedResponse> {
        if (page <= 0 || size <= 0) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "page와 size는 1 이상이어야 합니다.")
        }
        if (from != null && to != null && from > to) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "from은 to보다 클 수 없습니다. from=$from, to=$to")
        }

        val filtered = withQueuePositions(stateStore.loadAllJobsPruningStaleIndex())
            .filter { job -> type == null || job.jobType == type }
            .filter { job -> status == null || job.status == status }
            .filter { job -> from == null || job.queuedAt >= from }
            .filter { job -> to == null || job.queuedAt <= to }
            .sortedByDescending { it.queuedAt }

        val offset = (page - 1) * size
        if (offset >= filtered.size) {
            return JobPageResponse.of(emptyList(), page, size, filtered.size.toLong())
        }

        val end = min(offset + size, filtered.size)
        return JobPageResponse.of(filtered.subList(offset, end), page, size, filtered.size.toLong())
    }

    fun cancelJob(jobId: String, cancelledBy: String, ipAddress: String = "unknown"): JobStatusUnifiedResponse {
        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = stateStore.readJobSnapshot(jobId)
                ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")
            val current = snapshot.job
            if (isTerminal(current.status)) {
                throw BusinessException(ErrorCode.JOB_ALREADY_TERMINAL, "이미 종료된 작업입니다. jobId=$jobId, status=${current.status}")
            }

            val now = stateStore.nowEpochSeconds()
            val updated = current.copy(
                status = JobStatus.CANCELLED,
                completedAt = now,
                lastHeartbeatAt = now,
                errorCode = null,
                errorMessage = null
            )
            val cancelled = stateStore.compareAndSetJob(snapshot, updated)
            if (cancelled != null) {
                auditLogger.logJobAction(cancelledBy, AdminActionType.PROBLEM_JOB_CANCEL, ipAddress, cancelled)
                return withQueuePosition(cancelled)
            }
        }

        throw BusinessException(
            ErrorCode.RESOURCE_STATE_CONFLICT,
            "작업 상태가 계속 변경되어 취소하지 못했습니다. jobId=$jobId"
        )
    }

    fun retryJob(jobId: String, requestedBy: String, ipAddress: String = "unknown"): JobStatusUnifiedResponse {
        val original = stateStore.readJob(jobId)
            ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")

        if (!isTerminal(original.status)) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "RUNNING/PENDING 작업은 재시도할 수 없습니다. jobId=$jobId")
        }

        val failureLedger = stateStore.readFailureLedger(jobId)
        val failedProblemIds = failureLedger.problemIds
        val targetManifest = manifestValidator.readTargetManifest(original)
        val interruptedNonMetadataJob = original.jobType != ProblemJobType.COLLECT_METADATA &&
            original.status != JobStatus.COMPLETED
        val usesLegacyRetryFallback = targetManifest == null &&
            !failureLedger.exists &&
            (original.failCount > 0 || interruptedNonMetadataJob)
        if (!usesLegacyRetryFallback && failedProblemIds.size != original.failCount) {
            throw BusinessException(
                ErrorCode.RESOURCE_STATE_CONFLICT,
                "실패 항목 기록이 작업 상태와 일치하지 않습니다. jobId=$jobId, failCount=${original.failCount}, recorded=${failedProblemIds.size}"
            )
        }
        if (targetManifest != null) {
            return retryManifestJob(
                original = original,
                manifest = targetManifest,
                failedProblemIds = failedProblemIds,
                requestedBy = requestedBy,
                ipAddress = ipAddress
            )
        }

        val checkpointExclusive = original.lastCheckpointId?.toIntOrNull()
        val retryJobId = when (original.jobType) {
            ProblemJobType.COLLECT_METADATA -> {
                val start = original.range?.start
                    ?: throw BusinessException(ErrorCode.INVALID_RANGE, "원본 작업의 start 범위를 찾을 수 없습니다. jobId=$jobId")
                val end = original.range.end
                    ?: throw BusinessException(ErrorCode.INVALID_RANGE, "원본 작업의 end 범위를 찾을 수 없습니다. jobId=$jobId")
                validateRange(start, end)
                val failedIds = failedProblemIds.map { failedId ->
                    failedId.toIntOrNull()
                        ?: throw BusinessException(
                            ErrorCode.RESOURCE_STATE_CONFLICT,
                            "메타데이터 실패 항목 ID가 숫자가 아닙니다. jobId=$jobId, problemId=$failedId"
                        )
                }.sorted()
                if (failedIds.any { it !in start..end }) {
                    throw BusinessException(
                        ErrorCode.RESOURCE_STATE_CONFLICT,
                        "메타데이터 실패 항목 ID가 원본 범위를 벗어났습니다. jobId=$jobId"
                    )
                }

                when {
                    usesLegacyRetryFallback -> startCollectMetadataWithManifest(
                        explicitTargetIds = emptyList(),
                        targetRange = JobRange(start, end),
                        responseRange = JobRange(start, end),
                        createdBy = requestedBy,
                        ipAddress = ipAddress
                    ).jobId

                    original.status == JobStatus.COMPLETED -> {
                        if (failedIds.isEmpty()) {
                            createNoopCompletedJob(
                                ProblemJobType.COLLECT_METADATA,
                                JobRange(start, end),
                                requestedBy
                            ).jobId
                        } else {
                            startCollectMetadataWithManifest(
                                explicitTargetIds = failedIds,
                                targetRange = null,
                                responseRange = JobRange(failedIds.first(), failedIds.last()),
                                createdBy = requestedBy,
                                ipAddress = ipAddress
                            ).jobId
                        }
                    }

                    else -> {
                        val invalidFailedCheckpoint = when {
                            failedIds.isEmpty() -> false
                            checkpointExclusive == null -> true
                            else -> failedIds.any { failedId -> failedId > checkpointExclusive }
                        }
                        if (invalidFailedCheckpoint) {
                            throw BusinessException(
                                ErrorCode.RESOURCE_STATE_CONFLICT,
                                "메타데이터 실패 항목과 체크포인트가 일치하지 않습니다. jobId=$jobId"
                            )
                        }
                        val retryStart = when {
                            checkpointExclusive == null -> start
                            checkpointExclusive >= end -> null
                            else -> maxOf(start, checkpointExclusive + 1)
                        }
                        if (failedIds.isEmpty() && retryStart == null) {
                            createNoopCompletedJob(
                                ProblemJobType.COLLECT_METADATA,
                                JobRange(start, end),
                                requestedBy
                            ).jobId
                        } else {
                            startCollectMetadataWithManifest(
                                explicitTargetIds = failedIds,
                                targetRange = retryStart?.let { JobRange(it, end) },
                                responseRange = JobRange(
                                    failedIds.firstOrNull() ?: requireNotNull(retryStart),
                                    if (retryStart == null) failedIds.last() else end
                                ),
                                createdBy = requestedBy,
                                ipAddress = ipAddress
                            ).jobId
                        }
                    }
                }
            }

            ProblemJobType.COLLECT_DETAILS -> {
                val candidates = loadRetryProblemCandidates(
                    problemRepository.findByDescriptionHtmlIsNull(),
                    failedProblemIds,
                    jobId
                )
                val targets = selectRetryProblems(
                    original,
                    candidates,
                    failedProblemIds,
                    checkpointExclusive,
                    usesLegacyRetryFallback
                )
                startCollectDetailsWithTargets(
                    targets,
                    requestedBy,
                    ipAddress
                ).jobId
            }

            ProblemJobType.REFRESH_DETAILS -> {
                val range = original.range
                val candidates = loadRetryProblemCandidates(
                    filterProblemsByRange(problemRepository.findAll(), range?.start, range?.end),
                    failedProblemIds,
                    jobId
                )
                val targets = selectRetryProblems(
                    original,
                    candidates,
                    failedProblemIds,
                    checkpointExclusive,
                    usesLegacyRetryFallback
                )
                startRefreshDetailsWithTargets(
                    targets,
                    requestedBy,
                    ipAddress,
                    range
                ).jobId
            }

            ProblemJobType.UPDATE_LANGUAGE -> {
                val candidates = loadRetryProblemCandidates(
                    problemRepository.findAll(),
                    failedProblemIds,
                    jobId
                )
                val targets = selectRetryProblems(
                    original,
                    candidates,
                    failedProblemIds,
                    checkpointExclusive,
                    usesLegacyRetryFallback
                )
                startUpdateLanguageWithTargets(
                    targets,
                    requestedBy,
                    ipAddress
                ).jobId
            }
        }

        val retryJob = getJob(retryJobId)
            ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "재시도 작업 생성에 실패했습니다. jobId=$retryJobId")

        auditLogger.logJobAction(
            requestedBy,
            AdminActionType.PROBLEM_JOB_RETRY,
            ipAddress,
            retryJob,
            "retryFrom=$jobId"
        )

        return retryJob
    }

    private fun retryManifestJob(
        original: JobStatusUnifiedResponse,
        manifest: ProblemJobTargetManifest,
        failedProblemIds: Set<String>,
        requestedBy: String,
        ipAddress: String
    ): JobStatusUnifiedResponse {
        val selected = selectManifestRetryTargets(
            original = original,
            manifest = manifest,
            failedProblemIds = failedProblemIds
        )
        val retryJobId = when (original.jobType) {
            ProblemJobType.COLLECT_METADATA -> {
                val explicitIds = selected.explicitIds.map { problemId ->
                    problemId.toIntOrNull()
                        ?: targetManifestConflict(
                            original.jobId,
                            "메타데이터 작업 대상 ID가 숫자가 아닙니다. problemId=$problemId"
                        )
                }
                if (explicitIds.isEmpty() && selected.range == null) {
                    createNoopCompletedJob(
                        type = ProblemJobType.COLLECT_METADATA,
                        range = original.range,
                        createdBy = requestedBy
                    ).jobId
                } else {
                    startCollectMetadataWithManifest(
                        explicitTargetIds = explicitIds,
                        targetRange = selected.range,
                        responseRange = metadataResponseRange(selected),
                        createdBy = requestedBy,
                        ipAddress = ipAddress
                    ).jobId
                }
            }

            ProblemJobType.COLLECT_DETAILS -> {
                val selectedProblems = loadManifestProblems(
                    targetIds = selected.explicitIds,
                    jobId = original.jobId
                ).filter { problem ->
                    failedProblemIds.contains(problem.id.value) ||
                        problem.descriptionHtml == null
                }
                startCollectDetailsWithTargets(
                    selectedProblems,
                    requestedBy,
                    ipAddress
                ).jobId
            }

            ProblemJobType.REFRESH_DETAILS -> {
                val selectedProblems = loadManifestProblems(
                    targetIds = selected.explicitIds,
                    jobId = original.jobId
                )
                startRefreshDetailsWithTargets(
                    selectedProblems,
                    requestedBy,
                    ipAddress,
                    original.range
                ).jobId
            }

            ProblemJobType.UPDATE_LANGUAGE -> {
                val selectedProblems = loadManifestProblems(
                    targetIds = selected.explicitIds,
                    jobId = original.jobId
                )
                startUpdateLanguageWithTargets(
                    selectedProblems,
                    requestedBy,
                    ipAddress
                ).jobId
            }
        }

        val retryJob = getJob(retryJobId)
            ?: throw BusinessException(
                ErrorCode.JOB_NOT_FOUND,
                "재시도 작업 생성에 실패했습니다. jobId=$retryJobId"
            )
        auditLogger.logJobAction(
            requestedBy,
            AdminActionType.PROBLEM_JOB_RETRY,
            ipAddress,
            retryJob,
            "retryFrom=${original.jobId}"
        )
        return retryJob
    }

    private fun selectManifestRetryTargets(
        original: JobStatusUnifiedResponse,
        manifest: ProblemJobTargetManifest,
        failedProblemIds: Set<String>
    ): ManifestTargetSelection {
        val targetCount = manifestValidator.validateManifestForJob(original, manifest)
        if (original.processedCount !in 0..targetCount) {
            targetManifestConflict(
                original.jobId,
                "처리 수가 대상 수 범위를 벗어났습니다. processed=${original.processedCount}, total=$targetCount"
            )
        }

        if (original.processedCount == 0) {
            if (original.lastCheckpointId != null) {
                targetManifestConflict(original.jobId, "처리 전 작업에 체크포인트가 있습니다.")
            }
        } else {
            val expectedCheckpoint = targetIdAt(manifest, original.processedCount - 1)
            if (original.lastCheckpointId != expectedCheckpoint) {
                targetManifestConflict(
                    original.jobId,
                    "체크포인트가 manifest 처리 위치와 일치하지 않습니다."
                )
            }
        }

        val failedWithPositions = failedProblemIds.map { failedId ->
            val position = targetPosition(manifest, failedId)
                ?: targetManifestConflict(
                    original.jobId,
                    "실패 항목이 manifest에 없습니다. problemId=$failedId"
                )
            if (position >= original.processedCount) {
                targetManifestConflict(
                    original.jobId,
                    "실패 항목이 처리 완료 prefix 밖에 있습니다. problemId=$failedId"
                )
            }
            position to failedId
        }.sortedBy { it.first }
        val orderedFailedIds = failedWithPositions.map { it.second }

        if (original.status == JobStatus.COMPLETED) {
            if (original.processedCount != targetCount) {
                targetManifestConflict(
                    original.jobId,
                    "완료 작업의 처리 수가 대상 수와 일치하지 않습니다."
                )
            }
            return ManifestTargetSelection(
                explicitIds = orderedFailedIds,
                range = null
            )
        }

        val unprocessed = dropManifestTargets(manifest, original.processedCount)
        return ManifestTargetSelection(
            explicitIds = orderedFailedIds + unprocessed.explicitIds,
            range = unprocessed.range
        )
    }

    private fun loadManifestProblems(
        targetIds: List<String>,
        jobId: String
    ): List<Problem> {
        if (targetIds.isEmpty()) {
            return emptyList()
        }
        val foundProblems = problemRepository.findAllById(targetIds.toSet())
        val byId = foundProblems.associateBy { it.id.value }
        val missingCount = targetIds.count { targetId -> !byId.containsKey(targetId) }
        if (missingCount > 0) {
            log.info("retry skips deleted manifest problems: jobId=$jobId, missingCount=$missingCount")
        }
        return targetIds.mapNotNull(byId::get)
    }

    private fun metadataResponseRange(
        selected: ManifestTargetSelection
    ): JobRange {
        val explicitIds = selected.explicitIds.map { problemId ->
            problemId.toIntOrNull()
                ?: throw IllegalStateException("메타데이터 작업 대상 ID가 숫자가 아닙니다. problemId=$problemId")
        }
        val starts = explicitIds + listOfNotNull(selected.range?.start)
        val ends = explicitIds + listOfNotNull(selected.range?.end)
        return JobRange(
            start = starts.minOrNull()
                ?: throw IllegalStateException("메타데이터 재시도 대상이 없습니다."),
            end = ends.maxOrNull()
                ?: throw IllegalStateException("메타데이터 재시도 대상이 없습니다.")
        )
    }

    fun getJobMetrics(window: JobMetricsWindow): JobMetricsResponse {
        val threshold = when (window) {
            JobMetricsWindow.DAY -> stateStore.nowEpochSeconds() - Duration.ofDays(1).seconds
            JobMetricsWindow.WEEK -> stateStore.nowEpochSeconds() - Duration.ofDays(7).seconds
            JobMetricsWindow.MONTH -> stateStore.nowEpochSeconds() - Duration.ofDays(30).seconds
        }

        val jobs = stateStore.loadAllJobsPruningStaleIndex().filter { it.queuedAt >= threshold }
        val totalJobs = jobs.size.toLong()
        val completedJobs = jobs.count { it.status == JobStatus.COMPLETED }.toLong()
        val failedJobs = jobs.count { it.status == JobStatus.FAILED }.toLong()
        val cancelledJobs = jobs.count { it.status == JobStatus.CANCELLED }.toLong()

        val averageDurationSeconds = jobs
            .mapNotNull { job ->
                val started = job.startedAt
                val completed = job.completedAt
                if (started == null || completed == null || completed < started) {
                    null
                } else {
                    completed - started
                }
            }
            .let { durations ->
                if (durations.isEmpty()) {
                    0L
                } else {
                    durations.sum() / durations.size
                }
            }

        val averageFailureRate = jobs
            .map { job ->
                if (job.totalCount <= 0) {
                    if (job.status == JobStatus.FAILED) 1.0 else 0.0
                } else {
                    job.failCount.toDouble() / job.totalCount.toDouble()
                }
            }
            .let { rates ->
                if (rates.isEmpty()) {
                    0.0
                } else {
                    rates.sum() / rates.size.toDouble()
                }
            }

        val topErrorCodes = jobs
            .mapNotNull { it.errorCode }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { JobErrorCodeMetric(it.key, it.value.toLong()) }

        return JobMetricsResponse(
            window = window,
            totalJobs = totalJobs,
            completedJobs = completedJobs,
            failedJobs = failedJobs,
            cancelledJobs = cancelledJobs,
            averageDurationSeconds = averageDurationSeconds,
            averageFailureRate = averageFailureRate,
            topErrorCodes = topErrorCodes
        )
    }

    fun getJobAudit(
        type: ProblemJobType?,
        status: JobStatus?,
        from: Long?,
        to: Long?,
        page: Int,
        size: Int
    ): JobPageResponse<JobAuditResponse> {
        val jobsPage = getJobs(type, status, from, to, page, size)
        val audits = jobsPage.content.map { JobAuditResponse.from(it) }
        return JobPageResponse(
            content = audits,
            page = jobsPage.page,
            size = jobsPage.size,
            totalElements = jobsPage.totalElements,
            totalPages = jobsPage.totalPages,
            hasNext = jobsPage.hasNext,
            hasPrevious = jobsPage.hasPrevious
        )
    }

    private fun startCollectDetailsWithTargets(
        targets: List<Problem>,
        createdBy: String,
        ipAddress: String
    ): JobStatusUnifiedResponse {
        val orderedTargets = orderProblems(targets)
        val job = createJob(
            type = ProblemJobType.COLLECT_DETAILS,
            range = null,
            createdBy = createdBy,
            manifestExplicitIds = orderedTargets.map { it.id.value },
            manifestRange = null
        )
        auditLogger.logJobAction(createdBy, AdminActionType.PROBLEM_JOB_CREATE, ipAddress, job)
        executionRunner.executeAsync(job.jobId) { collectDetailsBatchAsyncInternal(job.jobId, orderedTargets) }
        return job
    }

    private fun startCollectMetadataWithManifest(
        explicitTargetIds: List<Int>,
        targetRange: JobRange?,
        responseRange: JobRange,
        createdBy: String,
        ipAddress: String
    ): JobStatusUnifiedResponse {
        val fixedTargetIds = explicitTargetIds.toList()
        val job = createJob(
            type = ProblemJobType.COLLECT_METADATA,
            range = responseRange,
            createdBy = createdBy,
            manifestExplicitIds = fixedTargetIds.map(Int::toString),
            manifestRange = targetRange
        )
        val targetIds = metadataTargetIds(fixedTargetIds, targetRange)
        auditLogger.logJobAction(createdBy, AdminActionType.PROBLEM_JOB_CREATE, ipAddress, job)
        executionRunner.executeAsync(job.jobId) { collectMetadataAsyncInternal(job.jobId, targetIds) }
        return job
    }

    private fun startRefreshDetailsWithTargets(
        targets: List<Problem>,
        createdBy: String,
        ipAddress: String,
        range: JobRange?
    ): JobStatusUnifiedResponse {
        val orderedTargets = orderProblems(targets)
        val job = createJob(
            type = ProblemJobType.REFRESH_DETAILS,
            range = range,
            createdBy = createdBy,
            manifestExplicitIds = orderedTargets.map { it.id.value },
            manifestRange = null
        )
        auditLogger.logJobAction(createdBy, AdminActionType.PROBLEM_JOB_CREATE, ipAddress, job)
        executionRunner.executeAsync(job.jobId) { refreshDetailsBatchAsyncInternal(job.jobId, orderedTargets) }
        return job
    }

    private fun startUpdateLanguageWithTargets(
        targets: List<Problem>,
        createdBy: String,
        ipAddress: String
    ): JobStatusUnifiedResponse {
        val orderedTargets = orderProblems(targets)
        val job = createJob(
            type = ProblemJobType.UPDATE_LANGUAGE,
            range = null,
            createdBy = createdBy,
            manifestExplicitIds = orderedTargets.map { it.id.value },
            manifestRange = null
        )
        auditLogger.logJobAction(createdBy, AdminActionType.PROBLEM_JOB_CREATE, ipAddress, job)
        executionRunner.executeAsync(job.jobId) { updateLanguageBatchAsyncInternal(job.jobId, orderedTargets) }
        return job
    }

    private fun createNoopCompletedJob(
        type: ProblemJobType,
        range: JobRange?,
        createdBy: String
    ): JobStatusUnifiedResponse {
        val created = createJob(
            type = type,
            range = range,
            createdBy = createdBy,
            manifestExplicitIds = emptyList(),
            manifestRange = null
        )
        executionRunner.runJobLoop(
            jobId = created.jobId,
            defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code
        ) { }
        return stateStore.readJob(created.jobId)
            ?: throw IllegalStateException("생성한 작업 상태를 찾을 수 없습니다. jobId=${created.jobId}")
    }

    private fun collectMetadataAsyncInternal(jobId: String, problemIds: Iterable<Int>) {
        executionRunner.runJobLoop(
            jobId = jobId,
            defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code
        ) { worker ->
            processMetadataTargets(
                jobId = jobId,
                worker = worker,
                problemIds = problemIds,
                initialProgress = JobProgress(0, 0, 0)
            )
        }
    }

    private fun processMetadataTargets(
        jobId: String,
        worker: JobWorkerContext,
        problemIds: Iterable<Int>,
        initialProgress: JobProgress
    ) {
        processJobTargets(
            jobId = jobId,
            worker = worker,
            targets = problemIds,
            initialProgress = initialProgress,
            operationName = "metadata",
            targetId = Int::toString,
            beforeProcess = pacer::pauseMetadata
        ) { problemId ->
            upsertProblemMetadata(problemId)
            true
        }
    }

    private fun upsertProblemMetadata(problemId: Int) {
        val response = solvedAcClient.fetchProblem(problemId)
        val difficultyTier = SolvedAcTierMapper.fromProblemLevel(response.level)
        val tags = ProblemCategoryMapper.extractTagsToEnglish(response.tags)
        val category = ProblemCategoryMapper.determineCategory(tags)
        val problem = Problem(
            id = ProblemId(response.problemId.toString()),
            title = response.titleKo,
            category = category,
            difficulty = difficultyTier,
            level = response.level,
            url = solvedAcProblemUrl(response.problemId),
            tags = tags
        )

        problemRepository.upsertMetadata(problem)
    }

    private fun updateProblemDetails(
        problemId: String,
        details: ProblemDetails,
        language: String? = null
    ): Problem? {
        return problemRepository.updateDetails(
            problemId,
            ProblemDetailsUpdate(
                descriptionHtml = details.descriptionHtml,
                inputDescriptionHtml = details.inputDescriptionHtml,
                outputDescriptionHtml = details.outputDescriptionHtml,
                sampleInputs = details.sampleInputs,
                sampleOutputs = details.sampleOutputs,
                language = language
            )
        )
    }

    private fun collectDetailsBatchAsyncInternal(jobId: String, targetProblems: List<Problem>) {
        executionRunner.runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) { worker ->
            processDetailsTargets(
                jobId = jobId,
                worker = worker,
                targets = targetProblems.map { ProblemTarget(it.id.value, it) },
                initialProgress = JobProgress(0, 0, 0)
            )
        }
    }

    private fun processDetailsTargets(
        jobId: String,
        worker: JobWorkerContext,
        targets: List<ProblemTarget>,
        initialProgress: JobProgress
    ) {
        processFetchedDetailsTargets(
            jobId = jobId,
            worker = worker,
            targets = targets,
            initialProgress = initialProgress,
            operationName = "details"
        ) { target, details ->
            updateProblemDetails(target.problemId, details) != null
        }
    }

    private fun refreshDetailsBatchAsyncInternal(jobId: String, targetProblems: List<Problem>) {
        executionRunner.runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) { worker ->
            processRefreshDetailsTargets(
                jobId = jobId,
                worker = worker,
                targets = targetProblems.map { ProblemTarget(it.id.value, it) },
                initialProgress = JobProgress(0, 0, 0)
            )
        }
    }

    private fun processRefreshDetailsTargets(
        jobId: String,
        worker: JobWorkerContext,
        targets: List<ProblemTarget>,
        initialProgress: JobProgress
    ) {
        processFetchedDetailsTargets(
            jobId = jobId,
            worker = worker,
            targets = targets,
            initialProgress = initialProgress,
            operationName = "refresh"
        ) { target, details ->
            val problem = requireNotNull(target.problem)
            val detectedLanguage = ProblemLanguageDetector.detectFromTexts(
                listOf(
                    problem.title,
                    details.descriptionHtml,
                    details.inputDescriptionHtml,
                    details.outputDescriptionHtml,
                    details.sampleInputs.joinToString("\n"),
                    details.sampleOutputs.joinToString("\n")
                )
            )
            updateProblemDetails(
                problemId = target.problemId,
                details = details,
                language = detectedLanguage
            ) != null
        }
    }

    private fun processFetchedDetailsTargets(
        jobId: String,
        worker: JobWorkerContext,
        targets: List<ProblemTarget>,
        initialProgress: JobProgress,
        operationName: String,
        store: (ProblemTarget, ProblemDetails) -> Boolean
    ) {
        val fetcher = parallelProblemDetailsFetcher
        if (fetcher == null) {
            processJobTargets(
                jobId = jobId,
                worker = worker,
                targets = targets,
                initialProgress = initialProgress,
                operationName = operationName,
                targetId = ProblemTarget::problemId,
                beforeProcess = pacer::pauseDetails
            ) { target ->
                if (target.problem == null) {
                    return@processJobTargets false
                }
                val details = bojCrawler.crawlProblemDetails(target.problemId)
                    ?: return@processJobTargets false
                store(target, details)
            }
            return
        }

        var processed = initialProgress.processedCount
        var success = initialProgress.successCount
        var fail = initialProgress.failCount

        fetcher.fetchOrdered<ProblemTarget, ProblemDetails?>(
            targets = targets,
            fetch = { target ->
                if (target.problem == null) {
                    null
                } else {
                    pacer.pauseDetails()
                    bojCrawler.crawlProblemDetails(target.problemId)
                }
            },
            onResult = { result ->
                if (!workerLeaseCoordinator.isWorkerRunning(jobId, worker)) {
                    return@fetchOrdered false
                }

                val problemId = result.target.problemId
                var failedProblemId: String? = null
                if (result.outcome is OrderedFetchOutcome.Failure) {
                    val cause = result.outcome.cause
                    when (cause) {
                        is InterruptedException -> throw cause
                        is CancellationException -> throw InterruptedException(
                            "problem details fetch was cancelled"
                        ).apply { initCause(cause) }
                    }
                }
                try {
                    val details = when (val outcome = result.outcome) {
                        is OrderedFetchOutcome.Success -> outcome.value
                        is OrderedFetchOutcome.Failure -> throw outcome.cause
                    }
                    if (details != null && store(result.target, details)) {
                        success++
                    } else {
                        fail++
                        failedProblemId = problemId
                    }
                } catch (e: Exception) {
                    fail++
                    failedProblemId = problemId
                    log.error(
                        "$operationName job item failed: jobId=$jobId, problemId=$problemId, error=${e.message}",
                        e
                    )
                }

                processed++
                workerLeaseCoordinator.updateProgress(
                    jobId,
                    worker,
                    processed,
                    success,
                    fail,
                    problemId,
                    failedProblemId
                )
            }
        )
    }

    private fun updateLanguageBatchAsyncInternal(jobId: String, targetProblems: List<Problem>) {
        executionRunner.runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) { worker ->
            processLanguageTargets(
                jobId = jobId,
                worker = worker,
                targets = targetProblems.map { ProblemTarget(it.id.value, it) },
                initialProgress = JobProgress(0, 0, 0)
            )
        }
    }

    private fun processLanguageTargets(
        jobId: String,
        worker: JobWorkerContext,
        targets: List<ProblemTarget>,
        initialProgress: JobProgress
    ) {
        processJobTargets(
            jobId = jobId,
            worker = worker,
            targets = targets,
            initialProgress = initialProgress,
            operationName = "language",
            targetId = ProblemTarget::problemId
        ) { target ->
            val problem = target.problem ?: return@processJobTargets false
            val detectedLanguage = ProblemLanguageDetector.detect(problem)
            detectedLanguage == null ||
                problem.language.equals(detectedLanguage, ignoreCase = true) ||
                problemRepository.updateLanguage(target.problemId, detectedLanguage)
        }
    }

    private fun <T> processJobTargets(
        jobId: String,
        worker: JobWorkerContext,
        targets: Iterable<T>,
        initialProgress: JobProgress,
        operationName: String,
        targetId: (T) -> String,
        beforeProcess: () -> Unit = {},
        process: (T) -> Boolean
    ) {
        var processed = initialProgress.processedCount
        var success = initialProgress.successCount
        var fail = initialProgress.failCount

        for (target in targets) {
            if (!workerLeaseCoordinator.isWorkerRunning(jobId, worker)) {
                return
            }

            val problemId = targetId(target)
            var failedProblemId: String? = null
            beforeProcess()
            try {
                if (process(target)) {
                    success++
                } else {
                    fail++
                    failedProblemId = problemId
                }
            } catch (e: Exception) {
                fail++
                failedProblemId = problemId
                log.error(
                    "$operationName job item failed: jobId=$jobId, problemId=$problemId, error=${e.message}",
                    e
                )
            }

            processed++
            if (
                !workerLeaseCoordinator.updateProgress(
                    jobId,
                    worker,
                    processed,
                    success,
                    fail,
                    problemId,
                    failedProblemId
                )
            ) {
                return
            }
        }
    }

    private fun submitRecoverableJob(job: JobStatusUnifiedResponse): Boolean {
        if (job.workerAttempt?.attemptNumber == Long.MAX_VALUE) {
            log.error("recoverable job attempt number is exhausted: jobId={}", job.jobId)
            return false
        }
        when (redisTemplate.type(leaseKey(job.jobId))) {
            DataType.STRING -> return false
            DataType.NONE -> Unit
            else -> {
                log.error("recoverable job has invalid lease type: jobId={}", job.jobId)
                return false
            }
        }

        return executionRunner.executeAsync(job.jobId) {
            runManifestJob(job.jobId)
        }
    }

    private fun runManifestJob(jobId: String) {
        executionRunner.runJobLoop(
            jobId = jobId,
            defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code,
            allowTakeover = true
        ) { worker ->
            workerLeaseCoordinator.readResumeState(jobId, worker) ?: return@runJobLoop
            val sourceJob = worker.sourceJob
            if (sourceJob.targetManifest == null) {
                workerLeaseCoordinator.markWorkerFailed(
                    jobId = jobId,
                    worker = worker,
                    errorCode = ErrorCode.WORKER_UNAVAILABLE.code,
                    message = "서버 재시작 전 형식의 작업은 자동으로 이어서 실행할 수 없습니다."
                )
                return@runJobLoop
            }
            val manifest = workerLeaseCoordinator.validateOwnedResumeState(sourceJob, worker)
                ?: return@runJobLoop
            val remainingTargets = dropManifestTargets(manifest, sourceJob.processedCount)
            val initialProgress = JobProgress(
                processedCount = sourceJob.processedCount,
                successCount = sourceJob.successCount,
                failCount = sourceJob.failCount
            )

            when (sourceJob.jobType) {
                ProblemJobType.COLLECT_METADATA -> {
                    val explicitIds = remainingTargets.explicitIds.map { problemId ->
                        requireNotNull(problemId.toIntOrNull()) {
                            "메타데이터 작업 대상 ID가 숫자가 아닙니다. problemId=$problemId"
                        }
                    }
                    processMetadataTargets(
                        jobId = jobId,
                        worker = worker,
                        problemIds = metadataTargetIds(explicitIds, remainingTargets.range),
                        initialProgress = initialProgress
                    )
                }

                ProblemJobType.COLLECT_DETAILS -> {
                    processDetailsTargets(
                        jobId = jobId,
                        worker = worker,
                        targets = loadProblemTargets(remainingTargets, jobId),
                        initialProgress = initialProgress
                    )
                }

                ProblemJobType.REFRESH_DETAILS -> {
                    processRefreshDetailsTargets(
                        jobId = jobId,
                        worker = worker,
                        targets = loadProblemTargets(remainingTargets, jobId),
                        initialProgress = initialProgress
                    )
                }

                ProblemJobType.UPDATE_LANGUAGE -> {
                    processLanguageTargets(
                        jobId = jobId,
                        worker = worker,
                        targets = loadProblemTargets(remainingTargets, jobId),
                        initialProgress = initialProgress
                    )
                }
            }
        }
    }

    private fun loadProblemTargets(
        selection: ManifestTargetSelection,
        jobId: String
    ): List<ProblemTarget> {
        check(selection.range == null) {
            "비메타데이터 작업에 범위 대상이 있습니다. jobId=$jobId"
        }
        if (selection.explicitIds.isEmpty()) {
            return emptyList()
        }

        val problemsById = problemRepository.findAllById(selection.explicitIds.toSet())
            .associateBy { problem -> problem.id.value }
        return selection.explicitIds.map { problemId ->
            ProblemTarget(problemId, problemsById[problemId])
        }
    }

    private fun createJob(
        type: ProblemJobType,
        range: JobRange?,
        createdBy: String,
        manifestExplicitIds: List<String>,
        manifestRange: JobRange?
    ): JobStatusUnifiedResponse {
        recoveryState.requireJobCreationReady()
        val now = stateStore.nowEpochSeconds()
        val jobId = UUID.randomUUID().toString()
        val manifest = ProblemJobTargetManifest(
            version = ProblemJobTargetManifest.CURRENT_VERSION,
            jobId = jobId,
            jobType = type,
            explicitIds = manifestExplicitIds.toList(),
            range = manifestRange
        )
        val totalCount = validateAndCountTargetManifest(manifest)
        val manifestJson = objectMapper.writeValueAsString(manifest)
        val created = JobStatusUnifiedResponse(
            jobId = jobId,
            jobType = type,
            status = JobStatus.PENDING,
            queuedAt = now,
            startedAt = null,
            lastHeartbeatAt = now,
            completedAt = null,
            totalCount = totalCount.coerceAtLeast(0),
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            progressPercentage = 0,
            estimatedRemainingSeconds = estimateRemaining(type, JobStatus.PENDING, 0, totalCount),
            queuePosition = null,
            range = range,
            lastCheckpointId = null,
            errorCode = null,
            errorMessage = null,
            createdBy = createdBy,
            targetManifest = ProblemJobTargetManifestReference(
                schemaVersion = manifest.version,
                sha256 = sha256(manifestJson)
            )
        )

        return stateStore.persistNewJob(created, manifestJson)
    }

    private fun getTypedJob(jobId: String, type: ProblemJobType): JobStatusUnifiedResponse? {
        val job = getJob(jobId) ?: return null
        return if (job.jobType == type) job else null
    }

    private fun withQueuePosition(job: JobStatusUnifiedResponse): JobStatusUnifiedResponse {
        if (job.status != JobStatus.PENDING) {
            return job.copy(queuePosition = null)
        }

        val pending = stateStore.loadAllJobsPruningStaleIndex()
            .filter { it.status == JobStatus.PENDING }
            .sortedWith(compareBy<JobStatusUnifiedResponse> { it.queuedAt }.thenBy { it.jobId })

        val index = pending.indexOfFirst { it.jobId == job.jobId }
        return job.copy(queuePosition = if (index >= 0) index + 1 else null)
    }

    private fun loadRetryProblemCandidates(
        currentCandidates: List<Problem>,
        failedProblemIds: Set<String>,
        jobId: String
    ): List<Problem> {
        if (failedProblemIds.isEmpty()) {
            return orderProblems(currentCandidates)
        }

        val failedProblems = problemRepository.findAllById(failedProblemIds)
        val foundIds = failedProblems.mapTo(mutableSetOf()) { it.id.value }
        val missingIds = failedProblemIds - foundIds
        if (missingIds.isNotEmpty()) {
            log.info("retry skips deleted failed problems: jobId=$jobId, missingCount=${missingIds.size}")
        }
        return orderProblems(currentCandidates + failedProblems)
    }

    private fun selectRetryProblems(
        original: JobStatusUnifiedResponse,
        candidates: List<Problem>,
        failedProblemIds: Set<String>,
        checkpointExclusive: Int?,
        usesLegacyRetryFallback: Boolean
    ): List<Problem> {
        if (usesLegacyRetryFallback) {
            return orderProblems(candidates)
        }
        return orderProblems(
            candidates.filter { problem ->
                failedProblemIds.contains(problem.id.value) ||
                    (original.status != JobStatus.COMPLETED && isAfterCheckpoint(problem, checkpointExclusive))
            }
        )
    }

    private fun solvedAcProblemUrl(problemId: Int): String {
        return "https://www.acmicpc.net/problem/$problemId"
    }
}
