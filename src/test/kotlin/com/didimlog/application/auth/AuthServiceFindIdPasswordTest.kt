package com.didimlog.application.auth

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
import com.mongodb.MongoCommandException
import com.mongodb.ServerAddress
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.LocalDateTime
import java.util.Optional

@DisplayName("AuthService 아이디/비밀번호 찾기 테스트")
class AuthServiceFindIdPasswordTest {

    private val solvedAcClient = mockk<com.didimlog.infra.solvedac.SolvedAcClient>(relaxed = true)
    private val studentRepository = mockk<StudentRepository>(relaxed = true)
    private val jwtTokenProvider = mockk<JwtTokenProvider>(relaxed = true)
    private val passwordEncoder = mockk<PasswordEncoder>(relaxed = true)
    private val emailService = mockk<EmailService>(relaxed = true)
    private val passwordResetCodeRepository = mockk<PasswordResetCodeRepository>(relaxed = true)
    private val passwordResetCodeGenerator = mockk<PasswordResetCodeGenerator>(relaxed = true)
    private val refreshTokenService = mockk<RefreshTokenService>(relaxed = true)
    private val bojOwnershipVerificationService =
        mockk<com.didimlog.application.auth.boj.BojOwnershipVerificationService>(relaxed = true)

    private val authService = AuthService(
        solvedAcClient = solvedAcClient,
        studentRepository = studentRepository,
        jwtTokenProvider = jwtTokenProvider,
        passwordEncoder = passwordEncoder,
        emailService = emailService,
        passwordResetCodeRepository = passwordResetCodeRepository,
        passwordResetCodeGenerator = passwordResetCodeGenerator,
        refreshTokenService = refreshTokenService,
        bojOwnershipVerificationService = bojOwnershipVerificationService,
        credentialSessionCoordinator = ImmediateCredentialSessionCoordinator()
    )

    @Test
    @DisplayName("findId는 이메일로 가입된 사용자의 BOJ ID를 이메일로 전송한다")
    fun `아이디 찾기 성공`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val student = Student(
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            email = email,
            bojId = BojId(bojId),
            password = "encodedPassword",
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findByEmail(email) } returns Optional.of(student)

        // when
        authService.findId(email)

