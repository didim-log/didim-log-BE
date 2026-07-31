package com.didimlog.application.problem.collector

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "app.problem-collector.worker-lease",
    name = ["enabled"],
    havingValue = "true"
)
class ProblemCollectorWorkerTakeoverScanner(
    private val problemCollectorService: ProblemCollectorService,
    private val workerLeaseProperties: ProblemCollectorWorkerLeaseProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${app.problem-collector.worker-lease.scan-interval:10s}"
    )
    fun submitRecoverableJobs() {
        if (!workerLeaseProperties.enabled) {
            return
        }

        val submittedCount = problemCollectorService.submitRecoverableJobs()
        if (submittedCount > 0) {
            log.info("복구 가능한 문제 수집 작업을 실행기에 제출했습니다: count={}", submittedCount)
        }
    }
}
