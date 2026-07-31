package com.didimlog.application.problem.collector

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("문제 수집 작업 인계 scanner 테스트")
class ProblemCollectorWorkerTakeoverScannerTest {

    private val service: ProblemCollectorService = mockk()

    @Test
    fun `worker lease가 꺼져 있으면 작업을 조회하지 않는다`() {
        val scanner = ProblemCollectorWorkerTakeoverScanner(
            problemCollectorService = service,
            workerLeaseProperties = ProblemCollectorWorkerLeaseProperties(enabled = false)
        )

        scanner.submitRecoverableJobs()

        verify(exactly = 0) { service.submitRecoverableJobs() }
    }

    @Test
    fun `worker lease가 켜져 있으면 복구 가능한 작업을 제출한다`() {
        val scanner = ProblemCollectorWorkerTakeoverScanner(
            problemCollectorService = service,
            workerLeaseProperties = ProblemCollectorWorkerLeaseProperties(enabled = true)
        )
        every { service.submitRecoverableJobs() } returns 2

        scanner.submitRecoverableJobs()

        verify(exactly = 1) { service.submitRecoverableJobs() }
    }
}
