package com.didimlog.application.admin

import com.didimlog.domain.Log
import com.didimlog.domain.enums.AiReviewStatus
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

@DisplayName("AdminLogService 테스트")
class AdminLogServiceTest {

    private val logRepository: LogRepository = mockk()
    private val service = AdminLogService(logRepository)

    private val pageable = PageRequest.of(0, 20)
    private val meaningfulStatuses = listOf(AiReviewStatus.COMPLETED, AiReviewStatus.FAILED)
    private val log = Log(
        id = "log-1",
        title = LogTitle("제목"),
        content = LogContent("내용"),
        code = LogCode("print(1)")
    )

    @Test
    @DisplayName("BOJ ID가 없으면 AI 생성이 시도된 전체 로그를 조회한다")
    fun `전체 로그 조회`() {
        val page = PageImpl(listOf(log))
        every { logRepository.findAllByAiReviewStatusInOrderByCreatedAtDesc(meaningfulStatuses, pageable) } returns page

        assertThat(service.getLogs(null, pageable)).isSameAs(page)
        verify(exactly = 0) {
            logRepository.findByBojIdValueAndAiReviewStatusInOrderByCreatedAtDesc(any(), any(), any())
        }
    }

    @Test
    @DisplayName("BOJ ID가 있으면 해당 사용자의 AI 생성 로그만 조회한다")
    fun `BOJ ID 로그 조회`() {
        val page = PageImpl(listOf(log))
        every {
            logRepository.findByBojIdValueAndAiReviewStatusInOrderByCreatedAtDesc("boj_user", meaningfulStatuses, pageable)
        } returns page

        assertThat(service.getLogs("boj_user", pageable)).isSameAs(page)
    }

    @Test
    @DisplayName("단건 로그가 없으면 예외가 발생한다")
    fun `단건 로그 조회`() {
        every { logRepository.findById("log-1") } returns Optional.of(log)
        every { logRepository.findById("missing") } returns Optional.empty()

        assertThat(service.getLog("log-1")).isSameAs(log)
        assertThatThrownBy { service.getLog("missing") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("로그를 찾을 수 없습니다. logId=missing")
    }
}