        // then
        verify(exactly = 1) {
            emailService.sendTemplateEmail(
                to = email,
                subject = "[디딤로그] 아이디 찾기",
                templateName = "mail/find-id",
                variables = match {
                    it["nickname"] == "testuser" && it["bojId"] == bojId
                }
            )
        }
    }

    @Test
    @DisplayName("findId는 이메일로 가입된 사용자가 없으면 예외를 발생시킨다")
    fun `아이디 찾기 실패 - 사용자 없음`() {
        // given
        val email = "notfound@example.com"
        every { studentRepository.findByEmail(email) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.findId(email)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        verify(exactly = 0) { emailService.sendTemplateEmail(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("findId는 BOJ ID가 등록되지 않은 계정이면 예외를 발생시킨다")
    fun `아이디 찾기 실패 - BOJ ID 없음`() {
        // given
        val email = "test@example.com"
        val student = Student(
            nickname = Nickname("testuser"),
            provider = Provider.GOOGLE,
            providerId = "google123",
            email = email,
            bojId = null,
            password = null,
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findByEmail(email) } returns Optional.of(student)

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.findId(email)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        verify(exactly = 0) { emailService.sendEmail(any(), any(), any()) }
    }

    @Test
    @DisplayName("findPassword는 저장된 재설정 코드와 같은 코드를 이메일로 전송한다")
    fun `비밀번호 찾기 성공 시 저장 코드와 메일 코드가 일치한다`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val studentId = "student-id"
        val resetCode = "RESET001"
        val expiresAt = mutableListOf<LocalDateTime>()
        val createdAt = mutableListOf<LocalDateTime>()

        every { studentRepository.findByEmail(email) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { studentRepository.findById(studentId) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { passwordResetCodeGenerator.generate() } returns resetCode
        every {
            passwordResetCodeRepository.issueForStudent(
                studentId,
                resetCode,
                capture(expiresAt),
                capture(createdAt),
                0,
                bojId
            )
        } answers {
            issuedCode(
                studentId = studentId,
                resetCode = resetCode,
                expiresAt = arg(2),
                createdAt = arg(3)
            )
        }

        // when
        authService.findPassword(email, bojId)

        // then
        assertThat(expiresAt).hasSize(1)
        assertThat(createdAt).hasSize(1)
        assertThat(expiresAt.single()).isEqualTo(createdAt.single().plusMinutes(30))
        verify(exactly = 1) { passwordResetCodeGenerator.generate() }
        verify(exactly = 1) {
            passwordResetCodeRepository.issueForStudent(studentId, resetCode, any(), any(), 0, bojId)
        }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { studentRepository.save(any()) }
        verify(exactly = 1) {
            emailService.sendTemplateEmail(
                to = email,
                subject = "[디딤로그] 비밀번호 재설정",
                templateName = "mail/find-password",
                variables = match {
                    it["nickname"] == "testuser" &&
                    it["email"] == email &&
                    it["bojId"] == bojId &&
                    it["resetCode"] == resetCode
                }
            )
        }
    }

    @Test
    @DisplayName("findPassword는 잠금 안에서 다시 조회한 최신 자격 증명 버전과 BOJ ID로 코드를 발급한다")
    fun `비밀번호 찾기는 최신 자격 증명 상태를 코드에 저장한다`() {
        val email = "test@example.com"
        val bojId = "testuser"
        val studentId = "student-id"
        val resetCode = "RESET001"
        val snapshotStudent = passwordStudent(studentId, email, bojId).copy(credentialVersion = 2)
        val latestStudent = snapshotStudent.copy(
            nickname = Nickname("latestuser"),
            credentialVersion = 3
        )

        every { studentRepository.findByEmail(email) } returns Optional.of(snapshotStudent)
        every { studentRepository.findById(studentId) } returns Optional.of(latestStudent)
        every { passwordResetCodeGenerator.generate() } returns resetCode
        every {
            passwordResetCodeRepository.issueForStudent(
                studentId,
                resetCode,
                any(),
                any(),
                latestStudent.credentialVersion,
                bojId
            )
        } answers {
            issuedCode(
                studentId = studentId,
                resetCode = resetCode,
                expiresAt = arg(2),
                createdAt = arg(3),
                credentialVersion = arg(4),
                bojId = arg(5)
            )
        }

        authService.findPassword(email, bojId)

        verify(exactly = 1) {
            passwordResetCodeRepository.issueForStudent(
                studentId,
                resetCode,
                any(),
                any(),
                latestStudent.credentialVersion,
                bojId
            )
        }
        verify(exactly = 1) {
            emailService.sendTemplateEmail(
                email,
                "[디딤로그] 비밀번호 재설정",
                "mail/find-password",
                match { it["nickname"] == latestStudent.nickname.value }
            )
        }
    }

    @Test
    @DisplayName("findPassword는 resetCode 유일 인덱스 충돌 시 새 코드로 재시도한다")
    fun `resetCode 충돌 후 새 코드를 생성해 발급한다`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val studentId = "student-id"
        val firstResetCode = "RESET001"
        val secondResetCode = "RESET002"
        val attemptedCodes = mutableListOf<String>()
        var attempts = 0

        every { studentRepository.findByEmail(email) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { studentRepository.findById(studentId) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { passwordResetCodeGenerator.generate() } returnsMany
            listOf(firstResetCode, secondResetCode)
        every {
            passwordResetCodeRepository.issueForStudent(studentId, any(), any(), any(), 0, bojId)
        } answers {
            attempts += 1
            val candidate = arg<String>(1)
            attemptedCodes += candidate
            if (attempts == 1) {
                throw duplicateKey(MongoIndexInitializer.PASSWORD_RESET_CODE_UNIQUE_INDEX_NAME)
            }
            issuedCode(
                studentId = studentId,
                resetCode = candidate,
                expiresAt = arg(2),
                createdAt = arg(3)
            )
        }

        // when
        authService.findPassword(email, bojId)

        // then
        assertThat(attemptedCodes).containsExactly(firstResetCode, secondResetCode)
        verify(exactly = 2) { passwordResetCodeGenerator.generate() }
        verify(exactly = 1) {
            emailService.sendTemplateEmail(
                email,
                "[디딤로그] 비밀번호 재설정",
                "mail/find-password",
                match { it["resetCode"] == secondResetCode }
            )
        }
    }

    @Test
    @DisplayName("findPassword는 studentId 유일 인덱스 충돌 시 같은 후보로 재시도한다")
    fun `studentId 충돌 후 생성 코드를 바꾸지 않고 재시도한다`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val studentId = "student-id"
        val resetCode = "RESET001"
        val attemptedCodes = mutableListOf<String>()
        val attemptedExpiresAt = mutableListOf<LocalDateTime>()
        val attemptedCreatedAt = mutableListOf<LocalDateTime>()
        var attempts = 0

        every { studentRepository.findByEmail(email) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { studentRepository.findById(studentId) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { passwordResetCodeGenerator.generate() } returns resetCode
        every {
            passwordResetCodeRepository.issueForStudent(studentId, any(), any(), any(), 0, bojId)
        } answers {
            attempts += 1
            val candidate = arg<String>(1)
            val expiresAt = arg<LocalDateTime>(2)
            val createdAt = arg<LocalDateTime>(3)
            attemptedCodes += candidate
            attemptedExpiresAt += expiresAt
            attemptedCreatedAt += createdAt
            if (attempts == 1) {
                throw duplicateCommandKey("studentId")
            }
            issuedCode(studentId, candidate, expiresAt, createdAt)
        }

        // when
        authService.findPassword(email, bojId)

        // then
        assertThat(attemptedCodes).containsExactly(resetCode, resetCode)
        assertThat(attemptedExpiresAt).hasSize(2).allMatch { it == attemptedExpiresAt.first() }
        assertThat(attemptedCreatedAt).hasSize(2).allMatch { it == attemptedCreatedAt.first() }
        verify(exactly = 1) { passwordResetCodeGenerator.generate() }
        verify(exactly = 1) {
            emailService.sendTemplateEmail(
                email,
                "[디딤로그] 비밀번호 재설정",
                "mail/find-password",
                match { it["resetCode"] == resetCode }
            )
        }
    }

    @Test
    @DisplayName("findPassword는 코드 충돌 재시도를 모두 소진하면 메일을 보내지 않는다")
    fun `코드 발급 최대 시도 소진 시 메일을 보내지 않는다`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val studentId = "student-id"

        every { studentRepository.findByEmail(email) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { studentRepository.findById(studentId) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { passwordResetCodeGenerator.generate() } returnsMany
            listOf("RESET001", "RESET002", "RESET003", "RESET004", "RESET005")
        every {
            passwordResetCodeRepository.issueForStudent(studentId, any(), any(), any(), 0, bojId)
        } throws duplicateKey(MongoIndexInitializer.PASSWORD_RESET_CODE_UNIQUE_INDEX_NAME)

        // when
        val exception = assertThrows<BusinessException> {
            authService.findPassword(email, bojId)
        }

        // then
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR)
        assertThat(exception.message).contains("비밀번호 재설정 코드를 생성하지 못했습니다.")
        verify(exactly = 5) { passwordResetCodeGenerator.generate() }
        verify(exactly = 5) {
            passwordResetCodeRepository.issueForStudent(studentId, any(), any(), any(), 0, bojId)
        }
        verify(exactly = 0) { emailService.sendTemplateEmail(any(), any(), any(), any()) }
        verify(exactly = 0) { passwordResetCodeRepository.deleteIssuedCode(any(), any()) }
    }

    @Test
    @DisplayName("findPassword는 메일 발송 실패 시 자신이 발급한 코드만 조건부 삭제한다")
    fun `메일 실패 시 발급 코드를 조건부 삭제한다`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val studentId = "student-id"
        val resetCode = "RESET001"
        val mailException = IllegalStateException("mail send failed")

        every { studentRepository.findByEmail(email) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { studentRepository.findById(studentId) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { passwordResetCodeGenerator.generate() } returns resetCode
        every {
            passwordResetCodeRepository.issueForStudent(studentId, resetCode, any(), any(), 0, bojId)
        } answers {
            issuedCode(studentId, resetCode, arg(2), arg(3))
        }
        every { emailService.sendTemplateEmail(any(), any(), any(), any()) } throws mailException
        every { passwordResetCodeRepository.deleteIssuedCode(studentId, resetCode) } returns true

        // when
        val thrown = assertThrows<IllegalStateException> {
            authService.findPassword(email, bojId)
        }

        // then
        assertThat(thrown).isSameAs(mailException)
        verify(exactly = 1) {
            passwordResetCodeRepository.deleteIssuedCode(studentId, resetCode)
        }
    }

    @Test
    @DisplayName("findPassword는 조건부 삭제 실패가 원래 메일 예외를 가리지 않게 한다")
    fun `보상 삭제 실패 시 원래 메일 예외를 다시 던진다`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val studentId = "student-id"
        val resetCode = "RESET001"
        val mailException = IllegalStateException("mail send failed")
        val cleanupException = IllegalArgumentException("cleanup failed")

        every { studentRepository.findByEmail(email) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { studentRepository.findById(studentId) } returns
            Optional.of(passwordStudent(studentId, email, bojId))
        every { passwordResetCodeGenerator.generate() } returns resetCode
        every {
            passwordResetCodeRepository.issueForStudent(studentId, resetCode, any(), any(), 0, bojId)
        } answers {
            issuedCode(studentId, resetCode, arg(2), arg(3))
        }
        every { emailService.sendTemplateEmail(any(), any(), any(), any()) } throws mailException
        every {
            passwordResetCodeRepository.deleteIssuedCode(studentId, resetCode)
        } throws cleanupException

        // when
        val thrown = assertThrows<IllegalStateException> {
            authService.findPassword(email, bojId)
        }

        // then
        assertThat(thrown).isSameAs(mailException)
        assertThat(thrown.suppressed).containsExactly(cleanupException)
        verify(exactly = 1) {
            passwordResetCodeRepository.deleteIssuedCode(studentId, resetCode)
        }
    }

    @Test
    @DisplayName("findPassword는 이메일로 가입된 사용자가 없으면 예외를 발생시킨다")
    fun `비밀번호 찾기 실패 - 사용자 없음`() {
        // given
        val email = "notfound@example.com"
        val bojId = "testuser"
        every { studentRepository.findByEmail(email) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.findPassword(email, bojId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        verify(exactly = 0) { emailService.sendTemplateEmail(any(), any(), any(), any()) }
        verify(exactly = 0) { passwordResetCodeGenerator.generate() }
        verify(exactly = 0) {
            passwordResetCodeRepository.issueForStudent(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    @DisplayName("findPassword는 이메일과 BOJ ID가 일치하지 않으면 예외를 발생시킨다")
    fun `비밀번호 찾기 실패 - 이메일과 BOJ ID 불일치`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val differentBojId = "differentuser"
        val student = Student(
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            email = email,
            bojId = BojId(bojId),
            password = "encodedPassword",
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findByEmail(email) } returns Optional.of(student)

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.findPassword(email, differentBojId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        verify(exactly = 0) { emailService.sendEmail(any(), any(), any()) }
        verify(exactly = 0) { passwordResetCodeGenerator.generate() }
        verify(exactly = 0) {
            passwordResetCodeRepository.issueForStudent(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    @DisplayName("findPassword는 비밀번호가 설정되지 않은 계정이면 예외를 발생시킨다")
    fun `비밀번호 찾기 실패 - 비밀번호 없음`() {
        // given
        val email = "test@example.com"
        val bojId = "testuser"
        val student = Student(
            nickname = Nickname("testuser"),
            provider = Provider.GOOGLE,
            providerId = "google123",
            email = email,
            bojId = BojId(bojId),
            password = null,
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { studentRepository.findByEmail(email) } returns Optional.of(student)

        // when & then
        val exception = assertThrows<BusinessException> {
            authService.findPassword(email, bojId)
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        verify(exactly = 0) { emailService.sendEmail(any(), any(), any()) }
        verify(exactly = 0) { passwordResetCodeGenerator.generate() }
        verify(exactly = 0) {
            passwordResetCodeRepository.issueForStudent(any(), any(), any(), any(), any(), any())
        }
    }

    private fun passwordStudent(studentId: String, email: String, bojId: String): Student {
        return Student(
            id = studentId,
            nickname = Nickname("testuser"),
            provider = Provider.BOJ,
            providerId = bojId,
            email = email,
            bojId = BojId(bojId),
            password = "encodedPassword",
            rating = 1000,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
    }

    private fun issuedCode(
        studentId: String,
        resetCode: String,
        expiresAt: LocalDateTime,
        createdAt: LocalDateTime,
        credentialVersion: Long = 0,
        bojId: String = "testuser"
    ) = com.didimlog.domain.PasswordResetCode(
        resetCode = resetCode,
        studentId = studentId,
        credentialVersion = credentialVersion,
        bojId = bojId,
        expiresAt = expiresAt,
        createdAt = createdAt
    )

    private fun duplicateKey(indexName: String): DuplicateKeyException {
        return DuplicateKeyException("E11000 duplicate key error index: $indexName")
    }

    private fun duplicateCommandKey(field: String): DuplicateKeyException {
        val response = BsonDocument()
            .append("ok", BsonInt32(0))
            .append("code", BsonInt32(11000))
            .append("errmsg", BsonString("duplicate key on compatible legacy index"))
            .append("keyPattern", BsonDocument(field, BsonInt32(1)))
        return DuplicateKeyException(
            "duplicate key without canonical index name",
            MongoCommandException(response, ServerAddress())
        )
    }
}
