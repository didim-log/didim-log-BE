package com.didimlog.application.auth

import com.didimlog.application.auth.boj.BojOwnershipVerificationService
import com.didimlog.domain.PasswordResetCode
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.config.mongo.MongoIndexInitializer
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.email.EmailService
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import com.mongodb.MongoCommandException
import com.mongodb.MongoWriteException
import com.mongodb.ServerAddress
import com.mongodb.WriteError
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDateTime
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder

@DisplayName("AuthService 분기 테스트")
class AuthServiceBranchTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val emailService: EmailService = mockk(relaxed = true)
    private val passwordResetCodeRepository: PasswordResetCodeRepository = mockk(relaxed = true)
    private val passwordResetCodeGenerator: PasswordResetCodeGenerator = mockk()
    private val refreshTokenService: RefreshTokenService = mockk(relaxed = true)
    private val bojOwnershipVerificationService: BojOwnershipVerificationService = mockk(relaxed = true)

    private val authService = AuthService(
        solvedAcClient,
        studentRepository,
        jwtTokenProvider,
        passwordEncoder,
        emailService,
        passwordResetCodeRepository,
        passwordResetCodeGenerator,
        refreshTokenService,
        bojOwnershipVerificationService,
        ImmediateCredentialSessionCoordinator()
    )

    private val email = "user@example.com"
    private val bojId = "testuser"
    private val bojIdVo = BojId(bojId)
    private val password = "Valid123!pw"

    private fun student(
        id: String? = "student-1",
        nickname: String = "tester",
        provider: Provider = Provider.BOJ,
        bojId: BojId? = bojIdVo,
        email: String? = this.email,
        password: String? = "encoded",
        rating: Int = 100,
        credentialVersion: Long = 0
    ) = Student(
        id = id,
        nickname = Nickname(nickname),
        provider = provider,
        providerId = "provider-id",
        email = email,
        bojId = bojId,
        password = password,
        credentialVersion = credentialVersion,
        rating = rating,
        currentTier = Tier.fromRating(rating),
        role = Role.USER
    )

    private fun stubTokenIssue() {
        every { jwtTokenProvider.createToken(any(), any(), any(), any()) } returns "access-token"
        every { refreshTokenService.generateAndSave(any()) } returns "refresh-token"
    }

    private fun writeException(code: Int, keyField: String? = null): MongoWriteException {
        val details = keyField?.let { BsonDocument("keyPattern", BsonDocument(it, BsonInt32(1))) } ?: BsonDocument()
        return MongoWriteException(WriteError(code, "write failed", details), ServerAddress(), emptySet())
    }

    private fun commandException(code: Int, keyField: String): MongoCommandException {
        val response = BsonDocument()
            .append("ok", BsonInt32(0))
            .append("code", BsonInt32(code))
            .append("errmsg", BsonString("command failed"))
            .append("keyPattern", BsonDocument(keyField, BsonInt32(1)))
        return MongoCommandException(response, ServerAddress())
    }

    @Nested
    @DisplayName("로그인")
    inner class Login {

        @Test
        @DisplayName("Solved.ac 동기화 중 예상치 못한 예외가 나도 기존 정보로 로그인한다")
        fun `예상치 못한 동기화 예외 무시`() {
            val saved = student()
            every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(saved)
            every { passwordEncoder.matches(password, "encoded") } returns true
            every { solvedAcClient.fetchUser(bojIdVo) } throws RuntimeException("network down")
            every { studentRepository.findById("student-1") } returns Optional.of(saved)
            stubTokenIssue()

            val result = authService.login(bojId, password)

            assertThat(result.token).isEqualTo("access-token")
            assertThat(result.refreshToken).isEqualTo("refresh-token")
            assertThat(result.rating).isEqualTo(100)
            verify {
                studentRepository.findByBojId(bojIdVo)
                studentRepository.findById("student-1")
            }
            confirmVerified(studentRepository)
        }

        @Test
        @DisplayName("프로필이 바뀌지 않았으면 Solved.ac 프로필을 갱신하지 않는다")
        fun `프로필 변화 없음`() {
            val saved = student(rating = 100)
            every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(saved)
            every { passwordEncoder.matches(password, "encoded") } returns true
            every { solvedAcClient.fetchUser(bojIdVo) } returns SolvedAcUserResponse(bojId, 100, 3)
            every { studentRepository.findById("student-1") } returns Optional.of(saved)
            stubTokenIssue()

            authService.login(bojId, password)

            verify {
                studentRepository.findByBojId(bojIdVo)
                studentRepository.findById("student-1")
            }
            confirmVerified(studentRepository)
        }

        @Test
        @DisplayName("토큰 발급 직전 학생이 삭제되었으면 로그인을 거절한다")
        fun `최신 학생 없음`() {
            every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(student())
            every { passwordEncoder.matches(password, "encoded") } returns true
            every { solvedAcClient.fetchUser(bojIdVo) } throws IllegalStateException("down")
            every { studentRepository.findById("student-1") } returns Optional.empty()

            val exception = assertThrows<BusinessException> { authService.login(bojId, password) }

            assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
            verify(exactly = 0) { jwtTokenProvider.createToken(any(), any(), any(), any()) }
        }

        @Test
        @DisplayName("토큰 발급 직전 비밀번호가 변경되었으면 로그인을 거절한다")
        fun `최신 비밀번호 불일치`() {
            every { studentRepository.findByBojId(bojIdVo) } returns Optional.of(student())
            every { passwordEncoder.matches(password, "encoded") } returns true
            every { passwordEncoder.matches(password, "changed") } returns false
            every { solvedAcClient.fetchUser(bojIdVo) } throws IllegalStateException("down")
            every { studentRepository.findById("student-1") } returns
                Optional.of(student(password = "changed", credentialVersion = 1))

            val exception = assertThrows<BusinessException> { authService.login(bojId, password) }

            assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
            assertThat(exception.message).contains("비밀번호가 일치하지 않습니다")
            verify(exactly = 0) { refreshTokenService.generateAndSave(any()) }
        }
    }

    @Nested
    @DisplayName("BOJ 회원가입")
    inner class Signup {

        private fun stubValidSignup(handle: String = bojId) {
            every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()
            every { studentRepository.findByEmail(email) } returns Optional.empty()
            every { solvedAcClient.fetchUser(bojIdVo) } returns SolvedAcUserResponse(handle, 1200, 10)
            every { passwordEncoder.encode(password) } returns "encoded"
        }

        @Test
        @DisplayName("이미 사용 중인 이메일이면 Solved.ac 조회 전에 거절한다")
        fun `이메일 중복`() {
            every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()
            every { studentRepository.findByEmail(email) } returns Optional.of(student(id = "other"))

            assertThatThrownBy { authService.signup(bojId, password, email, "session") }
                .isInstanceOf(BusinessException::class.java)
                .hasMessageContaining("이미 사용 중인 이메일입니다")
            verify(exactly = 0) { solvedAcClient.fetchUser(bojIdVo) }
        }

        @Test
        @DisplayName("Solved.ac 조회 중 예상치 못한 예외는 외부 서비스 오류로 알린다")
        fun `Solved ac 예상치 못한 예외`() {
            every { studentRepository.findByBojId(bojIdVo) } returns Optional.empty()
            every { studentRepository.findByEmail(email) } returns Optional.empty()
            every { solvedAcClient.fetchUser(bojIdVo) } throws RuntimeException("timeout")

            val exception = assertThrows<BusinessException> { authService.signup(bojId, password, email, "session") }

            assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR)
            assertThat(exception.message).doesNotContain("유효하지 않은 BOJ ID")
        }

        @Test
        @DisplayName("Solved.ac 핸들이 닉네임 정책을 위반하면 가입을 거절하고 인증 세션을 소비하지 않는다")
        fun `닉네임 생성 실패`() {
            stubValidSignup(handle = "a")

            val exception = assertThrows<BusinessException> { authService.signup(bojId, password, email, "session") }

            assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
            assertThat(exception.message).contains("유효하지 않은 닉네임입니다")
            verify(exactly = 0) { bojOwnershipVerificationService.consumeVerifiedBojId(any(), any()) }
            verify(exactly = 0) { studentRepository.save(any()) }
        }

        @Test
        @DisplayName("MongoWriteException 중복 키의 keyPattern으로 이메일 충돌을 분류한다")
        fun `MongoWriteException 이메일 중복`() {
            stubValidSignup()
            every { studentRepository.save(any()) } throws writeException(11000, "email")

            assertThatThrownBy { authService.signup(bojId, password, email, "session") }
                .isInstanceOf(BusinessException::class.java)
                .hasMessageContaining("이미 사용 중인 이메일입니다")
        }

        @Test
        @DisplayName("중복 키 원인을 알 수 없으면 BOJ ID 중복으로 안내한다")
        fun `알 수 없는 중복 키`() {
            stubValidSignup()
            every { studentRepository.save(any()) } throws DuplicateKeyException("duplicate key")

            val exception = assertThrows<BusinessException> { authService.signup(bojId, password, email, "session") }

            assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
            assertThat(exception.message).contains("이미 가입된 BOJ ID입니다")
        }

        @Test
        @DisplayName("중복 키가 아닌 MongoWriteException은 그대로 전파한다")
        fun `중복 키가 아닌 쓰기 예외`() {
            stubValidSignup()
            val writeFailure = writeException(121)
            every { studentRepository.save(any()) } throws writeFailure

            assertThatThrownBy { authService.signup(bojId, password, email, "session") }
                .isSameAs(writeFailure)
        }

        @Test
        @DisplayName("기타 저장 예외는 그대로 전파한다")
        fun `기타 저장 예외`() {
            stubValidSignup()
            val failure = IllegalStateException("db down")
            every { studentRepository.save(any()) } throws failure

            assertThatThrownBy { authService.signup(bojId, password, email, "session") }
                .isSameAs(failure)
        }

        @Test
        @DisplayName("저장된 학생에 ID가 없으면 토큰을 발급하지 않는다")
        fun `저장 학생 ID 없음`() {
            stubValidSignup()
            every { studentRepository.save(any()) } answers { firstArg<Student>() }

            assertThatThrownBy { authService.signup(bojId, password, email, "session") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("저장된 학생만 Access Token을 발급할 수 있습니다")
            verify(exactly = 0) { jwtTokenProvider.createToken(any(), any(), any(), any()) }
        }

        @Test
        @DisplayName("저장 직후 학생이 사라지거나 BOJ ID가 바뀌면 토큰을 발급하지 않는다")
        fun `토큰 발급 대상 변경`() {
            stubValidSignup()
            every { studentRepository.save(any()) } answers { firstArg<Student>().copy(id = "new-id") }
            every { studentRepository.findById("new-id") } returnsMany listOf(
                Optional.empty(),
                Optional.of(student(id = "new-id", bojId = BojId("otheruser")))
            )

            val missing = assertThrows<BusinessException> { authService.signup(bojId, password, email, "session") }
            val changed = assertThrows<BusinessException> { authService.signup(bojId, password, email, "session") }

            assertThat(missing.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
            assertThat(changed.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
            verify(exactly = 0) { jwtTokenProvider.createToken(any(), any(), any(), any()) }
        }
    }

    @Nested
    @DisplayName("비밀번호 찾기")
    inner class FindPassword {

        private fun stubStudent(latest: Optional<Student> = Optional.of(student())) {
            every { studentRepository.findByEmail(email) } returns Optional.of(student())
            every { studentRepository.findById("student-1") } returns latest
        }

        private fun issued(code: String = "RESET001") = PasswordResetCode(
            resetCode = code,
            studentId = "student-1",
            credentialVersion = 0,
            bojId = bojId,
            expiresAt = LocalDateTime.now().plusMinutes(30)
        )

        @Test
        @DisplayName("저장된 학생 ID가 없으면 내부 오류로 처리한다")
        fun `학생 ID 없음`() {
            every { studentRepository.findByEmail(email) } returns Optional.of(student(id = null))

            val exception = assertThrows<BusinessException> { authService.findPassword(email, bojId) }

            assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR)
        }

        @Test
        @DisplayName("코드 발급 직전 계정이 삭제되면 코드를 발급하지 않는다")
        fun `발급 직전 계정 삭제`() {
            stubStudent(latest = Optional.empty())

            val exception = assertThrows<BusinessException> { authService.findPassword(email, bojId) }

            assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
            verify(exactly = 0) { passwordResetCodeRepository.issueForStudent(any(), any(), any(), any(), any(), any()) }
        }

        @Test
        @DisplayName("코드 발급 직전 이메일이나 BOJ ID가 바뀌면 코드를 발급하지 않는다")
        fun `발급 직전 계정 정보 변경`() {
            every { studentRepository.findByEmail(email) } returns Optional.of(student())
            every { studentRepository.findById("student-1") } returnsMany listOf(
                Optional.of(student(email = "changed@example.com")),
                Optional.of(student(bojId = BojId("changed")))
            )

            repeat(2) {
                assertThatThrownBy { authService.findPassword(email, bojId) }
                    .isInstanceOf(BusinessException::class.java)
                    .hasMessageContaining("이메일과 BOJ ID가 일치하지 않습니다")
            }
            verify(exactly = 0) { passwordResetCodeRepository.issueForStudent(any(), any(), any(), any(), any(), any()) }
        }

        @Test
        @DisplayName("코드 발급 직전 비밀번호가 제거된 계정이면 코드를 발급하지 않는다")
        fun `발급 직전 비밀번호 제거`() {
            stubStudent(latest = Optional.of(student(password = null)))

            assertThatThrownBy { authService.findPassword(email, bojId) }
                .isInstanceOf(BusinessException::class.java)
                .hasMessageContaining("비밀번호가 설정되지 않은 계정입니다")
        }

        @Test
        @DisplayName("MongoWriteException의 resetCode 중복은 새 코드로 재시도한다")
        fun `MongoWriteException 코드 충돌 재시도`() {
            stubStudent()
            every { passwordResetCodeGenerator.generate() } returnsMany listOf("RESET001", "RESET002")
            every {
                passwordResetCodeRepository.issueForStudent("student-1", "RESET001", any(), any(), 0, bojId)
            } throws writeException(11000, "resetCode")
            every {
                passwordResetCodeRepository.issueForStudent("student-1", "RESET002", any(), any(), 0, bojId)
            } returns issued("RESET002")

            authService.findPassword(email, bojId)

            verify(exactly = 1) {
                emailService.sendTemplateEmail(email, any(), any(), match { it["resetCode"] == "RESET002" })
            }
        }

        @Test
        @DisplayName("MongoCommandException의 studentId 중복은 같은 코드로 재시도한다")
        fun `MongoCommandException 학생 충돌 재시도`() {
            stubStudent()
            every { passwordResetCodeGenerator.generate() } returns "RESET001"
            every {
                passwordResetCodeRepository.issueForStudent("student-1", "RESET001", any(), any(), 0, bojId)
            } throws commandException(11000, "studentId") andThen issued()

            authService.findPassword(email, bojId)

            verify(exactly = 1) { passwordResetCodeGenerator.generate() }
            verify(exactly = 2) {
                passwordResetCodeRepository.issueForStudent("student-1", "RESET001", any(), any(), 0, bojId)
            }
        }

        @Test
        @DisplayName("충돌 필드를 알 수 없는 중복 키 예외는 재시도하지 않고 전파한다")
        fun `알 수 없는 충돌 필드`() {
            stubStudent()
            every { passwordResetCodeGenerator.generate() } returns "RESET001"
            val duplicate = DuplicateKeyException("E11000 duplicate key error index: other_index")
            every {
                passwordResetCodeRepository.issueForStudent(any(), any(), any(), any(), any(), any())
            } throws duplicate

            assertThatThrownBy { authService.findPassword(email, bojId) }.isSameAs(duplicate)
            verify(exactly = 1) { passwordResetCodeRepository.issueForStudent(any(), any(), any(), any(), any(), any()) }
        }

        @Test
        @DisplayName("인덱스 이름이 포함되어도 중복 키 예외가 아니면 재시도하지 않는다")
        fun `중복 키가 아닌 예외`() {
            stubStudent()
            every { passwordResetCodeGenerator.generate() } returns "RESET001"
            val failure = IllegalStateException(
                "write concern failed on ${MongoIndexInitializer.PASSWORD_RESET_CODE_UNIQUE_INDEX_NAME}"
            )
            every {
                passwordResetCodeRepository.issueForStudent(any(), any(), any(), any(), any(), any())
            } throws failure

            assertThatThrownBy { authService.findPassword(email, bojId) }.isSameAs(failure)
            verify(exactly = 1) { passwordResetCodeRepository.issueForStudent(any(), any(), any(), any(), any(), any()) }
        }

        @Test
        @DisplayName("메일 실패 후 보상 삭제 대상이 없어도 원래 메일 예외를 전파한다")
        fun `보상 삭제 대상 없음`() {
            stubStudent()
            every { passwordResetCodeGenerator.generate() } returns "RESET001"
            every {
                passwordResetCodeRepository.issueForStudent(any(), any(), any(), any(), any(), any())
            } returns issued()
            val mailFailure = RuntimeException("smtp down")
            every { emailService.sendTemplateEmail(any(), any(), any(), any()) } throws mailFailure
            every { passwordResetCodeRepository.deleteIssuedCode("student-1", "RESET001") } returns false

            assertThatThrownBy { authService.findPassword(email, bojId) }.isSameAs(mailFailure)
            assertThat(mailFailure.suppressed).isEmpty()
        }

        @Test
        @DisplayName("보상 삭제가 메일 예외와 같은 예외를 던지면 자기 자신을 suppressed로 추가하지 않는다")
        fun `보상 예외가 메일 예외와 동일`() {
            stubStudent()
            every { passwordResetCodeGenerator.generate() } returns "RESET001"
            every {
                passwordResetCodeRepository.issueForStudent(any(), any(), any(), any(), any(), any())
            } returns issued()
            val shared = RuntimeException("shared failure")
            every { emailService.sendTemplateEmail(any(), any(), any(), any()) } throws shared
            every { passwordResetCodeRepository.deleteIssuedCode(any(), any()) } throws shared

            assertThatThrownBy { authService.findPassword(email, bojId) }.isSameAs(shared)
            assertThat(shared.suppressed).isEmpty()
        }
    }

    @Nested
    @DisplayName("비밀번호 재설정과 중복 확인")
    inner class ResetPasswordAndDuplicate {

        @Test
        @DisplayName("재설정 코드가 다른 요청에서 먼저 소비되면 비밀번호를 변경하지 않는다")
        fun `재설정 코드 선점 소비`() {
            val code = PasswordResetCode(
                resetCode = "RESET001",
                studentId = "student-1",
                credentialVersion = 0,
                bojId = bojId,
                expiresAt = LocalDateTime.now().plusMinutes(10)
            )
            every { passwordResetCodeRepository.findByResetCode("RESET001") } returns Optional.of(code)
            every { studentRepository.findById("student-1") } returns Optional.of(student())
            every { passwordResetCodeRepository.consumeByResetCode("RESET001", "student-1") } returns null

            assertThatThrownBy { authService.resetPassword("RESET001", "NewPass123!") }
                .isInstanceOf(BusinessException::class.java)
                .hasMessageContaining("유효하지 않은 재설정 코드입니다")
            verify(exactly = 0) { studentRepository.updatePasswordById(any(), any(), any(), bojIdVo) }
            verify(exactly = 0) { refreshTokenService.revokeAllForStudent(any()) }
        }

        @Test
        @DisplayName("BOJ ID 중복 여부를 저장소 결과로 반환한다")
        fun `BOJ ID 중복 확인`() {
            every { studentRepository.existsByBojId(bojIdVo) } returns true
            every { studentRepository.existsByBojId(BojId("freeuser")) } returns false

            assertThat(authService.checkBojIdDuplicate(bojId)).isTrue()
            assertThat(authService.checkBojIdDuplicate("freeuser")).isFalse()
        }

        @Test
        @DisplayName("BOJ ID 형식이 잘못되면 중복 확인 전에 예외가 발생한다")
        fun `BOJ ID 형식 오류`() {
            assertThatThrownBy { authService.checkBojIdDuplicate("bad id!") }
                .isInstanceOf(IllegalArgumentException::class.java)
            confirmVerified(studentRepository)
        }
    }
}
