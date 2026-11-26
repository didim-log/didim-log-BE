package com.didimlog.problem.application.collector

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture

/**
 * 수집 작업을 실행기에 제출하고, 선점부터 종료 기록까지 한 번의 실행을 감싼다.
 *
 * 같은 작업이 이 인스턴스 안에서 두 번 제출되지 않도록 제출된 jobId를 기억한다.
 * 작업 생성, 재시도, 재시작 복구가 모두 같은 실행기 인스턴스를 거쳐야 이 중복
 * 방지가 유지되므로 서비스가 하나만 만들어 공유한다.
 * taskExecutor가 없으면 호출한 스레드에서 바로 실행한다.
 */
internal class ProblemJobExecutionRunner(
    private val taskExecutor: Executor?,
    private val workerLeaseProperties: ProblemCollectorWorkerLeaseProperties,
    private val stateStore: ProblemJobStateStore,
    private val workerLeaseCoordinator: ProblemJobWorkerLeaseCoordinator
) {

    // 분리 전과 같은 로그 카테고리를 유지한다.
    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)

    private val scheduledJobIds = ConcurrentHashMap.newKeySet<String>()

    fun executeAsync(jobId: String, block: () -> Unit): Boolean {
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
                stateStore.markFailed(jobId, ErrorCode.WORKER_UNAVAILABLE.code, "작업 실행을 제출할 수 없습니다.")
            }
            return false
        }
    }

    fun runJobLoop(
        jobId: String,
        defaultFailureCode: String,
        allowTakeover: Boolean = false,
        block: (JobWorkerContext) -> Unit
    ) {
        val worker = workerLeaseCoordinator.claimWorker(jobId, allowTakeover) ?: return
        var heartbeat: ScheduledFuture<*>? = null

        try {
            heartbeat = workerLeaseCoordinator.startHeartbeat(jobId, worker)
            block(worker)
            if (workerLeaseCoordinator.isWorkerRunning(jobId, worker)) {
                workerLeaseCoordinator.markWorkerCompleted(jobId, worker)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            workerLeaseCoordinator.markWorkerFailed(
                jobId,
                worker,
                ErrorCode.WORKER_UNAVAILABLE.code,
                "작업이 인터럽트되었습니다."
            )
        } catch (e: BusinessException) {
            log.warn("job state validation failed: jobId=$jobId, error=${e.message}")
            workerLeaseCoordinator.markWorkerFailed(
                jobId,
                worker,
                e.errorCode.code,
                e.message ?: e.errorCode.message
            )
        } catch (e: Exception) {
            log.error("job failed unexpectedly: jobId=$jobId, error=${e.message}", e)
            workerLeaseCoordinator.markWorkerFailed(jobId, worker, defaultFailureCode, e.message ?: "unexpected error")
        } finally {
            heartbeat?.cancel(false)
            workerLeaseCoordinator.releaseWorkerLease(jobId, worker)
        }
    }
}
