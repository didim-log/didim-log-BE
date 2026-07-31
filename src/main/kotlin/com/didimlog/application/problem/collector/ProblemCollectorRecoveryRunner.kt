package com.didimlog.application.problem.collector

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProblemCollectorRecoveryRunner(
    private val properties: ProblemCollectorRecoveryProperties,
    private val recoveryState: ProblemCollectorRecoveryState,
    private val problemCollectorService: ProblemCollectorService
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!properties.failOrphanedJobsOnStartup) {
            return
        }

        val failedJobCount = problemCollectorService.failOrphanedJobsDuringStartup()
        recoveryState.markReady()
        log.info("서버 재시작 후 실행 주체를 잃은 문제 수집 작업을 실패 처리했습니다: count={}", failedJobCount)
    }
}
