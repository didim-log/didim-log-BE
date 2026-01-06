package com.didimlog.application.retrospective

import com.didimlog.domain.repository.RetrospectiveRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

@DisplayName("RetrospectiveCleanupService 테스트")
class RetrospectiveCleanupServiceTest {

    private lateinit var retrospectiveRepository: RetrospectiveRepository
    private lateinit var cleanupService: RetrospectiveCleanupService

    @BeforeEach
    fun setUp() {
        retrospectiveRepository = mockk(relaxed = true)
        cleanupService = RetrospectiveCleanupService(retrospectiveRepository, retentionDays = 180)
    }

    @Test
    @DisplayName("수동 정리 시 지정된 일수 이상 된 회고를 삭제한다")
    fun `수동 정리 테스트`() {
        // given
        val olderThanDays = 180
        val deletedCount = 5L

        every { retrospectiveRepository.countByCreatedAtBefore(any<LocalDateTime>()) } returns deletedCount
        every { retrospectiveRepository.deleteByCreatedAtBefore(any<LocalDateTime>()) } just runs

        // when
        val result = cleanupService.cleanupRetrospectives(olderThanDays)

        // then
        assertThat(result).isEqualTo(deletedCount)
        verify(exactly = 1) { retrospectiveRepository.countByCreatedAtBefore(any<LocalDateTime>()) }
        verify(exactly = 1) { retrospectiveRepository.deleteByCreatedAtBefore(any<LocalDateTime>()) }
    }

    @Test
    @DisplayName("자동 정리 스케줄러가 실행되면 설정된 보관 기간 이상 된 회고를 삭제한다")
    fun `자동 정리 스케줄러 테스트`() {
        // given
        val deletedCount = 3L

        every { retrospectiveRepository.countByCreatedAtBefore(any<LocalDateTime>()) } returns deletedCount
        every { retrospectiveRepository.deleteByCreatedAtBefore(any<LocalDateTime>()) } just runs

        // when
        cleanupService.autoCleanupRetrospectives()

        // then
        verify(exactly = 1) { retrospectiveRepository.countByCreatedAtBefore(any<LocalDateTime>()) }
        verify(exactly = 1) { retrospectiveRepository.deleteByCreatedAtBefore(any<LocalDateTime>()) }
    }

    @Test
    @DisplayName("삭제할 회고가 없으면 0을 반환한다")
    fun `삭제할 회고가 없는 경우`() {
        // given
        val olderThanDays = 180
        val deletedCount = 0L

        every { retrospectiveRepository.countByCreatedAtBefore(any<LocalDateTime>()) } returns deletedCount
        every { retrospectiveRepository.deleteByCreatedAtBefore(any<LocalDateTime>()) } just runs

        // when
        val result = cleanupService.cleanupRetrospectives(olderThanDays)

        // then
        assertThat(result).isEqualTo(0L)
        verify(exactly = 1) { retrospectiveRepository.countByCreatedAtBefore(any<LocalDateTime>()) }
        verify(exactly = 1) { retrospectiveRepository.deleteByCreatedAtBefore(any<LocalDateTime>()) }
    }
}

