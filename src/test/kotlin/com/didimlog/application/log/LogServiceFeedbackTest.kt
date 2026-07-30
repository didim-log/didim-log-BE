package com.didimlog.application.log

import com.didimlog.domain.Log
import com.didimlog.domain.enums.AiFeedbackStatus
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.valueobject.BojId
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
        val requesterBojId = "owner1"
        val log = log(logId, BojId(requesterBojId))
        val updatedLog = log.updateFeedback(AiFeedbackStatus.LIKE, null)

        every { logRepository.findById(logId) } returns Optional.of(log)
        every { logRepository.save(updatedLog) } answers { firstArg() }

        // when
        val result = logService.updateFeedback(logId, requesterBojId, AiFeedbackStatus.LIKE, null)

        // then
        assertThat(result.aiFeedbackStatus).isEqualTo(AiFeedbackStatus.LIKE)
        assertThat(result.aiFeedbackReason).isNull()
        verify(exactly = 1) { logRepository.findById(logId) }
        verify(exactly = 1) { logRepository.save(updatedLog) }
    }

    @Test
    @DisplayName("피드백 업데이트 성공 - DISLIKE with reason")
    fun `피드백 업데이트 성공 DISLIKE with reason`() {
        // given
        val logId = "log-123"
        val requesterBojId = "owner1"
        val reason = "INACCURATE"
        val log = log(logId, BojId(requesterBojId))
        val updatedLog = log.updateFeedback(AiFeedbackStatus.DISLIKE, reason)

        every { logRepository.findById(logId) } returns Optional.of(log)
        every { logRepository.save(updatedLog) } answers { firstArg() }

        // when
        val result = logService.updateFeedback(logId, requesterBojId, AiFeedbackStatus.DISLIKE, reason)

        // then
        assertThat(result.aiFeedbackStatus).isEqualTo(AiFeedbackStatus.DISLIKE)
        assertThat(result.aiFeedbackReason).isEqualTo(reason)
        verify(exactly = 1) { logRepository.findById(logId) }
        verify(exactly = 1) { logRepository.save(updatedLog) }
    }

    @Test
    @DisplayName("피드백 업데이트 실패 - 로그를 찾을 수 없음")
    fun `피드백 업데이트 실패 로그 없음`() {
        // given
        val logId = "non-existent"

        every { logRepository.findById(logId) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            logService.updateFeedback(logId, "owner1", AiFeedbackStatus.LIKE, null)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        assertThat(exception.message).contains("로그를 찾을 수 없습니다")
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("인증 BOJ ID가 비어 있으면 로그를 조회하지 않는다")
    fun `피드백 업데이트 실패 인증 정보 없음`() {
        val exception = assertThrows<BusinessException> {
            logService.updateFeedback("log-123", " ", AiFeedbackStatus.LIKE, null)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        verify(exactly = 0) { logRepository.findById(any()) }
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("소유자가 없는 로그에는 피드백을 저장하지 않는다")
    fun `피드백 업데이트 실패 로그 소유자 없음`() {
        val logId = "log-123"
        every { logRepository.findById(logId) } returns Optional.of(log(logId, null))

        val exception = assertThrows<BusinessException> {
            logService.updateFeedback(logId, "owner1", AiFeedbackStatus.LIKE, null)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.ACCESS_DENIED)
        verify(exactly = 0) { logRepository.save(any()) }
    }

    @Test
    @DisplayName("다른 사용자의 로그에는 피드백을 저장하지 않는다")
    fun `피드백 업데이트 실패 소유자 불일치`() {
        val logId = "log-123"
        every { logRepository.findById(logId) } returns Optional.of(log(logId, BojId("owner1")))

        val exception = assertThrows<BusinessException> {
            logService.updateFeedback(logId, "other1", AiFeedbackStatus.DISLIKE, "INACCURATE")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.ACCESS_DENIED)
        verify(exactly = 0) { logRepository.save(any()) }
    }

    private fun log(logId: String, bojId: BojId?): Log {
        return Log(
            id = logId,
            title = LogTitle("Test"),
            content = LogContent("Content"),
            code = LogCode("code"),
            bojId = bojId,
            aiFeedbackStatus = AiFeedbackStatus.NONE
        )
    }
}












