package com.didimlog.application.problem.collector

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("문제 수집 worker lease 설정 테스트")
class ProblemCollectorWorkerLeasePropertiesTest {

    @Test
    fun `lease 시간은 heartbeat 간격의 세 배 이상이어야 한다`() {
        val exception = assertThrows<IllegalArgumentException> {
            ProblemCollectorWorkerLeaseProperties(
                leaseDuration = Duration.ofSeconds(10),
                heartbeatInterval = Duration.ofSeconds(4)
            )
        }

        assertThat(exception.message).contains("at least three times")
    }

    @Test
    fun `lease와 heartbeat 시간은 양수여야 한다`() {
        assertThrows<IllegalArgumentException> {
            ProblemCollectorWorkerLeaseProperties(
                leaseDuration = Duration.ZERO
            )
        }
        assertThrows<IllegalArgumentException> {
            ProblemCollectorWorkerLeaseProperties(
                heartbeatInterval = Duration.ZERO
            )
        }
    }

    @Test
    fun `Redis millisecond 단위보다 짧은 시간은 거절한다`() {
        assertThrows<IllegalArgumentException> {
            ProblemCollectorWorkerLeaseProperties(
                leaseDuration = Duration.ofNanos(3),
                heartbeatInterval = Duration.ofNanos(1)
            )
        }
    }
}
