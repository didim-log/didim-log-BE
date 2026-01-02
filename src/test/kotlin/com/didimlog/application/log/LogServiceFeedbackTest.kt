package com.didimlog.application.log

import com.didimlog.domain.Log
import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

@DisplayName("LogService 피드백 테스트")
class LogServiceFeedbackTest {

    private val logRepository: LogRepository = mockk()
    private val logService = LogService(logRepository)

    @Test
    @DisplayName("피드백 업데이트 성공 - LIKE")
    fun `피드백 업데이트 성공 LIKE`() {
        // given
        val logId = "log-123"
        val log = Log(
            id = logId,
            title = LogTitle("Test"),
            content = LogContent("Content"),
            code = LogCode("code"),
            aiFeedbackStatus = AiFeedbackStatus.NONE
        )
        val updatedLog = log.updateFeedback(AiFeedbackStatus.LIKE, null)

        every { logRepository.findById(logId) } returns Optional.of(log)
        every { logRepository.save(any()) } returns updatedLog

        // when
        val result = logService.updateFeedback(logId, AiFeedbackStatus.LIKE, null)

        // then
        assertThat(result.aiFeedbackStatus).isEqualTo(AiFeedbackStatus.LIKE)
        assertThat(result.aiFeedbackReason).isNull()
        verify(exactly = 1) { logRepository.findById(logId) }
        verify(exactly = 1) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("피드백 업데이트 성공 - DISLIKE with reason")
    fun `피드백 업데이트 성공 DISLIKE with reason`() {
        // given
        val logId = "log-123"
        val reason = "INACCURATE"
        val log = Log(
            id = logId,
            title = LogTitle("Test"),
            content = LogContent("Content"),
            code = LogCode("code"),
            aiFeedbackStatus = AiFeedbackStatus.NONE
        )
        val updatedLog = log.updateFeedback(AiFeedbackStatus.DISLIKE, reason)

        every { logRepository.findById(logId) } returns Optional.of(log)
        every { logRepository.save(any()) } returns updatedLog

        // when
        val result = logService.updateFeedback(logId, AiFeedbackStatus.DISLIKE, reason)

        // then
        assertThat(result.aiFeedbackStatus).isEqualTo(AiFeedbackStatus.DISLIKE)
        assertThat(result.aiFeedbackReason).isEqualTo(reason)
        verify(exactly = 1) { logRepository.findById(logId) }
        verify(exactly = 1) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("피드백 업데이트 실패 - 로그를 찾을 수 없음")
    fun `피드백 업데이트 실패 로그 없음`() {
        // given
        val logId = "non-existent"

        every { logRepository.findById(logId) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            logService.updateFeedback(logId, AiFeedbackStatus.LIKE, null)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        assertThat(exception.message).contains("로그를 찾을 수 없습니다")
        verify(exactly = 0) { logRepository.save(any()) }
    }
}


