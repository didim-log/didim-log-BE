package com.didimlog.application.problem.collector

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.ApplicationArguments

@DisplayName("문제 수집 재시작 복구 실행기 테스트")
class ProblemCollectorRecoveryRunnerTest {

    private val service: ProblemCollectorService = mockk()

    @Test
    fun `복구가 비활성화되면 작업 상태를 읽지 않는다`() {
        val properties = ProblemCollectorRecoveryProperties(
            failOrphanedJobsOnStartup = false
        )
        val recoveryState = ProblemCollectorRecoveryState(properties)
        val runner = ProblemCollectorRecoveryRunner(
            problemCollectorService = service,
            recoveryState = recoveryState,
            properties = properties
        )

        runner.run(mockk<ApplicationArguments>())

        assertThat(recoveryState.isReady()).isTrue()
        verify(exactly = 0) { service.failOrphanedJobsDuringStartup() }
    }

    @Test
    fun `복구가 성공하면 orphan 작업을 실패 처리하고 준비 완료로 전환한다`() {
        val properties = ProblemCollectorRecoveryProperties(
            failOrphanedJobsOnStartup = true
        )
        val recoveryState = ProblemCollectorRecoveryState(properties)
        val runner = ProblemCollectorRecoveryRunner(
            problemCollectorService = service,
            recoveryState = recoveryState,
            properties = properties
        )
        every { service.failOrphanedJobsDuringStartup() } returns 2

        runner.run(mockk<ApplicationArguments>())

        assertThat(recoveryState.isReady()).isTrue()
        verify(exactly = 1) { service.failOrphanedJobsDuringStartup() }
    }

    @Test
    fun `복구 중 예외가 발생하면 시작을 실패시키고 준비 전 상태를 유지한다`() {
        val properties = ProblemCollectorRecoveryProperties(
            failOrphanedJobsOnStartup = true
        )
        val recoveryState = ProblemCollectorRecoveryState(properties)
        val runner = ProblemCollectorRecoveryRunner(
            problemCollectorService = service,
            recoveryState = recoveryState,
            properties = properties
        )
        every { service.failOrphanedJobsDuringStartup() } throws
            IllegalStateException("redis unavailable")

        val exception = assertThrows<IllegalStateException> {
            runner.run(mockk<ApplicationArguments>())
        }

        assertThat(exception.message).isEqualTo("redis unavailable")
        assertThat(recoveryState.isReady()).isFalse()
        verify(exactly = 1) { service.failOrphanedJobsDuringStartup() }
    }
}
