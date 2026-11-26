package com.didimlog.problem.application.collector

import com.didimlog.admin.application.AdminAuditService
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.JOB_INDEX_KEY
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.MAX_JOB_STATE_RETRIES
import com.didimlog.problem.application.collector.ProblemJobStatusCalculator.isTerminal
import com.didimlog.problem.application.collector.ProblemJobTargets.dropManifestTargets
import com.didimlog.problem.application.collector.ProblemJobTargets.filterProblemsByRange
import com.didimlog.problem.application.collector.ProblemJobTargets.isAfterCheckpoint
import com.didimlog.problem.application.collector.ProblemJobTargets.orderProblems
import com.didimlog.problem.application.collector.ProblemJobTargets.targetIdAt
import com.didimlog.problem.application.collector.ProblemJobTargets.targetManifestConflict
import com.didimlog.problem.application.collector.ProblemJobTargets.targetPosition
import com.didimlog.problem.application.collector.ProblemJobTargets.validateRange
import com.didimlog.problem.domain.Problem
import com.didimlog.admin.domain.AdminActionType
import com.didimlog.problem.domain.repository.ProblemRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.problem.infra.crawler.BojCrawler
import com.didimlog.infra.solvedac.SolvedAcClient
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService

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
    private val jobQueries = ProblemJobQueries(stateStore)
    private val targetCollector = ProblemJobTargetCollector(
        solvedAcClient = solvedAcClient,
        problemRepository = problemRepository,
        bojCrawler = bojCrawler,
        pacer = pacer,
        parallelProblemDetailsFetcher = parallelProblemDetailsFetcher,
        executionRunner = executionRunner,
        workerLeaseCoordinator = workerLeaseCoordinator
    )
    private val jobLauncher = ProblemJobLauncher(
        redisTemplate = redisTemplate,
        objectMapper = objectMapper,
        recoveryState = recoveryState,
        stateStore = stateStore,
        auditLogger = auditLogger,
        executionRunner = executionRunner,
        targetCollector = targetCollector
    )

    companion object {
        private const val RESTART_ORPHAN_MESSAGE =
            "서버 재시작으로 실행 주체를 잃었습니다. 작업을 재시도해주세요."
    }

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
                targetCollector.upsertProblemMetadata(problemId)
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
                if (targetCollector.updateProblemDetails(problem.id.value, details) == null) {
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

        return jobLauncher.startCollectMetadataWithManifest(
            explicitTargetIds = emptyList(),
            targetRange = JobRange(start, end),
            responseRange = JobRange(start, end),
            createdBy = createdBy,
            ipAddress = ipAddress
        )
            .jobId
    }

    fun collectDetailsBatchAsync(createdBy: String = "system", ipAddress: String = "unknown"): String {
        return jobLauncher.startCollectDetailsWithTargets(
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

        return jobLauncher.startRefreshDetailsWithTargets(
            filterProblemsByRange(problemRepository.findAll(), start, end),
            createdBy,
            ipAddress,
            JobRange(start, end)
        ).jobId
    }

    fun updateLanguageBatchAsync(createdBy: String = "system", ipAddress: String = "unknown"): String {
        return jobLauncher.startUpdateLanguageWithTargets(
            problemRepository.findAll(),
            createdBy,
            ipAddress
        ).jobId
    }

    fun getMetadataCollectJobStatus(jobId: String): JobStatusUnifiedResponse? {
        return jobQueries.getTypedJob(jobId, ProblemJobType.COLLECT_METADATA)
    }

    fun getDetailsCollectJobStatus(jobId: String): JobStatusUnifiedResponse? {
        return jobQueries.getTypedJob(jobId, ProblemJobType.COLLECT_DETAILS)
    }

    fun getDetailsRefreshJobStatus(jobId: String): JobStatusUnifiedResponse? {
        return jobQueries.getTypedJob(jobId, ProblemJobType.REFRESH_DETAILS)
    }

    fun getLanguageUpdateJobStatus(jobId: String): JobStatusUnifiedResponse? {
        return jobQueries.getTypedJob(jobId, ProblemJobType.UPDATE_LANGUAGE)
    }

    fun getJob(jobId: String): JobStatusUnifiedResponse? {
        return jobQueries.getJob(jobId)
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
            .count { job -> jobLauncher.submitRecoverableJob(job) }
    }

    fun getJobs(
        type: ProblemJobType?,
        status: JobStatus?,
        from: Long?,
        to: Long?,
        page: Int,
        size: Int
    ): JobPageResponse<JobStatusUnifiedResponse> {
        return jobQueries.getJobs(type, status, from, to, page, size)
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
                return jobQueries.withQueuePosition(cancelled)
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
                    usesLegacyRetryFallback -> jobLauncher.startCollectMetadataWithManifest(
                        explicitTargetIds = emptyList(),
                        targetRange = JobRange(start, end),
                        responseRange = JobRange(start, end),
                        createdBy = requestedBy,
                        ipAddress = ipAddress
                    ).jobId

                    original.status == JobStatus.COMPLETED -> {
                        if (failedIds.isEmpty()) {
                            jobLauncher.createNoopCompletedJob(
                                ProblemJobType.COLLECT_METADATA,
                                JobRange(start, end),
                                requestedBy
                            ).jobId
                        } else {
                            jobLauncher.startCollectMetadataWithManifest(
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
                            jobLauncher.createNoopCompletedJob(
                                ProblemJobType.COLLECT_METADATA,
                                JobRange(start, end),
                                requestedBy
                            ).jobId
                        } else {
                            jobLauncher.startCollectMetadataWithManifest(
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
                jobLauncher.startCollectDetailsWithTargets(
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
                jobLauncher.startRefreshDetailsWithTargets(
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
                jobLauncher.startUpdateLanguageWithTargets(
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
                    jobLauncher.createNoopCompletedJob(
                        type = ProblemJobType.COLLECT_METADATA,
                        range = original.range,
                        createdBy = requestedBy
                    ).jobId
                } else {
                    jobLauncher.startCollectMetadataWithManifest(
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
                jobLauncher.startCollectDetailsWithTargets(
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
                jobLauncher.startRefreshDetailsWithTargets(
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
                jobLauncher.startUpdateLanguageWithTargets(
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
        return jobQueries.getJobMetrics(window)
    }

    fun getJobAudit(
        type: ProblemJobType?,
        status: JobStatus?,
        from: Long?,
        to: Long?,
        page: Int,
        size: Int
    ): JobPageResponse<JobAuditResponse> {
        return jobQueries.getJobAudit(type, status, from, to, page, size)
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
}
