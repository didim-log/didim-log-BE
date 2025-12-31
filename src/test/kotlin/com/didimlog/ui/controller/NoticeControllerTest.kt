package com.didimlog.ui.controller

import com.didimlog.application.notice.NoticeService
import com.didimlog.domain.Notice
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.exception.GlobalExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@DisplayName("NoticeController 테스트")
@WebMvcTest(
    controllers = [NoticeController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration::class
    ]
)
@Import(GlobalExceptionHandler::class, NoticeControllerTest.TestConfig::class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class NoticeControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var noticeService: NoticeService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @TestConfiguration
    class TestConfig {
        @Bean
        fun noticeService(): NoticeService = mockk(relaxed = true)
    }

    @Test
    @DisplayName("공지사항 목록 조회 시 200 OK 및 Response JSON 구조 검증")
    fun `공지사항 목록 조회 성공`() {
        // given
        val notices = listOf(
            createNotice("notice-1", "공지사항 1", "내용 1", isPinned = true),
            createNotice("notice-2", "공지사항 2", "내용 2", isPinned = false)
        )
        val page = PageImpl(notices, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")), 2)

        every { noticeService.getNotices(any()) } returns page

        // when & then
        mockMvc.perform(
            get("/api/v1/notices")
                .param("page", "1")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.content[0].id").exists())
            .andExpect(jsonPath("$.content[0].title").exists())
            .andExpect(jsonPath("$.content[0].content").exists())
            .andExpect(jsonPath("$.content[0].isPinned").exists())
            .andExpect(jsonPath("$.totalElements").exists())
    }

    @Test
    @DisplayName("공지사항 상세 조회 시 200 OK 및 Response JSON 구조 검증")
    fun `공지사항 상세 조회 성공`() {
        // given
        val noticeId = "notice-1"
        val notice = createNotice(noticeId, "공지사항 제목", "공지사항 내용", isPinned = true)

        every { noticeService.getNotice(noticeId) } returns notice

        // when & then
        mockMvc.perform(
            get("/api/v1/notices/$noticeId")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(noticeId))
            .andExpect(jsonPath("$.title").value("공지사항 제목"))
            .andExpect(jsonPath("$.content").value("공지사항 내용"))
            .andExpect(jsonPath("$.isPinned").value(true))
    }

    @Test
    @DisplayName("공지사항 상세 조회 시 존재하지 않는 ID면 404 Not Found 반환")
    fun `공지사항 상세 조회 실패 - 존재하지 않음`() {
        // given
        val noticeId = "non-existent"
        every { noticeService.getNotice(noticeId) } throws BusinessException(
            ErrorCode.COMMON_RESOURCE_NOT_FOUND,
            "공지사항을 찾을 수 없습니다. id=$noticeId"
        )

        // when & then
        mockMvc.perform(
            get("/api/v1/notices/$noticeId")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("COMMON_RESOURCE_NOT_FOUND"))
    }

    @Test
    @DisplayName("공지사항 목록 조회 시 예상치 못한 예외가 발생하면 500 Internal Server Error 반환")
    fun `공지사항 목록 조회 - 500`() {
        // given
        every { noticeService.getNotices(any()) } throws RuntimeException("unexpected")

        // when & then
        mockMvc.perform(
            get("/api/v1/notices")
                .param("page", "1")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
    }

    private fun createNotice(
        id: String,
        title: String,
        content: String,
        isPinned: Boolean = false
    ): Notice {
        return Notice(
            id = id,
            title = title,
            content = content,
            isPinned = isPinned,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
    }
}

