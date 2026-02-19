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
        every { logRepository.countByCreatedAtBeforeAndAiReviewStatus(any(), any()) } returnsMany listOf(5L, 2L, 1L, 1L)
        every { logRepository.countByCreatedAtBeforeAndAiReviewStatusIsNull(any()) } returns 1L
        every { logRepository.deleteByCreatedAtBefore(any()) } just runs

        // when
        val result = logCleanupService.cleanupLogs(LogCleanupMode.OLDER_THAN_DAYS, olderThanDays)

        // then
        assertThat(result.deletedCount).isEqualTo(deletedCount)
        assertThat(result.mode).isEqualTo(LogCleanupMode.OLDER_THAN_DAYS)
        assertThat(result.referenceDays).isEqualTo(olderThanDays)
        verify(exactly = 1) { logRepository.countByCreatedAtBefore(any()) }
        verify(exactly = 1) { logRepository.deleteByCreatedAtBefore(any()) }
    }

    @Test
    @DisplayName("cleanupLogs는 삭제할 로그가 없으면 0을 반환한다")
    fun `삭제할 로그 없음`() {
        // given
        val olderThanDays = 7

        every { logRepository.countByCreatedAtBefore(any()) } returns 0L
        every { logRepository.countByCreatedAtBeforeAndAiReviewStatus(any(), any()) } returnsMany listOf(0L, 0L, 0L, 0L)
        every { logRepository.countByCreatedAtBeforeAndAiReviewStatusIsNull(any()) } returns 0L
        every { logRepository.deleteByCreatedAtBefore(any()) } just runs

        // when
        val result = logCleanupService.cleanupLogs(LogCleanupMode.OLDER_THAN_DAYS, olderThanDays)

        // then
        assertThat(result.deletedCount).isEqualTo(0L)
        verify(exactly = 1) { logRepository.countByCreatedAtBefore(any()) }
        verify(exactly = 1) { logRepository.deleteByCreatedAtBefore(any()) }
    }

    @Test
    @DisplayName("previewCleanup은 삭제 예정 건수와 상태별 분포를 반환한다")
    fun `로그 정리 미리보기 성공`() {
        every { logRepository.countByCreatedAtBefore(any()) } returns 12L
        every { logRepository.countByCreatedAtBeforeAndAiReviewStatus(any(), any()) } returnsMany listOf(7L, 3L, 1L, 0L)
        every { logRepository.countByCreatedAtBeforeAndAiReviewStatusIsNull(any()) } returns 1L

        val preview = logCleanupService.previewCleanup(LogCleanupMode.KEEP_RECENT_DAYS, 3)

        assertThat(preview.mode).isEqualTo(LogCleanupMode.KEEP_RECENT_DAYS)
        assertThat(preview.referenceDays).isEqualTo(3)
        assertThat(preview.deletableCount).isEqualTo(12L)
        assertThat(preview.statusBreakdown["COMPLETED"]).isEqualTo(7L)
        assertThat(preview.statusBreakdown["FAILED"]).isEqualTo(3L)
        assertThat(preview.statusBreakdown["UNKNOWN"]).isEqualTo(1L)
    }
}
