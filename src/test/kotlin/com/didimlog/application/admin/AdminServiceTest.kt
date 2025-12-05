package com.didimlog.application.admin

import com.didimlog.domain.Quote
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.QuoteRepository
import com.didimlog.domain.repository.StudentRepository
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
import java.util.Optional

@DisplayName("AdminService 테스트")
class AdminServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val quoteRepository: QuoteRepository = mockk()
    private val adminService = AdminService(studentRepository, quoteRepository)

    @Test
    @DisplayName("명언 목록을 페이징하여 조회할 수 있다")
    fun `명언 목록 조회 성공`() {
        // given
        val quotes = listOf(
            Quote(id = "quote1", content = "명언 1", author = "작가 1"),
            Quote(id = "quote2", content = "명언 2", author = "작가 2")
        )
        val pageable = PageRequest.of(0, 20)
        val page = PageImpl(quotes, pageable, quotes.size.toLong())

        every { quoteRepository.findAll(pageable) } returns page

        // when
        val result = adminService.getAllQuotes(pageable)

        // then
        assertThat(result.content).hasSize(2)
        assertThat(result.content[0].content).isEqualTo("명언 1")
        verify(exactly = 1) { quoteRepository.findAll(pageable) }
    }

    @Test
    @DisplayName("새로운 명언을 추가할 수 있다")
    fun `명언 추가 성공`() {
        // given
        val content = "새로운 명언"
        val author = "작가명"
        val savedQuote = Quote(id = "quote1", content = content, author = author)

        every { quoteRepository.save(any<Quote>()) } returns savedQuote

        // when
        val result = adminService.createQuote(content, author)

        // then
        assertThat(result.content).isEqualTo(content)
        assertThat(result.author).isEqualTo(author)
        verify(exactly = 1) { quoteRepository.save(any<Quote>()) }
    }

    @Test
    @DisplayName("명언을 삭제할 수 있다")
    fun `명언 삭제 성공`() {
        // given
        val quoteId = "quote1"
        val quote = Quote(id = quoteId, content = "명언", author = "작가")

        every { quoteRepository.findById(quoteId) } returns Optional.of(quote)
        every { quoteRepository.delete(quote) } returns Unit

        // when
        adminService.deleteQuote(quoteId)

        // then
        verify(exactly = 1) { quoteRepository.findById(quoteId) }
        verify(exactly = 1) { quoteRepository.delete(quote) }
    }

    @Test
    @DisplayName("존재하지 않는 명언 삭제 시 예외가 발생한다")
    fun `존재하지 않는 명언 삭제 시 예외 발생`() {
        // given
        val quoteId = "non-existent"
        every { quoteRepository.findById(quoteId) } returns Optional.empty()

        // when & then
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            adminService.deleteQuote(quoteId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        assertThat(exception.message).contains("명언을 찾을 수 없습니다")
    }

    @Test
    @DisplayName("회원 목록을 페이징하여 조회할 수 있다")
    fun `회원 목록 조회 성공`() {
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
            )
        )
        val pageable = PageRequest.of(0, 20)
        val studentPage = PageImpl(students, pageable, students.size.toLong())

        every { studentRepository.findAll(pageable) } returns studentPage

        // when
        val result = adminService.getAllUsers(pageable)

        // then
        assertThat(result.content).hasSize(1)
        assertThat(result.content[0].nickname).isEqualTo("user1")
        verify(exactly = 1) { studentRepository.findAll(pageable) }
    }

    @Test
    @DisplayName("회원을 강제 탈퇴시킬 수 있다")
    fun `회원 강제 탈퇴 성공`() {
        // given
        val studentId = "student1"
        val student = Student(
            id = studentId,
            nickname = Nickname("user1"),
            provider = Provider.BOJ,
            providerId = "user1",
            bojId = BojId("user1"),
            password = "encoded",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { studentRepository.delete(student) } returns Unit

        // when
        adminService.deleteUser(studentId)

        // then
        verify(exactly = 1) { studentRepository.findById(studentId) }
        verify(exactly = 1) { studentRepository.delete(student) }
    }

    @Test
    @DisplayName("존재하지 않는 회원 탈퇴 시 예외가 발생한다")
    fun `존재하지 않는 회원 탈퇴 시 예외 발생`() {
        // given
        val studentId = "non-existent"
        every { studentRepository.findById(studentId) } returns Optional.empty()

        // when & then
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            adminService.deleteUser(studentId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        assertThat(exception.message).contains("학생을 찾을 수 없습니다")
    }
}
