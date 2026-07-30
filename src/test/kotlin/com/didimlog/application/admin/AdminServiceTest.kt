package com.didimlog.application.admin

import com.didimlog.application.auth.CredentialSessionCoordinator
import com.didimlog.application.auth.ImmediateCredentialSessionCoordinator
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.domain.Quote
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.QuoteRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ui.dto.AdminUserUpdateDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.security.crypto.password.PasswordEncoder

@DisplayName("AdminService 테스트")
class AdminServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val quoteRepository: QuoteRepository = mockk()
    private val retrospectiveRepository: RetrospectiveRepository = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val refreshTokenService: RefreshTokenService = mockk(relaxed = true)
    private val adminService = AdminService(
        studentRepository,
        quoteRepository,
        retrospectiveRepository,
        passwordEncoder,
        refreshTokenService,
        ImmediateCredentialSessionCoordinator()
    )

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
            ),
            Student(
                id = "student2",
                nickname = Nickname("user2"),
                provider = Provider.BOJ,
                providerId = "user2",
                bojId = BojId("user2"),
                password = "encoded",
                currentTier = Tier.BRONZE,
                role = Role.USER
            ),
            Student(
                id = "student3",
                nickname = Nickname("user3"),
                provider = Provider.BOJ,
                providerId = "user3",
                bojId = BojId("user3"),
                password = "encoded",
                currentTier = Tier.BRONZE,
                role = Role.USER
            )
        )
        val pageable = PageRequest.of(0, 20)

        every {
            studentRepository.searchAdminUsers(pageable, null, null, null)
        } returns PageImpl(students, pageable, students.size.toLong())
        every {
            retrospectiveRepository.countByStudentIds(setOf("student1", "student2", "student3"))
        } returns linkedMapOf(
            "student2" to 1L,
            "student1" to 2L
        )

        // when
        val result = adminService.getAllUsers(pageable)

        // then
        assertThat(result.content).hasSize(3)
        assertThat(result.content[0].nickname).isEqualTo("user1")
        assertThat(result.content[0].retrospectiveCount).isEqualTo(2)
        assertThat(result.content[1].nickname).isEqualTo("user2")
        assertThat(result.content[1].retrospectiveCount).isEqualTo(1)
        assertThat(result.content[2].nickname).isEqualTo("user3")
        assertThat(result.content[2].retrospectiveCount).isZero()
        verify(exactly = 1) {
            studentRepository.searchAdminUsers(pageable, null, null, null)
        }
        verify(exactly = 1) {
            retrospectiveRepository.countByStudentIds(setOf("student1", "student2", "student3"))
        }
        verify(exactly = 0) { retrospectiveRepository.findAllByStudentId(any()) }
    }

    @Test
    @DisplayName("조회 결과가 비어 있으면 회고 수를 집계하지 않는다")
    fun `빈 회원 목록은 회고 수를 집계하지 않음`() {
        // given
        val pageable = PageRequest.of(0, 20)
        every {
            studentRepository.searchAdminUsers(pageable, null, null, null)
        } returns PageImpl(emptyList(), pageable, 0)

        // when
        val result = adminService.getAllUsers(pageable)

        // then
        assertThat(result.content).isEmpty()
        assertThat(result.totalElements).isZero()
        verify(exactly = 1) {
            studentRepository.searchAdminUsers(pageable, null, null, null)
        }
        verify(exactly = 0) { retrospectiveRepository.countByStudentIds(any()) }
        verify(exactly = 0) { retrospectiveRepository.findAllByStudentId(any()) }
    }

    @Test
    @DisplayName("관리자 회원 조회 조건을 DB 조회용 값으로 변환한다")
    fun `회원 검색과 가입일 조건 전달`() {
        // given
        val pageable = PageRequest.of(1, 20)
        val search = "User.1"
        val startDate = "2026-01-02"
        val endDate = "2026-01-03"
        every {
            studentRepository.searchAdminUsers(
                pageable = pageable,
                search = search,
                createdAtFrom = LocalDateTime.of(2026, 1, 2, 0, 0),
                createdAtTo = LocalDateTime.of(2026, 1, 3, 23, 59, 59)
            )
        } returns PageImpl(emptyList(), pageable, 0)

        // when
        adminService.getAllUsers(pageable, search, startDate, endDate)

        // then
        verify(exactly = 1) {
            studentRepository.searchAdminUsers(
                pageable = pageable,
                search = search,
                createdAtFrom = LocalDateTime.of(2026, 1, 2, 0, 0),
                createdAtTo = LocalDateTime.of(2026, 1, 3, 23, 59, 59)
            )
        }
    }

    @Test
    @DisplayName("가입일 형식이 잘못되면 DB를 조회하지 않는다")
    fun `잘못된 가입일 형식 거부`() {
        // given
        val pageable = PageRequest.of(0, 20)

        // when
        val exception = org.junit.jupiter.api.assertThrows<BusinessException> {
            adminService.getAllUsers(pageable, startDate = "2026/01/02")
        }

        // then
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        verify(exactly = 0) {
            studentRepository.searchAdminUsers(any(), any(), any(), any())
        }
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
        every { studentRepository.deleteById(studentId) } returns Unit

        // when
        adminService.deleteUser(studentId)

        // then
        verify(exactly = 1) { studentRepository.findById(studentId) }
        verifyOrder {
            refreshTokenService.revokeAllForStudent(studentId)
            studentRepository.deleteById(studentId)
        }
        verify(exactly = 0) { studentRepository.delete(any<Student>()) }
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
        verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
        verify(exactly = 0) { studentRepository.deleteById(any()) }
    }

    @Test
    @DisplayName("강제 탈퇴 세션 정리에 실패하면 학생 삭제를 시작하지 않는다")
    fun `회원 강제 탈퇴 세션 정리 실패 시 삭제하지 않음`() {
        val studentId = "student1"
        val student = student(studentId)
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every {
            refreshTokenService.revokeAllForStudent(studentId)
        } throws IllegalStateException("Redis unavailable")

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            adminService.deleteUser(studentId)
        }

        verify(exactly = 0) { studentRepository.deleteById(any()) }
    }

    @Test
    @DisplayName("관리자는 사용자 정보를 선택적으로 수정할 수 있다 (Role/Nickname/BOJ ID)")
    fun `관리자 사용자 강제 수정 성공`() {
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
        val request = AdminUserUpdateDto(
            role = "ROLE_ADMIN",
            nickname = "newNickname",
            bojId = "newBojId"
        )

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { studentRepository.existsByNickname(Nickname("newNickname")) } returns false
        every { studentRepository.existsByBojId(BojId("newBojId")) } returns false
        every { studentRepository.save(any<Student>()) } answers { firstArg() }

        // when
        val updated = adminService.updateUser(studentId, request)

        // then
        assertThat(updated.role).isEqualTo(Role.ADMIN)
        assertThat(updated.nickname.value).isEqualTo("newNickname")
        assertThat(updated.bojId?.value).isEqualTo("newBojId")
        verify(exactly = 1) { studentRepository.findById(studentId) }
        verify(exactly = 1) { studentRepository.save(any<Student>()) }
        verify(exactly = 1) { refreshTokenService.revokeAllForStudent(studentId) }
    }

    @Test
    @DisplayName("관리자 역할 변경은 자격 증명 버전을 올리고 기존 세션을 폐기한다")
    fun `관리자 역할 단독 변경 시 자격 증명 버전 증가 및 세션 폐기`() {
        val studentId = "student1"
        val student = student(studentId).copy(credentialVersion = 4)
        val coordinator = RecordingCredentialSessionCoordinator()
        val service = AdminService(
            studentRepository,
            quoteRepository,
            retrospectiveRepository,
            passwordEncoder,
            refreshTokenService,
            coordinator
        )
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { studentRepository.save(any<Student>()) } answers { firstArg() }

        val updated = service.updateUser(studentId, AdminUserUpdateDto(role = "ROLE_ADMIN"))

        assertThat(updated.role).isEqualTo(Role.ADMIN)
        assertThat(updated.credentialVersion).isEqualTo(5)
        assertThat(coordinator.executedStudentIds).containsExactly(studentId)
        verifyOrder {
            studentRepository.save(match { it.credentialVersion == 5L })
            refreshTokenService.revokeAllForStudent(studentId)
        }
    }

    @Test
    @DisplayName("관리자 BOJ ID 왕복 변경은 변경할 때마다 자격 증명 버전을 올린다")
    fun `관리자 BOJ ID 왕복 변경 시 자격 증명 버전 단조 증가`() {
        val studentId = "student1"
        var persisted = student(studentId).copy(credentialVersion = 4)

        every { studentRepository.findById(studentId) } answers { Optional.of(persisted) }
        every { studentRepository.existsByBojId(BojId("user2")) } returns false
        every { studentRepository.existsByBojId(BojId("user1")) } returns false
        every { studentRepository.save(any<Student>()) } answers {
            firstArg<Student>().also { persisted = it }
        }

        val changed = adminService.updateUser(
            studentId,
            AdminUserUpdateDto(bojId = "user2")
        )
        val restored = adminService.updateUser(
            studentId,
            AdminUserUpdateDto(bojId = "user1")
        )

        assertThat(changed.bojId).isEqualTo(BojId("user2"))
        assertThat(changed.credentialVersion).isEqualTo(5)
        assertThat(restored.bojId).isEqualTo(BojId("user1"))
        assertThat(restored.credentialVersion).isEqualTo(6)
        verify(exactly = 2) { refreshTokenService.revokeAllForStudent(studentId) }
    }

    @Test
    @DisplayName("관리자는 사용자 비밀번호를 선택적으로 수정할 수 있다")
    fun `관리자 사용자 비밀번호 수정 성공`() {
        // given
        val studentId = "student1"
        val student = Student(
            id = studentId,
            nickname = Nickname("user1"),
            provider = Provider.BOJ,
            providerId = "user1",
            bojId = BojId("user1"),
            password = "oldEncoded",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
        val request = AdminUserUpdateDto(password = "newPassword123!")

        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { passwordEncoder.encode("newPassword123!") } returns "newEncoded"
        every { studentRepository.save(any<Student>()) } answers { firstArg() }

        // when
        val updated = adminService.updateUser(studentId, request)

        // then
        assertThat(updated.password).isEqualTo("newEncoded")
        assertThat(updated.credentialVersion).isEqualTo(1)
        verify(exactly = 1) { passwordEncoder.encode("newPassword123!") }
        verifyOrder {
            studentRepository.save(any<Student>())
            refreshTokenService.revokeAllForStudent(studentId)
        }
    }

    @Test
    @DisplayName("관리자 비밀번호 변경과 같은 학생의 로그인 작업을 직렬화한다")
    fun `관리자 비밀번호 변경과 로그인 경합 직렬화`() {
        val studentId = "student1"
        val student = student(studentId)
        val coordinator = LockingCredentialSessionCoordinator()
        val service = AdminService(
            studentRepository,
            quoteRepository,
            retrospectiveRepository,
            passwordEncoder,
            refreshTokenService,
            coordinator
        )
        val updateReachedSave = CountDownLatch(1)
        val allowUpdateToFinish = CountDownLatch(1)
        val loginAttempted = CountDownLatch(1)
        val loginEntered = CountDownLatch(1)
        every { studentRepository.findById(studentId) } returns Optional.of(student)
        every { passwordEncoder.encode("newPassword123!") } returns "newEncoded"
        every { studentRepository.save(any<Student>()) } answers {
            updateReachedSave.countDown()
            check(allowUpdateToFinish.await(5, TimeUnit.SECONDS))
            firstArg()
        }

        val executor = Executors.newFixedThreadPool(2)
        try {
            val updateFuture = executor.submit<Student> {
                service.updateUser(studentId, AdminUserUpdateDto(password = "newPassword123!"))
            }
            assertThat(updateReachedSave.await(5, TimeUnit.SECONDS)).isTrue()

            val loginFuture = executor.submit {
                loginAttempted.countDown()
                coordinator.execute(studentId) {
                    loginEntered.countDown()
                }
            }

            assertThat(loginAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(loginEntered.await(200, TimeUnit.MILLISECONDS)).isFalse()
            allowUpdateToFinish.countDown()
            updateFuture.get(5, TimeUnit.SECONDS)
            loginFuture.get(5, TimeUnit.SECONDS)
            assertThat(loginEntered.count).isZero()
        } finally {
            allowUpdateToFinish.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun student(studentId: String): Student {
        return Student(
            id = studentId,
            nickname = Nickname("user1"),
            provider = Provider.BOJ,
            providerId = "user1",
            bojId = BojId("user1"),
            password = "oldEncoded",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
    }

    private class LockingCredentialSessionCoordinator : CredentialSessionCoordinator {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()

        override fun <T> execute(studentId: String, action: () -> T): T {
            return locks.computeIfAbsent(studentId) { ReentrantLock() }.withLock(action)
        }

        override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
            return execute(studentId, action)
        }
    }

    private class RecordingCredentialSessionCoordinator : CredentialSessionCoordinator {
        val executedStudentIds = mutableListOf<String>()

        override fun <T> execute(studentId: String, action: () -> T): T {
            executedStudentIds += studentId
            return action()
        }

        override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
            return execute(studentId, action)
        }
    }
}
