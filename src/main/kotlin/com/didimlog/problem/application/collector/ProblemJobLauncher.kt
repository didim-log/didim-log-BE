package com.didimlog.problem.application.collector

import com.didimlog.admin.domain.AdminActionType
import com.didimlog.global.exception.ErrorCode
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.leaseKey
import com.didimlog.problem.application.collector.ProblemJobStatusCalculator.estimateRemaining
import com.didimlog.problem.application.collector.ProblemJobTargets.metadataTargetIds
import com.didimlog.problem.application.collector.ProblemJobTargets.orderProblems
import com.didimlog.problem.application.collector.ProblemJobTargets.sha256
import com.didimlog.problem.application.collector.ProblemJobTargets.validateAndCountTargetManifest
import com.didimlog.problem.domain.Problem
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

/**
 * 수집 작업을 만들어 실행기에 제출한다.
 *
 * - 새 작업: 대상 manifest를 고정해 PENDING 상태로 저장하고, 생성 감사 로그를 남긴 뒤 제출
 * - 처리할 대상이 없는 재시도: 바로 완료되는 빈 작업
 * - 재시작 복구: lease가 비어 있는 PENDING·RUNNING 작업을 이어서 실행하도록 다시 제출
 *
 * 작업 생성은 재시작 복구가 끝나 생성 gate가 열린 뒤에만 가능하다.
 */
internal class ProblemJobLauncher(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val recoveryState: ProblemCollectorRecoveryState,
    private val stateStore: ProblemJobStateStore,
    private val auditLogger: ProblemJobAuditLogger,
    private val executionRunner: ProblemJobExecutionRunner,
    private val targetCollector: ProblemJobTargetCollector
) {

    // 분리 전과 같은 로그 카테고리를 유지한다.
    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)

    fun startCollectMetadataWithManifest(
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
        executionRunner.executeAsync(job.jobId) {
            targetCollector.collectMetadataAsyncInternal(job.jobId, targetIds)
        }
        return job
    }

    fun startCollectDetailsWithTargets(
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
        executionRunner.executeAsync(job.jobId) {
            targetCollector.collectDetailsBatchAsyncInternal(job.jobId, orderedTargets)
        }
        return job
    }

    fun startRefreshDetailsWithTargets(
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
        executionRunner.executeAsync(job.jobId) {
            targetCollector.refreshDetailsBatchAsyncInternal(job.jobId, orderedTargets)
        }
        return job
    }

    fun startUpdateLanguageWithTargets(
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
        executionRunner.executeAsync(job.jobId) {
            targetCollector.updateLanguageBatchAsyncInternal(job.jobId, orderedTargets)
        }
        return job
    }

    fun createNoopCompletedJob(
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

    fun submitRecoverableJob(job: JobStatusUnifiedResponse): Boolean {
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
            targetCollector.resumeFromManifest(job.jobId)
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
}
