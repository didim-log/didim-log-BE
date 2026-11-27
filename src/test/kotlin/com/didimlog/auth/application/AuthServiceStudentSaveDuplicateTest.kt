package com.didimlog.auth.application

import com.didimlog.auth.application.boj.BojOwnershipVerificationService
import com.didimlog.auth.domain.repository.PasswordResetCodeRepository
import com.didimlog.global.security.jwt.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.util.PasswordValidator
import com.didimlog.infra.email.EmailService
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import com.didimlog.student.domain.BojId
import com.didimlog.student.domain.Student
import com.didimlog.student.domain.repository.StudentRepository
import com.mongodb.MongoWriteException
import com.mongodb.ServerAddress
import com.mongodb.WriteError
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

@DisplayName("BOJ 계정 저장 중 MongoDB 쓰기 오류 처리 테스트")
class AuthServiceStudentSaveDuplicateTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val authService = AuthService(
        solvedAcClient,
        studentRepository,
        mockk<JwtTokenProvider>(),
        passwordEncoder,
        mockk<EmailService>(),
        mockk<PasswordResetCodeRepository>(),
        mockk<PasswordResetCodeGenerator>(relaxed = true),
        mockk<RefreshTokenService>(),
        mockk<BojOwnershipVerificationService>(relaxed = true),
        mockk<CredentialSessionCoordinator>()
    )

    @BeforeEach
    fun setUp() {
        mockkObject(PasswordValidator)
        every { PasswordValidator.validate(PASSWORD) } returns Unit
        every { solvedAcClient.fetchUser(BojId(BOJ_ID)) } returns SolvedAcUserResponse(
            handle = BOJ_ID,
            rating = 1500,
            tier = 15
        )
        every { studentRepository.findByBojId(BojId(BOJ_ID)) } returns Optional.empty()
        every { studentRepository.findByEmail(EMAIL) } returns Optional.empty()
        every { passwordEncoder.encode(PASSWORD) } returns "encoded-password"
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(PasswordValidator)
    }

    @Test
    @DisplayName("중복 키 쓰기 오류가 이메일 키 패턴이면 이메일 중복으로 응답한다")
    fun `duplicate email write error`() {
        every { studentRepository.save(any<Student>()) } throws writeException(11000, keyPatternField = "email")

        val exception = assertThrows<BusinessException> {
            authService.createSuperAdmin(BOJ_ID, PASSWORD, EMAIL)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).isEqualTo("이미 사용 중인 이메일입니다.")
    }

    @Test
    @DisplayName("키 패턴을 알 수 없는 중복 키 쓰기 오류는 BOJ ID 중복으로 응답한다")
    fun `duplicate write error without key pattern`() {
        every { studentRepository.save(any<Student>()) } throws writeException(11000)

        val exception = assertThrows<BusinessException> {
            authService.createSuperAdmin(BOJ_ID, PASSWORD, EMAIL)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).isEqualTo("이미 가입된 BOJ ID입니다. bojId=$BOJ_ID")
    }

    @Test
    @DisplayName("중복 키가 아닌 쓰기 오류는 그대로 다시 던진다")
    fun `other write error is rethrown`() {
        val writeException = writeException(121)
        every { studentRepository.save(any<Student>()) } throws writeException

        val exception = assertThrows<MongoWriteException> {
            authService.createSuperAdmin(BOJ_ID, PASSWORD, EMAIL)
        }

        assertThat(exception).isSameAs(writeException)
    }

    private fun writeException(code: Int, keyPatternField: String? = null): MongoWriteException {
        val details = BsonDocument()
        if (keyPatternField != null) {
            details.append("keyPattern", BsonDocument(keyPatternField, BsonInt32(1)))
        }
        return MongoWriteException(WriteError(code, "write error", details), ServerAddress(), emptySet())
    }

    companion object {
        private const val BOJ_ID = "admin123"
        private const val PASSWORD = "password123!"
        private const val EMAIL = "admin@example.com"
    }
}
