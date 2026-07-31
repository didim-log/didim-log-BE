package com.didimlog.application.problem.collector

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.application.utils.ProblemLanguageDetector
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.AdminActionType
import com.didimlog.domain.repository.ProblemDetailsUpdate
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.crawler.ProblemDetails
import com.didimlog.infra.solvedac.ProblemCategoryMapper
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcTierMapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private val heartbeatExecutor: ScheduledExecutorService? = null
) {

    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)

    companion object {
        private const val JOB_KEY_PREFIX = "problem:job:status:"
        private const val JOB_FAILURE_KEY_PREFIX = "problem:job:failures:"
        private const val JOB_TARGET_KEY_PREFIX = "problem:job:targets:"
        private const val JOB_LEASE_KEY_PREFIX = "problem:job:lease:"
        private const val JOB_INDEX_KEY = "problem:job:index"
        private const val JOB_TTL_SECONDS = 86400L
        private const val MAX_JOB_STATE_RETRIES = 3
        private const val JOB_FAILURE_LEDGER_INVALID = -2L
        private const val JOB_STATE_MISSING = -1L
        private const val JOB_STATE_CONFLICT = 0L
        private const val JOB_STATE_UPDATED = 1L
        private const val JOB_LEASE_CONFLICT = -3L
        private const val JOB_LEASE_INVALID = -4L
        private const val JOB_LEASE_DURATION_INVALID = -6L
        private const val METADATA_AVG_SECONDS = 1L
        private const val DETAILS_AVG_SECONDS = 3L
        private const val LANGUAGE_AVG_SECONDS = 1L
        private const val RESTART_ORPHAN_MESSAGE =
            "서버 재시작으로 실행 주체를 잃었습니다. 작업을 재시도해주세요."
        private val CREATE_JOB_SCRIPT = DefaultRedisScript(
            """
            if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[3]) == 1 then
                return 0
            end

            local indexType = redis.call('TYPE', KEYS[2])
            if type(indexType) == 'table' then
                indexType = indexType['ok']
            end
            if indexType ~= 'none' and indexType ~= 'zset' then
                return -1
            end

            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
            redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[3])
            redis.call('ZADD', KEYS[2], ARGV[4], ARGV[5])
            return 1
            """.trimIndent(),
            Long::class.java
        )
        private val COMPARE_AND_SET_JOB_SCRIPT = DefaultRedisScript(
            """
            local current = redis.call('GET', KEYS[1])
            if not current then
                return -1
            end
            if current ~= ARGV[1] then
                return 0
            end

            local refreshTargetTtl = false
            if ARGV[6] ~= '' then
                local targetType = redis.call('TYPE', KEYS[3])
                if type(targetType) == 'table' then
                    targetType = targetType['ok']
                end
                if targetType == 'string' then
                    refreshTargetTtl = true
                end
            end

            if ARGV[4] ~= '' then
                local failureType = redis.call('TYPE', KEYS[2])
                if type(failureType) == 'table' then
                    failureType = failureType['ok']
                end
                if failureType ~= 'none' and failureType ~= 'set' then
                    return -2
                end
                redis.call('SADD', KEYS[2], ARGV[4])
            end

            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            if ARGV[5] ~= '0' then
                redis.call('EXPIRE', KEYS[2], ARGV[3])
            end
            if refreshTargetTtl then
                redis.call('EXPIRE', KEYS[3], ARGV[3])
            end
            if ARGV[7] == '1' then
                redis.call('DEL', KEYS[4])
            end
            return 1
            """.trimIndent(),
            Long::class.java
        )
        private val CLAIM_JOB_SCRIPT = DefaultRedisScript(
            """
            local current = redis.call('GET', KEYS[1])
            if not current then
                return -1
            end
            if current ~= ARGV[1] then
                return 0
            end

            local leaseMillis = tonumber(ARGV[5])
            if not leaseMillis or leaseMillis < 1 then
                return -6
            end

            local leaseType = redis.call('TYPE', KEYS[4])
            if type(leaseType) == 'table' then
                leaseType = leaseType['ok']
            end
            if leaseType == 'string' then
                return -3
            end
            if leaseType ~= 'none' then
                return -4
            end

            local refreshTargetTtl = false
            if ARGV[6] ~= '' then
                local targetType = redis.call('TYPE', KEYS[3])
                if type(targetType) == 'table' then
                    targetType = targetType['ok']
                end
                if targetType == 'string' then
                    refreshTargetTtl = true
                end
            end

            local failureType = redis.call('TYPE', KEYS[2])
            if type(failureType) == 'table' then
                failureType = failureType['ok']
            end

            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            redis.call('SET', KEYS[4], ARGV[4], 'PX', ARGV[5])
            if failureType == 'set' then
                redis.call('EXPIRE', KEYS[2], ARGV[3])
            end
            if refreshTargetTtl then
                redis.call('EXPIRE', KEYS[3], ARGV[3])
            end
            return 1
            """.trimIndent(),
            Long::class.java
        )
        private val OWNED_COMPARE_AND_SET_JOB_SCRIPT = DefaultRedisScript(
            """
            local current = redis.call('GET', KEYS[1])
            if not current then
                return -1
            end
            if current ~= ARGV[1] then
                return 0
            end

            local leaseMillis = tonumber(ARGV[8])
            if not leaseMillis or leaseMillis < 1 then
                return -6
            end

            local leaseType = redis.call('TYPE', KEYS[4])
            if type(leaseType) == 'table' then
                leaseType = leaseType['ok']
            end
            if leaseType == 'none' then
                return -3
            end
            if leaseType ~= 'string' then
                return -4
            end
            if redis.call('GET', KEYS[4]) ~= ARGV[7] then
                return -3
            end

            local refreshTargetTtl = false
            if ARGV[6] ~= '' then
                local targetType = redis.call('TYPE', KEYS[3])
                if type(targetType) == 'table' then
                    targetType = targetType['ok']
                end
                if targetType == 'string' then
                    refreshTargetTtl = true
                end
            end

            if ARGV[4] ~= '' then
                local failureType = redis.call('TYPE', KEYS[2])
                if type(failureType) == 'table' then
                    failureType = failureType['ok']
                end
                if failureType ~= 'none' and failureType ~= 'set' then
                    return -2
                end
            end

            if ARGV[4] ~= '' then
                redis.call('SADD', KEYS[2], ARGV[4])
            end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            if ARGV[5] ~= '0' then
                redis.call('EXPIRE', KEYS[2], ARGV[3])
            end
            if refreshTargetTtl then
                redis.call('EXPIRE', KEYS[3], ARGV[3])
            end
            if ARGV[9] == '1' then
                redis.call('DEL', KEYS[4])
            else
                redis.call('PEXPIRE', KEYS[4], ARGV[8])
            end
            return 1
            """.trimIndent(),
            Long::class.java
        )
        private val RENEW_JOB_LEASE_SCRIPT = DefaultRedisScript(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """.trimIndent(),
            Long::class.java
        )
        private val RELEASE_JOB_LEASE_SCRIPT = DefaultRedisScript(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """.trimIndent(),
            Long::class.java
        )
    }

    private data class JobSnapshot(
        val rawJson: String,
        val job: JobStatusUnifiedResponse
    )

    private data class FailureLedger(
        val exists: Boolean,
        val problemIds: Set<String>
    )

    private data class ManifestTargetSelection(
        val explicitIds: List<String>,
        val range: JobRange?
    )

    private data class JobWorkerContext(
        val attempt: ProblemJobWorkerAttempt?,
        val leaseValue: String?,
        val sourceJob: JobStatusUnifiedResponse,
        val ownershipLost: AtomicBoolean = AtomicBoolean(false)
    )

    private data class ProblemTarget(
        val problemId: String,
        val problem: Problem?
    )

    private data class JobProgress(
        val processedCount: Int,
        val successCount: Int,
        val failCount: Int
    )

    private val scheduledJobIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Solved.ac API를 통해 문제 메타데이터를 수집하여 DB에 저장한다 (Upsert).
     */
    @Transactional
    fun collectMetadata(start: Int, end: Int) {
        log.info("문제 메타데이터 수집 시작: start=$start, end=$end")
        var successCount = 0
        var failCount = 0

        for (problemId in start..end) {
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

            pacer.pauseMetadata()
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
                val details = bojCrawler.crawlProblemDetails(problem.id.value) ?: continue
                if (updateProblemDetails(problem.id.value, details) == null) {
                    log.warn("문제 상세 정보 저장 대상 없음: problemId=${problem.id.value}")
                }
                pacer.pauseDetails()
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
        val raw = readJob(jobId) ?: return null
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
            markFailed(
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

        return loadAllJobs()
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

        val filtered = withQueuePositions(loadAllJobs())
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
            val snapshot = readJobSnapshot(jobId)
                ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")
            val current = snapshot.job
            if (isTerminal(current.status)) {
                throw BusinessException(ErrorCode.JOB_ALREADY_TERMINAL, "이미 종료된 작업입니다. jobId=$jobId, status=${current.status}")
            }

            val now = nowEpochSeconds()
            val updated = current.copy(
                status = JobStatus.CANCELLED,
                completedAt = now,
                lastHeartbeatAt = now,
                errorCode = null,
                errorMessage = null
            )
            val cancelled = compareAndSetJob(snapshot, updated)
            if (cancelled != null) {
                logJobAction(cancelledBy, AdminActionType.PROBLEM_JOB_CANCEL, ipAddress, cancelled)
                return withQueuePosition(cancelled)
            }
        }

        throw BusinessException(
            ErrorCode.RESOURCE_STATE_CONFLICT,
            "작업 상태가 계속 변경되어 취소하지 못했습니다. jobId=$jobId"
        )
    }

    fun retryJob(jobId: String, requestedBy: String, ipAddress: String = "unknown"): JobStatusUnifiedResponse {
        val original = readJob(jobId)
            ?: throw BusinessException(ErrorCode.JOB_NOT_FOUND, "작업을 찾을 수 없습니다. jobId=$jobId")

        if (!isTerminal(original.status)) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "RUNNING/PENDING 작업은 재시도할 수 없습니다. jobId=$jobId")
        }

        val failureLedger = readFailureLedger(jobId)
        val failedProblemIds = failureLedger.problemIds
        val targetManifest = readTargetManifest(original)
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

        logJobAction(
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
        logJobAction(
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
        val targetCount = validateManifestForJob(original, manifest)
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
            JobMetricsWindow.DAY -> nowEpochSeconds() - Duration.ofDays(1).seconds
            JobMetricsWindow.WEEK -> nowEpochSeconds() - Duration.ofDays(7).seconds
            JobMetricsWindow.MONTH -> nowEpochSeconds() - Duration.ofDays(30).seconds
        }

        val jobs = loadAllJobs().filter { it.queuedAt >= threshold }
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
        logJobAction(createdBy, AdminActionType.PROBLEM_JOB_CREATE, ipAddress, job)
        executeAsync(job.jobId) { collectDetailsBatchAsyncInternal(job.jobId, orderedTargets) }
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
        logJobAction(createdBy, AdminActionType.PROBLEM_JOB_CREATE, ipAddress, job)
        executeAsync(job.jobId) { collectMetadataAsyncInternal(job.jobId, targetIds) }
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
        logJobAction(createdBy, AdminActionType.PROBLEM_JOB_CREATE, ipAddress, job)
        executeAsync(job.jobId) { refreshDetailsBatchAsyncInternal(job.jobId, orderedTargets) }
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
        logJobAction(createdBy, AdminActionType.PROBLEM_JOB_CREATE, ipAddress, job)
        executeAsync(job.jobId) { updateLanguageBatchAsyncInternal(job.jobId, orderedTargets) }
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
        runJobLoop(
            jobId = created.jobId,
            defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code
        ) { }
        return readJob(created.jobId)
            ?: throw IllegalStateException("생성한 작업 상태를 찾을 수 없습니다. jobId=${created.jobId}")
    }

    private fun collectMetadataAsyncInternal(jobId: String, problemIds: Iterable<Int>) {
        runJobLoop(
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
            pause = pacer::pauseMetadata
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
        runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) { worker ->
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
        processJobTargets(
            jobId = jobId,
            worker = worker,
            targets = targets,
            initialProgress = initialProgress,
            operationName = "details",
            targetId = ProblemTarget::problemId,
            pause = pacer::pauseDetails
        ) { target ->
            if (target.problem == null) {
                return@processJobTargets false
            }
            val details = bojCrawler.crawlProblemDetails(target.problemId)
                ?: return@processJobTargets false
            updateProblemDetails(target.problemId, details) != null
        }
    }

    private fun refreshDetailsBatchAsyncInternal(jobId: String, targetProblems: List<Problem>) {
        runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) { worker ->
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
        processJobTargets(
            jobId = jobId,
            worker = worker,
            targets = targets,
            initialProgress = initialProgress,
            operationName = "refresh",
            targetId = ProblemTarget::problemId,
            pause = pacer::pauseDetails
        ) { target ->
            val problem = target.problem
                ?: return@processJobTargets false
            val details = bojCrawler.crawlProblemDetails(target.problemId)
                ?: return@processJobTargets false
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

    private fun updateLanguageBatchAsyncInternal(jobId: String, targetProblems: List<Problem>) {
        runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) { worker ->
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
        pause: () -> Unit = {},
        process: (T) -> Boolean
    ) {
        var processed = initialProgress.processedCount
        var success = initialProgress.successCount
        var fail = initialProgress.failCount

        for (target in targets) {
            if (!isWorkerRunning(jobId, worker)) {
                return
            }

            val problemId = targetId(target)
            var failedProblemId: String? = null
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
                !updateProgress(
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
            pause()
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

        return executeAsync(job.jobId) {
            runManifestJob(job.jobId)
        }
    }

    private fun runManifestJob(jobId: String) {
        runJobLoop(
            jobId = jobId,
            defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code,
            allowTakeover = true
        ) { worker ->
            readResumeState(jobId, worker) ?: return@runJobLoop
            val sourceJob = worker.sourceJob
            if (sourceJob.targetManifest == null) {
                markWorkerFailed(
                    jobId = jobId,
                    worker = worker,
                    errorCode = ErrorCode.WORKER_UNAVAILABLE.code,
                    message = "서버 재시작 전 형식의 작업은 자동으로 이어서 실행할 수 없습니다."
                )
                return@runJobLoop
            }
            val manifest = validateOwnedResumeState(sourceJob, worker)
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

    private fun readResumeState(
        jobId: String,
        worker: JobWorkerContext
    ): JobStatusUnifiedResponse? {
        return try {
            readOwnedWorkerJob(jobId, worker)
        } catch (e: RuntimeException) {
            worker.ownershipLost.set(true)
            log.error("recoverable job state read failed: jobId={}, error={}", jobId, e.message)
            null
        }
    }

    private fun readOwnedWorkerJob(
        jobId: String,
        worker: JobWorkerContext
    ): JobStatusUnifiedResponse? {
        val current = readJob(jobId) ?: return null
        if (!isWorkerSnapshot(current, worker)) {
            worker.ownershipLost.set(true)
            return null
        }
        return current
    }

    private fun validateOwnedResumeState(
        job: JobStatusUnifiedResponse,
        worker: JobWorkerContext
    ): ProblemJobTargetManifest? {
        return try {
            validateResumeState(job)
        } catch (e: BusinessException) {
            throw e
        } catch (e: RuntimeException) {
            worker.ownershipLost.set(true)
            log.error("recoverable job validation read failed: jobId={}, error={}", job.jobId, e.message)
            null
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

    private fun executeAsync(jobId: String, block: () -> Unit): Boolean {
        if (!scheduledJobIds.add(jobId)) {
            return false
        }

        val scheduledTask = Runnable {
            try {
                block()
            } finally {
                scheduledJobIds.remove(jobId)
            }
        }
        val executor = taskExecutor
        if (executor == null) {
            scheduledTask.run()
            return true
        }

        try {
            executor.execute(scheduledTask)
            return true
        } catch (e: RejectedExecutionException) {
            scheduledJobIds.remove(jobId)
            log.error("job submission rejected: jobId=$jobId, error=${e.message}", e)
            if (!workerLeaseProperties.enabled) {
                markFailed(jobId, ErrorCode.WORKER_UNAVAILABLE.code, "작업 실행을 제출할 수 없습니다.")
            }
            return false
        }
    }

    private fun runJobLoop(
        jobId: String,
        defaultFailureCode: String,
        allowTakeover: Boolean = false,
        block: (JobWorkerContext) -> Unit
    ) {
        val worker = claimWorker(jobId, allowTakeover) ?: return
        var heartbeat: ScheduledFuture<*>? = null

        try {
            heartbeat = startHeartbeat(jobId, worker)
            block(worker)
            if (isWorkerRunning(jobId, worker)) {
                markWorkerCompleted(jobId, worker)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            markWorkerFailed(
                jobId,
                worker,
                ErrorCode.WORKER_UNAVAILABLE.code,
                "작업이 인터럽트되었습니다."
            )
        } catch (e: BusinessException) {
            log.warn("job state validation failed: jobId=$jobId, error=${e.message}")
            markWorkerFailed(
                jobId,
                worker,
                e.errorCode.code,
                e.message ?: e.errorCode.message
            )
        } catch (e: Exception) {
            log.error("job failed unexpectedly: jobId=$jobId, error=${e.message}", e)
            markWorkerFailed(jobId, worker, defaultFailureCode, e.message ?: "unexpected error")
        } finally {
            heartbeat?.cancel(false)
            releaseWorkerLease(jobId, worker)
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
        val now = nowEpochSeconds()
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

        return persistNewJob(created, manifestJson)
    }

    private fun getTypedJob(jobId: String, type: ProblemJobType): JobStatusUnifiedResponse? {
        val job = getJob(jobId) ?: return null
        return if (job.jobType == type) job else null
    }

    private fun claimWorker(
        jobId: String,
        allowTakeover: Boolean = false
    ): JobWorkerContext? {
        val snapshot = readJobSnapshot(jobId) ?: return null
        val canClaimPending = snapshot.job.status == JobStatus.PENDING
        val canTakeOverRunning = workerLeaseProperties.enabled &&
            allowTakeover &&
            snapshot.job.status == JobStatus.RUNNING
        if (!canClaimPending && !canTakeOverRunning) {
            return null
        }

        val now = nowEpochSeconds()
        if (!workerLeaseProperties.enabled) {
            val running = snapshot.job.copy(
                status = JobStatus.RUNNING,
                startedAt = now,
                lastHeartbeatAt = now,
                errorCode = null,
                errorMessage = null
            )
            return if (compareAndSetJob(snapshot, running) != null) {
                JobWorkerContext(
                    attempt = null,
                    leaseValue = null,
                    sourceJob = snapshot.job
                )
            } else {
                null
            }
        }

        val previousAttemptNumber = snapshot.job.workerAttempt?.attemptNumber ?: 0L
        if (previousAttemptNumber == Long.MAX_VALUE) {
            log.error("job attempt number is exhausted: jobId={}", jobId)
            return null
        }
        val attempt = ProblemJobWorkerAttempt(
            ownerId = workerIdentity.ownerId,
            attemptId = UUID.randomUUID().toString(),
            attemptNumber = previousAttemptNumber + 1L
        )
        val running = snapshot.job.copy(
            status = JobStatus.RUNNING,
            startedAt = snapshot.job.startedAt ?: now,
            lastHeartbeatAt = now,
            completedAt = null,
            estimatedRemainingSeconds = estimateRemaining(
                snapshot.job.jobType,
                JobStatus.RUNNING,
                snapshot.job.processedCount,
                snapshot.job.totalCount
            ),
            queuePosition = null,
            errorCode = null,
            errorMessage = null,
            workerAttempt = attempt
        )
        val leaseValue = objectMapper.writeValueAsString(attempt)
        val result = redisTemplate.execute(
            CLAIM_JOB_SCRIPT,
            jobStateKeys(jobId),
            snapshot.rawJson,
            objectMapper.writeValueAsString(running),
            JOB_TTL_SECONDS.toString(),
            leaseValue,
            workerLeaseProperties.leaseDuration.toMillis().toString(),
            running.targetManifest?.schemaVersion?.toString().orEmpty()
        )
        return when (result) {
            JOB_STATE_UPDATED -> JobWorkerContext(
                attempt = attempt,
                leaseValue = leaseValue,
                sourceJob = snapshot.job
            )
            JOB_STATE_MISSING,
            JOB_STATE_CONFLICT,
            JOB_LEASE_CONFLICT -> null
            JOB_FAILURE_LEDGER_INVALID -> throw IllegalStateException(
                "실패 항목 원장 Redis 타입이 올바르지 않습니다. jobId=$jobId"
            )
            JOB_LEASE_INVALID -> throw IllegalStateException(
                "작업 lease Redis 타입이 올바르지 않습니다. jobId=$jobId"
            )
            JOB_LEASE_DURATION_INVALID -> throw IllegalStateException(
                "작업 lease 시간이 올바르지 않습니다. jobId=$jobId"
            )
            else -> throw IllegalStateException(
                "알 수 없는 작업 선점 결과입니다. jobId=$jobId, result=$result"
            )
        }
    }

    private fun markWorkerCompleted(jobId: String, worker: JobWorkerContext) {
        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = readJobSnapshot(jobId) ?: return
            if (!isWorkerSnapshot(snapshot.job, worker)) {
                return
            }
            if (snapshot.job.processedCount != snapshot.job.totalCount) {
                log.error(
                    "job completion rejected due to incomplete progress: jobId={}, processed={}, total={}",
                    jobId,
                    snapshot.job.processedCount,
                    snapshot.job.totalCount
                )
                markWorkerFailed(
                    jobId,
                    worker,
                    ErrorCode.WORKER_UNAVAILABLE.code,
                    "처리 수가 전체 대상 수와 일치하지 않아 작업을 완료하지 못했습니다."
                )
                return
            }

            val now = nowEpochSeconds()
            val completed = snapshot.job.copy(
                status = JobStatus.COMPLETED,
                completedAt = now,
                lastHeartbeatAt = now
            )
            if (compareAndSetWorkerJob(snapshot, completed, worker) != null) {
                return
            }
        }
        log.warn("job completion CAS retries exhausted: jobId=$jobId")
    }

    private fun markWorkerFailed(
        jobId: String,
        worker: JobWorkerContext,
        errorCode: String,
        message: String
    ): Boolean {
        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = readJobSnapshot(jobId) ?: return false
            if (!isWorkerSnapshot(snapshot.job, worker)) {
                return false
            }

            val now = nowEpochSeconds()
            val failed = snapshot.job.copy(
                status = JobStatus.FAILED,
                completedAt = now,
                lastHeartbeatAt = now,
                errorCode = errorCode,
                errorMessage = message
            )
            if (compareAndSetWorkerJob(snapshot, failed, worker) != null) {
                return true
            }
        }
        log.warn("owned job failure CAS retries exhausted: jobId=$jobId")
        return false
    }

    private fun markFailed(jobId: String, errorCode: String, message: String): Boolean {
        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = readJobSnapshot(jobId) ?: return false
            if (snapshot.job.status != JobStatus.PENDING && snapshot.job.status != JobStatus.RUNNING) {
                return false
            }

            val now = nowEpochSeconds()
            val failed = snapshot.job.copy(
                status = JobStatus.FAILED,
                completedAt = now,
                lastHeartbeatAt = now,
                errorCode = errorCode,
                errorMessage = message
            )
            if (compareAndSetJob(snapshot, failed) != null) {
                return true
            }
        }
        log.warn("job failure CAS retries exhausted: jobId=$jobId")
        return false
    }

    private fun updateProgress(
        jobId: String,
        worker: JobWorkerContext,
        processedCount: Int,
        successCount: Int,
        failCount: Int,
        checkpointId: String?,
        failedProblemId: String?
    ): Boolean {
        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = readJobSnapshot(jobId) ?: run {
                worker.ownershipLost.set(true)
                return false
            }
            if (!isWorkerSnapshot(snapshot.job, worker)) {
                return false
            }
            check(processedCount == snapshot.job.processedCount + 1) {
                "작업 진행 수는 한 건씩 증가해야 합니다. jobId=$jobId"
            }
            check(successCount >= snapshot.job.successCount && failCount >= snapshot.job.failCount) {
                "작업 성공 또는 실패 수는 감소할 수 없습니다. jobId=$jobId"
            }
            check(successCount + failCount == processedCount) {
                "작업 처리 수가 성공 수와 실패 수의 합과 다릅니다. jobId=$jobId"
            }
            check((failedProblemId != null) == (failCount == snapshot.job.failCount + 1)) {
                "실패 항목과 실패 수 증가가 일치하지 않습니다. jobId=$jobId"
            }

            val now = nowEpochSeconds()
            val updated = snapshot.job.copy(
                processedCount = processedCount,
                successCount = successCount,
                failCount = failCount,
                lastCheckpointId = checkpointId ?: snapshot.job.lastCheckpointId,
                lastHeartbeatAt = now
            )
            if (compareAndSetWorkerJob(snapshot, updated, worker, failedProblemId) != null) {
                return true
            }
        }
        log.warn("job progress CAS retries exhausted: jobId=$jobId")
        worker.ownershipLost.set(true)
        return false
    }

    private fun startHeartbeat(
        jobId: String,
        worker: JobWorkerContext
    ): ScheduledFuture<*>? {
        if (worker.attempt == null) {
            return null
        }

        val executor = checkNotNull(heartbeatExecutor) {
            "worker lease를 활성화하려면 problem collector heartbeat executor가 필요합니다."
        }
        val intervalMillis = workerLeaseProperties.heartbeatInterval.toMillis()
        return executor.scheduleAtFixedRate(
            {
                try {
                    updateHeartbeat(jobId, worker)
                } catch (e: RuntimeException) {
                    worker.ownershipLost.set(true)
                    log.error("job heartbeat failed: jobId=$jobId, error=${e.message}", e)
                }
            },
            intervalMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS
        )
    }

    private fun updateHeartbeat(jobId: String, worker: JobWorkerContext) {
        if (worker.ownershipLost.get()) {
            return
        }
        val leaseValue = worker.leaseValue ?: return
        val renewed = redisTemplate.execute(
            RENEW_JOB_LEASE_SCRIPT,
            listOf(leaseKey(jobId)),
            leaseValue,
            workerLeaseProperties.leaseDuration.toMillis().toString()
        )
        if (renewed != JOB_STATE_UPDATED) {
            worker.ownershipLost.set(true)
            log.warn("job lease ownership lost during heartbeat: jobId=$jobId")
            return
        }

        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = readJobSnapshot(jobId) ?: run {
                worker.ownershipLost.set(true)
                return
            }
            if (!isWorkerSnapshot(snapshot.job, worker)) {
                worker.ownershipLost.set(true)
                return
            }

            val updated = snapshot.job.copy(lastHeartbeatAt = nowEpochSeconds())
            if (compareAndSetWorkerJob(snapshot, updated, worker) != null) {
                return
            }
            if (worker.ownershipLost.get()) {
                return
            }
        }
        log.warn("job heartbeat state CAS retries exhausted: jobId=$jobId")
    }

    private fun releaseWorkerLease(jobId: String, worker: JobWorkerContext) {
        val leaseValue = worker.leaseValue ?: return
        try {
            redisTemplate.execute(
                RELEASE_JOB_LEASE_SCRIPT,
                listOf(leaseKey(jobId)),
                leaseValue
            )
        } catch (e: RuntimeException) {
            log.error("job lease release failed: jobId=$jobId, error=${e.message}", e)
        }
    }

    private fun isWorkerRunning(jobId: String, worker: JobWorkerContext): Boolean {
        if (worker.ownershipLost.get()) {
            return false
        }
        val current = readJob(jobId) ?: return false
        if (!isWorkerSnapshot(current, worker)) {
            return false
        }

        val leaseValue = worker.leaseValue ?: return true
        val owned = redisTemplate.opsForValue().get(leaseKey(jobId)) == leaseValue
        if (!owned) {
            worker.ownershipLost.set(true)
        }
        return owned
    }

    private fun isWorkerSnapshot(
        job: JobStatusUnifiedResponse,
        worker: JobWorkerContext
    ): Boolean {
        if (job.status != JobStatus.RUNNING) {
            return false
        }
        val attempt = worker.attempt
        return if (attempt == null) {
            job.workerAttempt == null
        } else {
            !worker.ownershipLost.get() && job.workerAttempt == attempt
        }
    }

    private fun readJob(jobId: String): JobStatusUnifiedResponse? {
        return readJobSnapshot(jobId)?.job
    }

    private fun readFailureLedger(jobId: String): FailureLedger {
        val key = failureKey(jobId)
        return when (redisTemplate.type(key)) {
            DataType.NONE -> FailureLedger(exists = false, problemIds = emptySet())

            DataType.SET -> FailureLedger(
                exists = true,
                problemIds = redisTemplate.opsForSet().members(key).orEmpty()
            )

            else -> throw BusinessException(
                ErrorCode.RESOURCE_STATE_CONFLICT,
                "실패 항목 원장 Redis 타입이 올바르지 않습니다. jobId=$jobId"
            )
        }
    }

    private fun validateResumeState(
        job: JobStatusUnifiedResponse
    ): ProblemJobTargetManifest {
        if (job.status != JobStatus.PENDING && job.status != JobStatus.RUNNING) {
            targetManifestConflict(job.jobId, "종료된 작업은 이어서 실행할 수 없습니다.")
        }
        if (job.completedAt != null) {
            targetManifestConflict(job.jobId, "실행 중 작업에 종료 시각이 있습니다.")
        }
        if (job.status == JobStatus.PENDING) {
            if (
                job.startedAt != null ||
                job.workerAttempt != null ||
                job.processedCount != 0 ||
                job.successCount != 0 ||
                job.failCount != 0
            ) {
                targetManifestConflict(job.jobId, "대기 작업에 실행 이력 또는 진행 상태가 있습니다.")
            }
        } else {
            val attempt = job.workerAttempt
                ?: targetManifestConflict(job.jobId, "실행 중 작업에 worker attempt가 없습니다.")
            if (
                attempt.schemaVersion != ProblemJobWorkerAttempt.CURRENT_SCHEMA_VERSION ||
                attempt.ownerId.isBlank() ||
                attempt.attemptId.isBlank() ||
                attempt.attemptNumber < 1
            ) {
                targetManifestConflict(job.jobId, "worker attempt가 올바르지 않습니다.")
            }
        }
        if (job.processedCount !in 0..job.totalCount) {
            targetManifestConflict(
                job.jobId,
                "처리 수가 대상 수 범위를 벗어났습니다. processed=${job.processedCount}, total=${job.totalCount}"
            )
        }
        if (job.successCount < 0 || job.failCount < 0) {
            targetManifestConflict(job.jobId, "성공 또는 실패 수가 음수입니다.")
        }
        if (job.successCount + job.failCount != job.processedCount) {
            targetManifestConflict(job.jobId, "처리 수가 성공 수와 실패 수의 합과 다릅니다.")
        }

        val manifest = readTargetManifest(job)
            ?: targetManifestConflict(job.jobId, "대상 manifest 참조가 없습니다.")
        if (job.processedCount == 0) {
            if (job.lastCheckpointId != null) {
                targetManifestConflict(job.jobId, "처리 전 작업에 체크포인트가 있습니다.")
            }
        } else {
            val expectedCheckpoint = targetIdAt(manifest, job.processedCount - 1)
            if (job.lastCheckpointId != expectedCheckpoint) {
                targetManifestConflict(job.jobId, "체크포인트가 manifest 처리 위치와 일치하지 않습니다.")
            }
        }

        val failureLedger = readFailureLedger(job.jobId)
        if (failureLedger.problemIds.size != job.failCount) {
            targetManifestConflict(
                job.jobId,
                "실패 수가 실패 항목 기록 수와 다릅니다. fail=${job.failCount}, recorded=${failureLedger.problemIds.size}"
            )
        }
        failureLedger.problemIds.forEach { failedProblemId ->
            val position = targetPosition(manifest, failedProblemId)
                ?: targetManifestConflict(
                    job.jobId,
                    "실패 항목이 manifest에 없습니다. problemId=$failedProblemId"
                )
            if (position >= job.processedCount) {
                targetManifestConflict(
                    job.jobId,
                    "실패 항목이 처리 완료 prefix 밖에 있습니다. problemId=$failedProblemId"
                )
            }
        }
        return manifest
    }

    private fun readTargetManifest(
        job: JobStatusUnifiedResponse
    ): ProblemJobTargetManifest? {
        val reference = job.targetManifest ?: return null
        if (reference.schemaVersion != ProblemJobTargetManifest.CURRENT_VERSION) {
            targetManifestConflict(
                job.jobId,
                "지원하지 않는 manifest 참조 버전입니다. version=${reference.schemaVersion}"
            )
        }

        val key = targetKey(job.jobId)
        if (redisTemplate.type(key) != DataType.STRING) {
            targetManifestConflict(job.jobId, "대상 manifest key가 없거나 문자열이 아닙니다.")
        }
        val rawJson = redisTemplate.opsForValue().get(key)
            ?: targetManifestConflict(job.jobId, "대상 manifest를 찾을 수 없습니다.")
        if (sha256(rawJson) != reference.sha256) {
            targetManifestConflict(job.jobId, "대상 manifest hash가 상태 참조와 일치하지 않습니다.")
        }

        val manifest = runCatching {
            objectMapper.readValue(rawJson, ProblemJobTargetManifest::class.java)
        }.getOrElse {
            targetManifestConflict(job.jobId, "대상 manifest JSON을 읽을 수 없습니다.")
        }
        validateManifestForJob(job, manifest)
        return manifest
    }

    private fun validateManifestForJob(
        job: JobStatusUnifiedResponse,
        manifest: ProblemJobTargetManifest
    ): Int {
        if (manifest.jobId != job.jobId) {
            targetManifestConflict(job.jobId, "대상 manifest jobId가 작업 상태와 다릅니다.")
        }
        if (manifest.jobType != job.jobType) {
            targetManifestConflict(job.jobId, "대상 manifest 작업 유형이 작업 상태와 다릅니다.")
        }
        val targetCount = try {
            validateAndCountTargetManifest(manifest)
        } catch (e: IllegalArgumentException) {
            targetManifestConflict(job.jobId, e.message ?: "대상 manifest가 올바르지 않습니다.")
        }
        if (targetCount != job.totalCount) {
            targetManifestConflict(
                job.jobId,
                "대상 수가 작업 상태와 다릅니다. manifest=$targetCount, status=${job.totalCount}"
            )
        }
        return targetCount
    }

    private fun targetPosition(
        manifest: ProblemJobTargetManifest,
        targetId: String
    ): Int? {
        val explicitIndex = manifest.explicitIds.indexOf(targetId)
        if (explicitIndex >= 0) {
            return explicitIndex
        }

        val start = manifest.range?.start ?: return null
        val end = manifest.range.end ?: return null
        val numericId = targetId.toIntOrNull() ?: return null
        if (numericId !in start..end) {
            return null
        }
        return manifest.explicitIds.size + (numericId - start)
    }

    private fun targetIdAt(
        manifest: ProblemJobTargetManifest,
        index: Int
    ): String {
        if (index < manifest.explicitIds.size) {
            return manifest.explicitIds[index]
        }
        val start = manifest.range?.start
            ?: targetManifestConflict(manifest.jobId, "manifest 대상 위치가 범위를 벗어났습니다.")
        val end = manifest.range.end
            ?: targetManifestConflict(manifest.jobId, "manifest 범위의 end가 없습니다.")
        val numericId = start.toLong() + index.toLong() - manifest.explicitIds.size.toLong()
        if (numericId > end.toLong()) {
            targetManifestConflict(manifest.jobId, "manifest 대상 위치가 범위를 벗어났습니다.")
        }
        return numericId.toString()
    }

    private fun dropManifestTargets(
        manifest: ProblemJobTargetManifest,
        processedCount: Int
    ): ManifestTargetSelection {
        if (processedCount < manifest.explicitIds.size) {
            return ManifestTargetSelection(
                explicitIds = manifest.explicitIds.drop(processedCount),
                range = manifest.range
            )
        }

        val consumedFromRange = processedCount - manifest.explicitIds.size
        val range = manifest.range ?: return ManifestTargetSelection(emptyList(), null)
        val start = requireNotNull(range.start)
        val end = requireNotNull(range.end)
        val remainingStart = start.toLong() + consumedFromRange.toLong()
        return if (remainingStart > end.toLong()) {
            ManifestTargetSelection(emptyList(), null)
        } else {
            ManifestTargetSelection(
                explicitIds = emptyList(),
                range = JobRange(remainingStart.toInt(), end)
            )
        }
    }

    private fun targetManifestConflict(jobId: String, reason: String): Nothing {
        throw BusinessException(
            ErrorCode.RESOURCE_STATE_CONFLICT,
            "작업 대상 manifest가 올바르지 않습니다. jobId=$jobId, reason=$reason"
        )
    }

    private fun readJobSnapshot(jobId: String): JobSnapshot? {
        val json = redisTemplate.opsForValue().get(jobKey(jobId)) ?: return null
        return deserializeJob(jobId, json)
            ?.let { JobSnapshot(json, it) }
    }

    private fun deserializeJob(jobId: String, json: String): JobStatusUnifiedResponse? {
        return runCatching { objectMapper.readValue(json, JobStatusUnifiedResponse::class.java) }
            .onFailure { e ->
                log.warn("Failed to deserialize job status: jobId=$jobId, error=${e.message}")
            }
            .getOrNull()
    }

    private fun persistNewJob(
        job: JobStatusUnifiedResponse,
        manifestJson: String
    ): JobStatusUnifiedResponse {
        val normalized = normalize(job)
        val key = jobKey(normalized.jobId)
        val json = objectMapper.writeValueAsString(normalized)
        val result = redisTemplate.execute(
            CREATE_JOB_SCRIPT,
            listOf(key, JOB_INDEX_KEY, targetKey(normalized.jobId)),
            json,
            manifestJson,
            JOB_TTL_SECONDS.toString(),
            normalized.queuedAt.toString(),
            normalized.jobId
        )
        check(result == JOB_STATE_UPDATED) {
            "작업 상태 생성에 실패했습니다. jobId=${normalized.jobId}, result=$result"
        }
        return normalized
    }

    private fun compareAndSetJob(
        snapshot: JobSnapshot,
        updated: JobStatusUnifiedResponse,
        failedProblemId: String? = null
    ): JobStatusUnifiedResponse? {
        val normalized = normalize(updated)
        check(normalized.targetManifest == snapshot.job.targetManifest) {
            "작업 상태 갱신 중 대상 manifest 참조를 변경할 수 없습니다. jobId=${normalized.jobId}"
        }
        val result = redisTemplate.execute(
            COMPARE_AND_SET_JOB_SCRIPT,
            jobStateKeys(normalized.jobId),
            snapshot.rawJson,
            objectMapper.writeValueAsString(normalized),
            JOB_TTL_SECONDS.toString(),
            failedProblemId.orEmpty(),
            normalized.failCount.toString(),
            normalized.targetManifest?.schemaVersion?.toString().orEmpty(),
            if (isTerminal(normalized.status)) "1" else "0"
        )
        return when (result) {
            JOB_STATE_UPDATED -> normalized
            JOB_FAILURE_LEDGER_INVALID -> throw IllegalStateException(
                "실패 항목 원장 Redis 타입이 올바르지 않습니다. jobId=${normalized.jobId}"
            )
            JOB_STATE_MISSING,
            JOB_STATE_CONFLICT -> null
            else -> throw IllegalStateException(
                "알 수 없는 작업 상태 갱신 결과입니다. jobId=${normalized.jobId}, result=$result"
            )
        }
    }

    private fun compareAndSetWorkerJob(
        snapshot: JobSnapshot,
        updated: JobStatusUnifiedResponse,
        worker: JobWorkerContext,
        failedProblemId: String? = null
    ): JobStatusUnifiedResponse? {
        if (worker.attempt == null) {
            return compareAndSetJob(snapshot, updated, failedProblemId)
        }
        if (worker.ownershipLost.get() || snapshot.job.workerAttempt != worker.attempt) {
            return null
        }

        val normalized = normalize(updated)
        check(normalized.targetManifest == snapshot.job.targetManifest) {
            "작업 상태 갱신 중 대상 manifest 참조를 변경할 수 없습니다. jobId=${normalized.jobId}"
        }
        check(normalized.workerAttempt == worker.attempt) {
            "worker 상태 갱신 중 attempt를 변경할 수 없습니다. jobId=${normalized.jobId}"
        }
        val result = redisTemplate.execute(
            OWNED_COMPARE_AND_SET_JOB_SCRIPT,
            jobStateKeys(normalized.jobId),
            snapshot.rawJson,
            objectMapper.writeValueAsString(normalized),
            JOB_TTL_SECONDS.toString(),
            failedProblemId.orEmpty(),
            normalized.failCount.toString(),
            normalized.targetManifest?.schemaVersion?.toString().orEmpty(),
            requireNotNull(worker.leaseValue),
            workerLeaseProperties.leaseDuration.toMillis().toString(),
            if (isTerminal(normalized.status)) "1" else "0"
        )
        return when (result) {
            JOB_STATE_UPDATED -> normalized
            JOB_FAILURE_LEDGER_INVALID -> throw IllegalStateException(
                "실패 항목 원장 Redis 타입이 올바르지 않습니다. jobId=${normalized.jobId}"
            )
            JOB_LEASE_INVALID -> throw IllegalStateException(
                "작업 lease Redis 타입이 올바르지 않습니다. jobId=${normalized.jobId}"
            )
            JOB_LEASE_DURATION_INVALID -> throw IllegalStateException(
                "작업 lease 시간이 올바르지 않습니다. jobId=${normalized.jobId}"
            )
            JOB_LEASE_CONFLICT -> {
                worker.ownershipLost.set(true)
                null
            }
            JOB_STATE_MISSING,
            JOB_STATE_CONFLICT -> null
            else -> throw IllegalStateException(
                "알 수 없는 worker 상태 갱신 결과입니다. jobId=${normalized.jobId}, result=$result"
            )
        }
    }

    private fun loadAllJobs(): List<JobStatusUnifiedResponse> {
        val ids = redisTemplate.opsForZSet()
            .reverseRange(JOB_INDEX_KEY, 0, -1)
            .orEmpty()
            .toList()
        if (ids.isEmpty()) {
            return emptyList()
        }

        val statusKeys = ids.map(::jobKey)
        val statusValues = redisTemplate.opsForValue().multiGet(statusKeys).orEmpty()
        val jobs = mutableListOf<JobStatusUnifiedResponse>()
        val staleIds = mutableListOf<String>()

        ids.forEachIndexed { index, jobId ->
            val job = readBatchJob(jobId, statusValues.getOrNull(index))
            if (job == null) {
                staleIds.add(jobId)
            } else {
                jobs.add(job)
            }
        }

        if (staleIds.isNotEmpty()) {
            redisTemplate.opsForZSet().remove(JOB_INDEX_KEY, *staleIds.toTypedArray())
            redisTemplate.delete(
                staleIds.flatMap { jobId ->
                    listOf(failureKey(jobId), targetKey(jobId), leaseKey(jobId))
                }
            )
        }

        return jobs.sortedByDescending { it.queuedAt }
    }

    private fun readBatchJob(jobId: String, json: String?): JobStatusUnifiedResponse? {
        if (json != null) {
            return deserializeJob(jobId, json)
        }

        return when (redisTemplate.type(jobKey(jobId))) {
            DataType.NONE -> null
            DataType.STRING -> readJob(jobId)
            else -> throw BusinessException(
                ErrorCode.RESOURCE_STATE_CONFLICT,
                "작업 상태 Redis 타입이 올바르지 않습니다. jobId=$jobId"
            )
        }
    }

    private fun normalize(job: JobStatusUnifiedResponse): JobStatusUnifiedResponse {
        val safeTotal = job.totalCount.coerceAtLeast(0)
        val safeProcessed = job.processedCount.coerceIn(0, safeTotal)
        val safeSuccess = job.successCount.coerceIn(0, safeProcessed)
        val safeFail = job.failCount.coerceIn(0, safeProcessed)
        val progress = when {
            safeTotal == 0 && isTerminal(job.status) -> 100
            safeTotal == 0 -> 0
            else -> ((safeProcessed * 100) / safeTotal).coerceIn(0, 100)
        }

        val estimated = estimateRemaining(job.jobType, job.status, safeProcessed, safeTotal)

        return job.copy(
            totalCount = safeTotal,
            processedCount = safeProcessed,
            successCount = safeSuccess,
            failCount = safeFail,
            progressPercentage = progress,
            estimatedRemainingSeconds = estimated,
            queuePosition = null
        )
    }

    private fun estimateRemaining(type: ProblemJobType, status: JobStatus, processed: Int, total: Int): Long? {
        if (status != JobStatus.RUNNING || total <= 0 || processed <= 0 || processed > total) {
            return null
        }

        val avg = when (type) {
            ProblemJobType.COLLECT_METADATA -> METADATA_AVG_SECONDS
            ProblemJobType.COLLECT_DETAILS,
            ProblemJobType.REFRESH_DETAILS -> DETAILS_AVG_SECONDS
            ProblemJobType.UPDATE_LANGUAGE -> LANGUAGE_AVG_SECONDS
        }
        return (total - processed).toLong() * avg
    }

    private fun withQueuePositions(jobs: List<JobStatusUnifiedResponse>): List<JobStatusUnifiedResponse> {
        val pending = jobs
            .filter { it.status == JobStatus.PENDING }
            .sortedWith(compareBy<JobStatusUnifiedResponse> { it.queuedAt }.thenBy { it.jobId })

        val map = pending.mapIndexed { idx, job -> job.jobId to (idx + 1) }.toMap()
        return jobs.map { job ->
            job.copy(queuePosition = map[job.jobId])
        }
    }

    private fun withQueuePosition(job: JobStatusUnifiedResponse): JobStatusUnifiedResponse {
        if (job.status != JobStatus.PENDING) {
            return job.copy(queuePosition = null)
        }

        val pending = loadAllJobs()
            .filter { it.status == JobStatus.PENDING }
            .sortedWith(compareBy<JobStatusUnifiedResponse> { it.queuedAt }.thenBy { it.jobId })

        val index = pending.indexOfFirst { it.jobId == job.jobId }
        return job.copy(queuePosition = if (index >= 0) index + 1 else null)
    }

    private fun isAfterCheckpoint(problem: Problem, checkpointExclusive: Int?): Boolean {
        if (checkpointExclusive == null) {
            return true
        }
        val numericId = problem.id.value.toIntOrNull() ?: return true
        return numericId > checkpointExclusive
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

    private fun metadataTargetIds(
        explicitTargetIds: List<Int>,
        targetRange: JobRange?
    ): Iterable<Int> {
        return sequence {
            yieldAll(explicitTargetIds)
            if (targetRange != null) {
                val start = requireNotNull(targetRange.start)
                val end = requireNotNull(targetRange.end)
                yieldAll(start..end)
            }
        }.asIterable()
    }

    private fun validateAndCountTargetManifest(
        manifest: ProblemJobTargetManifest
    ): Int {
        require(manifest.version == ProblemJobTargetManifest.CURRENT_VERSION) {
            "지원하지 않는 작업 대상 manifest 버전입니다. version=${manifest.version}"
        }
        require(manifest.jobId.isNotBlank()) {
            "작업 대상 manifest jobId가 비어 있습니다."
        }
        require(manifest.explicitIds.none(String::isBlank)) {
            "작업 대상 manifest에 빈 ID가 있습니다. jobId=${manifest.jobId}"
        }
        require(manifest.explicitIds.distinct().size == manifest.explicitIds.size) {
            "작업 대상 manifest에 중복 ID가 있습니다. jobId=${manifest.jobId}"
        }

        val targetRange = manifest.range
        val rangeCount = if (targetRange == null) {
            0L
        } else {
            require(manifest.jobType == ProblemJobType.COLLECT_METADATA) {
                "메타데이터 작업 외에는 범위 manifest를 사용할 수 없습니다. jobId=${manifest.jobId}"
            }
            val start = requireNotNull(targetRange.start) {
                "작업 대상 manifest 범위의 start가 없습니다. jobId=${manifest.jobId}"
            }
            val end = requireNotNull(targetRange.end) {
                "작업 대상 manifest 범위의 end가 없습니다. jobId=${manifest.jobId}"
            }
            require(start > 0 && end > 0 && start <= end) {
                "작업 대상 manifest 범위가 올바르지 않습니다. jobId=${manifest.jobId}, start=$start, end=$end"
            }
            end.toLong() - start.toLong() + 1L
        }

        if (manifest.jobType == ProblemJobType.COLLECT_METADATA) {
            val explicitProblemIds = manifest.explicitIds.map { problemId ->
                requireNotNull(problemId.toIntOrNull()) {
                    "메타데이터 작업 대상 ID가 숫자가 아닙니다. jobId=${manifest.jobId}, problemId=$problemId"
                }
            }
            require(explicitProblemIds.all { it > 0 }) {
                "메타데이터 작업 대상 ID는 1 이상이어야 합니다. jobId=${manifest.jobId}"
            }
            if (targetRange != null) {
                val start = requireNotNull(targetRange.start)
                val end = requireNotNull(targetRange.end)
                require(explicitProblemIds.none { it in start..end }) {
                    "작업 대상 manifest의 명시 ID와 범위가 겹칩니다. jobId=${manifest.jobId}"
                }
            }
        }

        val targetCount = manifest.explicitIds.size.toLong() + rangeCount
        require(targetCount <= Int.MAX_VALUE.toLong()) {
            "작업 대상 수가 허용 범위를 벗어났습니다. jobId=${manifest.jobId}, count=$targetCount"
        }
        return targetCount.toInt()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    private fun orderProblems(problems: List<Problem>): List<Problem> {
        return problems
            .distinctBy { it.id.value }
            .sortedWith(
                compareBy<Problem> { it.id.value.toIntOrNull() == null }
                    .thenBy { it.id.value.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.id.value }
            )
    }

    private fun isTerminal(status: JobStatus): Boolean {
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED
    }

    private fun validateRange(start: Int?, end: Int?) {
        if ((start == null) != (end == null)) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "start와 end는 함께 제공되어야 합니다.")
        }
        if (start != null && end != null && start > end) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "start는 end보다 클 수 없습니다. start=$start, end=$end")
        }
        if (start != null && start <= 0) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "start는 1 이상이어야 합니다. start=$start")
        }
        if (end != null && end <= 0) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "end는 1 이상이어야 합니다. end=$end")
        }
    }

    private fun solvedAcProblemUrl(problemId: Int): String {
        return "https://www.acmicpc.net/problem/$problemId"
    }

    private fun filterProblemsByRange(
        problems: List<Problem>,
        start: Int?,
        end: Int?
    ): List<Problem> {
        if (start == null || end == null) {
            return problems
        }

        return problems.filter { problem ->
            val numericProblemId = problem.id.value.toIntOrNull() ?: return@filter false
            numericProblemId in start..end
        }
    }

    private fun jobKey(jobId: String): String {
        return "$JOB_KEY_PREFIX$jobId"
    }

    private fun failureKey(jobId: String): String {
        return "$JOB_FAILURE_KEY_PREFIX$jobId"
    }

    private fun targetKey(jobId: String): String {
        return "$JOB_TARGET_KEY_PREFIX$jobId"
    }

    private fun leaseKey(jobId: String): String {
        return "$JOB_LEASE_KEY_PREFIX$jobId"
    }

    private fun jobStateKeys(jobId: String): List<String> {
        return listOf(
            jobKey(jobId),
            failureKey(jobId),
            targetKey(jobId),
            leaseKey(jobId)
        )
    }

    private fun nowEpochSeconds(): Long {
        return Instant.now().epochSecond
    }

    private fun logJobAction(
        actor: String,
        action: AdminActionType,
        ipAddress: String,
        job: JobStatusUnifiedResponse,
        extra: String? = null
    ) {
        val rangeText = if (job.range?.start != null && job.range.end != null) {
            "${job.range.start}-${job.range.end}"
        } else {
            "ALL"
        }

        val detail = buildString {
            append("jobId=${job.jobId}")
            append(", type=${job.jobType}")
            append(", status=${job.status}")
            append(", range=$rangeText")
            append(", total=${job.totalCount}")
            if (!extra.isNullOrBlank()) {
                append(", ")
                append(extra)
            }
        }

        try {
            adminAuditService.logAction(
                adminId = actor,
                action = action,
                details = detail,
                ipAddress = ipAddress
            )
        } catch (e: RejectedExecutionException) {
            log.error(
                "admin audit submission rejected: actor=$actor, action=$action, error=${e.message}",
                e
            )
        }
    }
}
