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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
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
    @param:Qualifier("taskExecutor")
    private val taskExecutor: Executor? = null
) {

    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)

    companion object {
        private const val JOB_KEY_PREFIX = "problem:job:status:"
        private const val JOB_FAILURE_KEY_PREFIX = "problem:job:failures:"
        private const val JOB_INDEX_KEY = "problem:job:index"
        private const val JOB_TTL_SECONDS = 86400L
        private const val MAX_JOB_STATE_RETRIES = 3
        private const val JOB_FAILURE_LEDGER_INVALID = -2L
        private const val JOB_STATE_MISSING = -1L
        private const val JOB_STATE_CONFLICT = 0L
        private const val JOB_STATE_UPDATED = 1L
        private const val METADATA_AVG_SECONDS = 1L
        private const val DETAILS_AVG_SECONDS = 3L
        private const val LANGUAGE_AVG_SECONDS = 1L
        private val CREATE_JOB_SCRIPT = DefaultRedisScript(
            """
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end

            local indexType = redis.call('TYPE', KEYS[2])
            if type(indexType) == 'table' then
                indexType = indexType['ok']
            end
            if indexType ~= 'none' and indexType ~= 'zset' then
                return -1
            end

            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
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
            return 1
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

        val job = createJob(
            type = ProblemJobType.COLLECT_METADATA,
            totalCount = end - start + 1,
            range = JobRange(start, end),
            createdBy = createdBy
        )
        logJobAction(createdBy, AdminActionType.PROBLEM_JOB_CREATE, ipAddress, job)
        executeAsync(job.jobId) { collectMetadataAsyncInternal(job.jobId, start..end) }
        return job.jobId
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
        val interruptedNonMetadataJob = original.jobType != ProblemJobType.COLLECT_METADATA &&
            original.status != JobStatus.COMPLETED
        val usesLegacyRetryFallback = !failureLedger.exists &&
            (original.failCount > 0 || interruptedNonMetadataJob)
        if (!usesLegacyRetryFallback && failedProblemIds.size != original.failCount) {
            throw BusinessException(
                ErrorCode.RESOURCE_STATE_CONFLICT,
                "실패 항목 기록이 작업 상태와 일치하지 않습니다. jobId=$jobId, failCount=${original.failCount}, recorded=${failedProblemIds.size}"
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
                    usesLegacyRetryFallback -> startCollectMetadataWithTargets(
                        targetIds = start..end,
                        totalCount = end - start + 1,
                        range = JobRange(start, end),
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
                            startCollectMetadataWithTargets(
                                targetIds = failedIds,
                                totalCount = failedIds.size,
                                range = JobRange(failedIds.first(), failedIds.last()),
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
                        val tailCount = retryStart?.let { end - it + 1 } ?: 0
                        val totalCount = failedIds.size + tailCount
                        if (totalCount == 0) {
                            createNoopCompletedJob(
                                ProblemJobType.COLLECT_METADATA,
                                JobRange(start, end),
                                requestedBy
                            ).jobId
                        } else {
                            val targetIds = sequence {
                                yieldAll(failedIds)
                                if (retryStart != null) {
                                    yieldAll(retryStart..end)
                                }
                            }.asIterable()
                            startCollectMetadataWithTargets(
                                targetIds = targetIds,
                                totalCount = totalCount,
                                range = JobRange(
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
            totalCount = orderedTargets.size,
            range = null,
            createdBy = createdBy
        )
        logJobAction(createdBy, AdminActionType.PROBLEM_JOB_CREATE, ipAddress, job)
        executeAsync(job.jobId) { collectDetailsBatchAsyncInternal(job.jobId, orderedTargets) }
        return job
    }

    private fun startCollectMetadataWithTargets(
        targetIds: Iterable<Int>,
        totalCount: Int,
        range: JobRange,
        createdBy: String,
        ipAddress: String
    ): JobStatusUnifiedResponse {
        val job = createJob(
            type = ProblemJobType.COLLECT_METADATA,
            totalCount = totalCount,
            range = range,
            createdBy = createdBy
        )
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
            totalCount = orderedTargets.size,
            range = range,
            createdBy = createdBy
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
            totalCount = orderedTargets.size,
            range = null,
            createdBy = createdBy
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
        val created = createJob(type, 0, range, createdBy)
        if (markRunning(created.jobId)) {
            markCompleted(created.jobId)
        }
        return readJob(created.jobId)
            ?: throw IllegalStateException("생성한 작업 상태를 찾을 수 없습니다. jobId=${created.jobId}")
    }

    private fun collectMetadataAsyncInternal(jobId: String, problemIds: Iterable<Int>) {
        runJobLoop(
            jobId = jobId,
            defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code
        ) {
            var processed = 0
            var success = 0
            var fail = 0

            for (problemId in problemIds) {
                if (isCancelled(jobId)) {
                    return@runJobLoop
                }

                var failedProblemId: String? = null
                try {
                    upsertProblemMetadata(problemId)
                    success++
                } catch (e: Exception) {
                    fail++
                    failedProblemId = problemId.toString()
                    log.warn("metadata job item failed: jobId=$jobId, problemId=$problemId, error=${e.message}")
                }

                processed++
                updateProgress(jobId, processed, success, fail, problemId.toString(), failedProblemId)
                pacer.pauseMetadata()
            }
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
        runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) {
            var processed = 0
            var success = 0
            var fail = 0

            for (problem in targetProblems) {
                if (isCancelled(jobId)) {
                    return@runJobLoop
                }

                var failedProblemId: String? = null
                try {
                    val details = bojCrawler.crawlProblemDetails(problem.id.value)
                    if (details == null) {
                        fail++
                        failedProblemId = problem.id.value
                    } else {
                        if (updateProblemDetails(problem.id.value, details) == null) {
                            fail++
                            failedProblemId = problem.id.value
                        } else {
                            success++
                        }
                    }
                } catch (e: Exception) {
                    fail++
                    failedProblemId = problem.id.value
                    log.error("details job item failed: jobId=$jobId, problemId=${problem.id.value}, error=${e.message}", e)
                }

                processed++
                updateProgress(jobId, processed, success, fail, problem.id.value, failedProblemId)
                pacer.pauseDetails()
            }
        }
    }

    private fun refreshDetailsBatchAsyncInternal(jobId: String, targetProblems: List<Problem>) {
        runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) {
            var processed = 0
            var success = 0
            var fail = 0

            for (problem in targetProblems) {
                if (isCancelled(jobId)) {
                    return@runJobLoop
                }

                var failedProblemId: String? = null
                try {
                    val details = bojCrawler.crawlProblemDetails(problem.id.value)
                    if (details == null) {
                        fail++
                        failedProblemId = problem.id.value
                    } else {
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
                        if (
                            updateProblemDetails(
                                problemId = problem.id.value,
                                details = details,
                                language = detectedLanguage
                            ) == null
                        ) {
                            fail++
                            failedProblemId = problem.id.value
                        } else {
                            success++
                        }
                    }
                } catch (e: Exception) {
                    fail++
                    failedProblemId = problem.id.value
                    log.error("refresh job item failed: jobId=$jobId, problemId=${problem.id.value}, error=${e.message}", e)
                }

                processed++
                updateProgress(jobId, processed, success, fail, problem.id.value, failedProblemId)
                pacer.pauseDetails()
            }
        }
    }

    private fun updateLanguageBatchAsyncInternal(jobId: String, targetProblems: List<Problem>) {
        runJobLoop(jobId = jobId, defaultFailureCode = ErrorCode.WORKER_UNAVAILABLE.code) {
            var processed = 0
            var success = 0
            var fail = 0

            for (problem in targetProblems) {
                if (isCancelled(jobId)) {
                    return@runJobLoop
                }

                var failedProblemId: String? = null
                try {
                    val detectedLanguage = ProblemLanguageDetector.detect(problem)
                    if (detectedLanguage == null || problem.language.equals(detectedLanguage, ignoreCase = true)) {
                        success++
                    } else if (problemRepository.updateLanguage(problem.id.value, detectedLanguage)) {
                        success++
                    } else {
                        fail++
                        failedProblemId = problem.id.value
                    }
                } catch (e: Exception) {
                    fail++
                    failedProblemId = problem.id.value
                    log.error("language job item failed: jobId=$jobId, problemId=${problem.id.value}, error=${e.message}", e)
                }

                processed++
                updateProgress(jobId, processed, success, fail, problem.id.value, failedProblemId)
            }
        }
    }

    private fun executeAsync(jobId: String, block: () -> Unit) {
        val executor = taskExecutor
        if (executor == null) {
            block()
            return
        }

        try {
            executor.execute(block)
        } catch (e: RejectedExecutionException) {
            log.error("job submission rejected: jobId=$jobId, error=${e.message}", e)
            markFailed(jobId, ErrorCode.WORKER_UNAVAILABLE.code, "작업 실행을 제출할 수 없습니다.")
        }
    }

    private inline fun runJobLoop(
        jobId: String,
        defaultFailureCode: String,
        block: () -> Unit
    ) {
        val marked = markRunning(jobId)
        if (!marked) {
            return
        }

        try {
            block()
            if (!isCancelled(jobId)) {
                markCompleted(jobId)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            markFailed(jobId, ErrorCode.WORKER_UNAVAILABLE.code, "작업이 인터럽트되었습니다.")
        } catch (e: Exception) {
            log.error("job failed unexpectedly: jobId=$jobId, error=${e.message}", e)
            markFailed(jobId, defaultFailureCode, e.message ?: "unexpected error")
        }
    }

    private fun createJob(
        type: ProblemJobType,
        totalCount: Int,
        range: JobRange?,
        createdBy: String
    ): JobStatusUnifiedResponse {
        val now = nowEpochSeconds()
        val created = JobStatusUnifiedResponse(
            jobId = UUID.randomUUID().toString(),
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
            createdBy = createdBy
        )

        return persistNewJob(created)
    }

    private fun getTypedJob(jobId: String, type: ProblemJobType): JobStatusUnifiedResponse? {
        val job = getJob(jobId) ?: return null
        return if (job.jobType == type) job else null
    }

    private fun markRunning(jobId: String): Boolean {
        val snapshot = readJobSnapshot(jobId) ?: return false
        if (snapshot.job.status != JobStatus.PENDING) {
            return false
        }

        val now = nowEpochSeconds()
        val running = snapshot.job.copy(
            status = JobStatus.RUNNING,
            startedAt = now,
            lastHeartbeatAt = now,
            errorCode = null,
            errorMessage = null
        )
        return compareAndSetJob(snapshot, running) != null
    }

    private fun markCompleted(jobId: String) {
        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = readJobSnapshot(jobId) ?: return
            if (snapshot.job.status != JobStatus.RUNNING) {
                return
            }

            val now = nowEpochSeconds()
            val completed = snapshot.job.copy(
                status = JobStatus.COMPLETED,
                completedAt = now,
                lastHeartbeatAt = now
            )
            if (compareAndSetJob(snapshot, completed) != null) {
                return
            }
        }
        log.warn("job completion CAS retries exhausted: jobId=$jobId")
    }

    private fun markFailed(jobId: String, errorCode: String, message: String) {
        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = readJobSnapshot(jobId) ?: return
            if (snapshot.job.status != JobStatus.PENDING && snapshot.job.status != JobStatus.RUNNING) {
                return
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
                return
            }
        }
        log.warn("job failure CAS retries exhausted: jobId=$jobId")
    }

    private fun updateProgress(
        jobId: String,
        processedCount: Int,
        successCount: Int,
        failCount: Int,
        checkpointId: String?,
        failedProblemId: String?
    ) {
        val snapshot = readJobSnapshot(jobId) ?: return
        if (snapshot.job.status != JobStatus.RUNNING) {
            return
        }

        val now = nowEpochSeconds()
        val updated = snapshot.job.copy(
            processedCount = processedCount,
            successCount = successCount,
            failCount = failCount,
            lastCheckpointId = checkpointId ?: snapshot.job.lastCheckpointId,
            lastHeartbeatAt = now
        )
        compareAndSetJob(snapshot, updated, failedProblemId)
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

    private fun persistNewJob(job: JobStatusUnifiedResponse): JobStatusUnifiedResponse {
        val normalized = normalize(job)
        val key = jobKey(normalized.jobId)
        val json = objectMapper.writeValueAsString(normalized)
        val result = redisTemplate.execute(
            CREATE_JOB_SCRIPT,
            listOf(key, JOB_INDEX_KEY),
            json,
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
        val result = redisTemplate.execute(
            COMPARE_AND_SET_JOB_SCRIPT,
            listOf(jobKey(normalized.jobId), failureKey(normalized.jobId)),
            snapshot.rawJson,
            objectMapper.writeValueAsString(normalized),
            JOB_TTL_SECONDS.toString(),
            failedProblemId.orEmpty(),
            normalized.failCount.toString()
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
            redisTemplate.delete(staleIds.map(::failureKey))
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

    private fun isCancelled(jobId: String): Boolean {
        val current = readJob(jobId) ?: return true
        return current.status == JobStatus.CANCELLED
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

        adminAuditService.logAction(
            adminId = actor,
            action = action,
            details = detail,
            ipAddress = ipAddress
        )
    }
}
