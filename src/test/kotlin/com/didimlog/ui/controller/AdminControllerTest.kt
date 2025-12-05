package com.didimlog.ui.controller

import com.didimlog.application.admin.AdminService
import com.didimlog.application.feedback.FeedbackService
import com.didimlog.domain.Feedback
import com.didimlog.domain.Quote
import com.didimlog.domain.Student
import com.didimlog.domain.enums.FeedbackStatus
import com.didimlog.domain.enums.FeedbackType
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

@DisplayName("AdminController 테스트")
class AdminControllerTest {

    private val adminService: AdminService = mockk()
    private val feedbackService: FeedbackService = mockk()
    private val adminController = AdminController(adminService, feedbackService)

    @Test
    @DisplayName("전체 회원 목록 조회 성공")
    fun `전체 회원 목록 조회 성공`() {
        // given
        val students = listOf(
            Student(
                id = "student1",
                nickname = Nickname("user1"),
                provider = Provider.BOJ,
                providerId = "user1",
                bojId = BojId("user1"),
                password = "encoded",
                currentTier = Tier.BRONZE,
                role = Role.USER
            ),
            Student(
                id = "student2",
                nickname = Nickname("user2"),
                provider = Provider.BOJ,
                providerId = "user2",
                bojId = BojId("user2"),
                password = "encoded",
                currentTier = Tier.SILVER,
                role = Role.USER
            )
        )
        val pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "rating"))
        val studentPage = PageImpl(students, pageable, students.size.toLong())
        val adminUserResponsePage = studentPage.map { com.didimlog.ui.dto.AdminUserResponse.from(it) }

        every { adminService.getAllUsers(any()) } returns adminUserResponsePage

        // when
        val response = adminController.getAllUsers(0, 20)

        // then
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.content?.size).isEqualTo(2)
        verify(exactly = 1) { adminService.getAllUsers(any()) }
    }

    @Test
    @DisplayName("회원 강제 탈퇴 성공")
    fun `회원 강제 탈퇴 성공`() {
        // given
        val studentId = "student1"
        every { adminService.deleteUser(studentId) } returns Unit

        // when
        val response = adminController.deleteUser(studentId)

        // then
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.get("message")).isEqualTo("회원이 성공적으로 탈퇴되었습니다.")
        verify(exactly = 1) { adminService.deleteUser(studentId) }
    }

    @Test
    @DisplayName("존재하지 않는 회원 탈퇴 시 예외 발생")
    fun `존재하지 않는 회원 탈퇴 시 예외 발생`() {
        // given
        val studentId = "non-existent"
        every { adminService.deleteUser(studentId) } throws BusinessException(
            ErrorCode.STUDENT_NOT_FOUND,
            "학생을 찾을 수 없습니다. studentId=$studentId"
        )

        // when & then
        assertThatThrownBy {
            adminController.deleteUser(studentId)
        }.isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("학생을 찾을 수 없습니다")
    }

    @Test
    @DisplayName("명언 목록 조회 성공")
    fun `명언 목록 조회 성공`() {
        // given
        val quotes = listOf(
            Quote(id = "quote1", content = "명언 1", author = "작가 1"),
            Quote(id = "quote2", content = "명언 2", author = "작가 2")
        )
        val pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"))
        val page = PageImpl(quotes, pageable, quotes.size.toLong())

        every { adminService.getAllQuotes(any()) } returns page

        // when
        val response = adminController.getAllQuotes(0, 20)

        // then
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.content?.size).isEqualTo(2)
        verify(exactly = 1) { adminService.getAllQuotes(any()) }
    }

    @Test
    @DisplayName("명언 추가 성공")
    fun `명언 추가 성공`() {
        // given
        val request = QuoteCreateRequest(content = "새로운 명언", author = "작가명")
        val savedQuote = Quote(id = "quote1", content = request.content, author = request.author)

        every { adminService.createQuote(request.content, request.author) } returns savedQuote

        // when
        val response = adminController.createQuote(request)

        // then
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.body?.content).isEqualTo(request.content)
        assertThat(response.body?.author).isEqualTo(request.author)
        verify(exactly = 1) { adminService.createQuote(request.content, request.author) }
    }

    @Test
    @DisplayName("명언 삭제 성공")
    fun `명언 삭제 성공`() {
        // given
        val quoteId = "quote1"
        every { adminService.deleteQuote(quoteId) } returns Unit

        // when
        val response = adminController.deleteQuote(quoteId)

        // then
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.get("message")).isEqualTo("명언이 성공적으로 삭제되었습니다.")
        verify(exactly = 1) { adminService.deleteQuote(quoteId) }
    }

    @Test
    @DisplayName("피드백 목록 조회 성공")
    fun `피드백 목록 조회 성공`() {
        // given
        val feedbacks = listOf(
            Feedback(
                writerId = "student1",
                content = "버그 리포트입니다. 매우 긴 내용을 작성합니다.",
                type = FeedbackType.BUG,
                status = FeedbackStatus.PENDING
            ).copy(id = "feedback1")
        )
        val pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))
        val page = PageImpl(feedbacks, pageable, feedbacks.size.toLong())

        every { feedbackService.getAllFeedbacks(any()) } returns page

        // when
        val response = adminController.getAllFeedbacks(0, 20)

        // then
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.content?.size).isEqualTo(1)
        verify(exactly = 1) { feedbackService.getAllFeedbacks(any()) }
    }

    @Test
    @DisplayName("피드백 상태 변경 성공")
    fun `피드백 상태 변경 성공`() {
        // given
        val feedbackId = "feedback1"
        val request = FeedbackStatusUpdateRequest(status = FeedbackStatus.COMPLETED)
        val updatedFeedback = Feedback(
            writerId = "student1",
            content = "버그 리포트입니다. 매우 긴 내용을 작성합니다.",
            type = FeedbackType.BUG,
            status = FeedbackStatus.COMPLETED
        ).copy(id = feedbackId)

        every { feedbackService.updateFeedbackStatus(feedbackId, request.status) } returns updatedFeedback

        // when
        val response = adminController.updateFeedbackStatus(feedbackId, request)

        // then
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.status).isEqualTo("COMPLETED")
        verify(exactly = 1) { feedbackService.updateFeedbackStatus(feedbackId, request.status) }
    }
}
