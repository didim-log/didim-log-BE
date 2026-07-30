package com.didimlog.application.problem.collector

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.stereotype.Component

@Component
class ProblemCollectorRecoveryState(
    properties: ProblemCollectorRecoveryProperties
) {
    private val jobCreationReady = AtomicBoolean(!properties.failOrphanedJobsOnStartup)

    fun requireJobCreationReady() {
        if (!jobCreationReady.get()) {
            throw BusinessException(
                ErrorCode.WORKER_UNAVAILABLE,
                "문제 수집 작업 복구가 진행 중입니다. 잠시 후 다시 시도해주세요."
            )
        }
    }

    internal fun markReady() {
        jobCreationReady.set(true)
    }

    fun isReady(): Boolean = jobCreationReady.get()
}
