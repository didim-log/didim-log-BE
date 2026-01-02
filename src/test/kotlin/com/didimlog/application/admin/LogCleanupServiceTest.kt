package com.didimlog.application.admin

import com.didimlog.domain.repository.LogRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LogCleanupService 테스트")
class LogCleanupServiceTest {

    private val logRepository: LogRepository = mockk()
    private val logCleanupService = LogCleanupService(logRepository)

    @Test
    @DisplayName("cleanupLogs는 지정된 일수 이상 된 로그를 삭제하고 개수를 반환한다")
    fun `로그 정리 성공`() {
        // given
        val olderThanDays = 30
        val deletedCount = 10L

        every { logRepository.countByCreatedAtBefore(any()) } returns deletedCount
        every { logRepository.deleteByCreatedAtBefore(any()) } just runs

        // when
        val result = logCleanupService.cleanupLogs(olderThanDays)

        // then
        assertThat(result).isEqualTo(deletedCount)
        verify(exactly = 1) { logRepository.countByCreatedAtBefore(any()) }
        verify(exactly = 1) { logRepository.deleteByCreatedAtBefore(any()) }
    }

    @Test
    @DisplayName("cleanupLogs는 삭제할 로그가 없으면 0을 반환한다")
    fun `삭제할 로그 없음`() {
        // given
        val olderThanDays = 7

        every { logRepository.countByCreatedAtBefore(any()) } returns 0L
        every { logRepository.deleteByCreatedAtBefore(any()) } just runs

        // when
        val result = logCleanupService.cleanupLogs(olderThanDays)

        // then
        assertThat(result).isEqualTo(0L)
        verify(exactly = 1) { logRepository.countByCreatedAtBefore(any()) }
        verify(exactly = 1) { logRepository.deleteByCreatedAtBefore(any()) }
    }
}

