package com.didimlog.problem.application.collector

import com.didimlog.admin.application.AdminAuditService
import com.didimlog.admin.domain.AdminActionType
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.JOB_INDEX_KEY
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.MAX_JOB_STATE_RETRIES
import com.didimlog.problem.application.collector.ProblemJobStatusCalculator.isTerminal
import com.didimlog.problem.application.collector.ProblemJobTargets.filterProblemsByRange
import com.didimlog.problem.application.collector.ProblemJobTargets.validateRange
import com.didimlog.problem.domain.repository.ProblemRepository
import com.didimlog.problem.infra.crawler.BojCrawler
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
 *
 * controller와 복구 runner가 쓰는 진입점이다. 실제 처리는 같은 패키지의 협력
 * 클래스가 맡고, 서비스는 생성자 인자로 협력 클래스를 직접 만들어 연결한다.
 *
 * - [ProblemJobStateStore]: Redis 작업 상태 읽기와 Lua CAS
 * - [ProblemJobManifestValidator]: 대상 manifest와 이어서 실행할 상태 검증
 * - [ProblemJobWorkerLeaseCoordinator]: worker 선점, heartbeat, 진행·완료·실패 기록
 * - [ProblemJobExecutionRunner]: 실행기 제출과 한 번의 실행 루프
 * - [ProblemJobTargetCollector]: 작업 유형별 대상 처리
 * - [ProblemJobLauncher]: 작업 생성과 제출, 재시작 복구 제출
 * - [ProblemJobRetryPlanner]: 재시도 대상 선택
 * - [ProblemJobQueries]: 단건·목록·메트릭·감사 조회
 *
 * 트랜잭션이 필요한 동기 수집(collectMetadata, collectDetailsBatch)은 Spring
 * 프록시를 거치도록 이 클래스에 둔다.
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
    private val retryPlanner = ProblemJobRetryPlanner(
        problemRepository = problemRepository,
        stateStore = stateStore,
        manifestValidator = manifestValidator,
        auditLogger = auditLogger,
        jobQueries = jobQueries,
        jobLauncher = jobLauncher
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
        return retryPlanner.retryJob(jobId, requestedBy, ipAddress)
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
}
