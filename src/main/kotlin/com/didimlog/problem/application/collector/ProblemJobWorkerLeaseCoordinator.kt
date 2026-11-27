package com.didimlog.problem.application.collector

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.JOB_TTL_SECONDS
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.jobStateKeys
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.leaseKey
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.CLAIM_JOB_SCRIPT
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_FAILURE_LEDGER_INVALID
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_LEASE_CONFLICT
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_LEASE_DURATION_INVALID
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_LEASE_INVALID
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_STATE_CONFLICT
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_STATE_MISSING
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_STATE_UPDATED
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.MAX_JOB_STATE_RETRIES
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.RELEASE_JOB_LEASE_SCRIPT
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.RENEW_JOB_LEASE_SCRIPT
import com.didimlog.problem.application.collector.ProblemJobStatusCalculator.estimateRemaining
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 작업을 실행하는 worker의 소유권 수명 주기를 관리한다.
 *
 * - 작업 선점(claimWorker): lease가 켜져 있으면 CLAIM 스크립트로 attempt와 lease를 함께 기록
 * - 실행 중: heartbeat로 lease 연장, 진행 상황 갱신, 소유권 확인
 * - 종료: 완료·실패 기록과 lease 해제
 * - 재시작 후 이어서 실행할 때 소유한 상태와 manifest 확인
 *
 * 소유권을 잃으면 [JobWorkerContext.ownershipLost]를 켜고 더 이상 상태를 쓰지 않는다.
 */
internal class ProblemJobWorkerLeaseCoordinator(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val stateStore: ProblemJobStateStore,
    private val manifestValidator: ProblemJobManifestValidator,
    private val workerLeaseProperties: ProblemCollectorWorkerLeaseProperties,
    private val workerIdentity: ProblemCollectorWorkerIdentity,
    private val heartbeatExecutor: ScheduledExecutorService?
) {

    // 분리 전과 같은 로그 카테고리를 유지한다.
    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)

    fun claimWorker(
        jobId: String,
        allowTakeover: Boolean = false
    ): JobWorkerContext? {
        val snapshot = stateStore.readJobSnapshot(jobId) ?: return null
        val canClaimPending = snapshot.job.status == JobStatus.PENDING
        val canTakeOverRunning = workerLeaseProperties.enabled &&
            allowTakeover &&
            snapshot.job.status == JobStatus.RUNNING
        if (!canClaimPending && !canTakeOverRunning) {
            return null
        }

        val now = stateStore.nowEpochSeconds()
        if (!workerLeaseProperties.enabled) {
            val running = snapshot.job.copy(
                status = JobStatus.RUNNING,
                startedAt = now,
                lastHeartbeatAt = now,
                errorCode = null,
                errorMessage = null
            )
            return if (stateStore.compareAndSetJob(snapshot, running) != null) {
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

    fun startHeartbeat(
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
            val snapshot = stateStore.readJobSnapshot(jobId) ?: run {
                worker.ownershipLost.set(true)
                return
            }
            if (!isWorkerSnapshot(snapshot.job, worker)) {
                worker.ownershipLost.set(true)
                return
            }

            val updated = snapshot.job.copy(lastHeartbeatAt = stateStore.nowEpochSeconds())
            if (stateStore.compareAndSetWorkerJob(snapshot, updated, worker) != null) {
                return
            }
            if (worker.ownershipLost.get()) {
                return
            }
        }
        log.warn("job heartbeat state CAS retries exhausted: jobId=$jobId")
    }

    fun isWorkerRunning(jobId: String, worker: JobWorkerContext): Boolean {
        if (worker.ownershipLost.get()) {
            return false
        }
        val current = stateStore.readJob(jobId) ?: return false
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

    fun updateProgress(
        jobId: String,
        worker: JobWorkerContext,
        processedCount: Int,
        successCount: Int,
        failCount: Int,
        checkpointId: String?,
        failedProblemId: String?
    ): Boolean {
        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = stateStore.readJobSnapshot(jobId) ?: run {
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

            val now = stateStore.nowEpochSeconds()
            val updated = snapshot.job.copy(
                processedCount = processedCount,
                successCount = successCount,
                failCount = failCount,
                lastCheckpointId = checkpointId ?: snapshot.job.lastCheckpointId,
                lastHeartbeatAt = now
            )
            if (stateStore.compareAndSetWorkerJob(snapshot, updated, worker, failedProblemId) != null) {
                return true
            }
        }
        log.warn("job progress CAS retries exhausted: jobId=$jobId")
        worker.ownershipLost.set(true)
        return false
    }

    fun markWorkerCompleted(jobId: String, worker: JobWorkerContext) {
        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = stateStore.readJobSnapshot(jobId) ?: return
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

            val now = stateStore.nowEpochSeconds()
            val completed = snapshot.job.copy(
                status = JobStatus.COMPLETED,
                completedAt = now,
                lastHeartbeatAt = now
            )
            if (stateStore.compareAndSetWorkerJob(snapshot, completed, worker) != null) {
                return
            }
        }
        log.warn("job completion CAS retries exhausted: jobId=$jobId")
    }

    fun markWorkerFailed(
        jobId: String,
        worker: JobWorkerContext,
        errorCode: String,
        message: String
    ): Boolean {
        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = stateStore.readJobSnapshot(jobId) ?: return false
            if (!isWorkerSnapshot(snapshot.job, worker)) {
                return false
            }

            val now = stateStore.nowEpochSeconds()
            val failed = snapshot.job.copy(
                status = JobStatus.FAILED,
                completedAt = now,
                lastHeartbeatAt = now,
                errorCode = errorCode,
                errorMessage = message
            )
            if (stateStore.compareAndSetWorkerJob(snapshot, failed, worker) != null) {
                return true
            }
        }
        log.warn("owned job failure CAS retries exhausted: jobId=$jobId")
        return false
    }

    fun releaseWorkerLease(jobId: String, worker: JobWorkerContext) {
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

    fun readResumeState(
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
        val current = stateStore.readJob(jobId) ?: return null
        if (!isWorkerSnapshot(current, worker)) {
            worker.ownershipLost.set(true)
            return null
        }
        return current
    }

    fun validateOwnedResumeState(
        job: JobStatusUnifiedResponse,
        worker: JobWorkerContext
    ): ProblemJobTargetManifest? {
        return try {
            manifestValidator.validateResumeState(job)
        } catch (e: BusinessException) {
            throw e
        } catch (e: RuntimeException) {
            worker.ownershipLost.set(true)
            log.error("recoverable job validation read failed: jobId={}, error={}", job.jobId, e.message)
            null
        }
    }
}
