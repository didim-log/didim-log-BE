package com.didimlog.application.auth.oauth

import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.SolvedAcTierLevel
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Base64
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("OAuth 교환 서비스 테스트")
class OAuthExchangeServiceTest {

    private val exchangeCodeStore: OAuthExchangeCodeStore = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val refreshTokenService: RefreshTokenService = mockk()
    private val service = OAuthExchangeService(
        exchangeCodeStore = exchangeCodeStore,
        studentRepository = studentRepository,
        jwtTokenProvider = jwtTokenProvider,
        refreshTokenService = refreshTokenService,
        exchangeCodeTtlSeconds = 120L
    )

    @Test
    @DisplayName("교환 코드는 URL-safe 256비트 값으로 TTL과 함께 저장한다")
    fun `교환 코드 발급 성공`() {
        val codeSlot = slot<String>()
        every {
            exchangeCodeStore.save(capture(codeSlot), STUDENT_ID, 120L)
        } returns true

        val code = service.issue(STUDENT_ID)

        assertThat(code).isEqualTo(codeSlot.captured)
        assertThat(code).matches("[A-Za-z0-9_-]{43}")
        assertThat(Base64.getUrlDecoder().decode(code)).hasSize(32)
        verify(exactly = 1) {
            exchangeCodeStore.save(code, STUDENT_ID, 120L)
        }
    }

    @Test
    @DisplayName("교환 코드 TTL은 60초 이상 120초 이하만 허용한다")
    fun `교환 코드 TTL 범위 검증`() {
        listOf(59L, 121L).forEach { invalidTtl ->
            assertThrows<IllegalArgumentException> {
                OAuthExchangeService(
                    exchangeCodeStore = exchangeCodeStore,
                    studentRepository = studentRepository,
                    jwtTokenProvider = jwtTokenProvider,
                    refreshTokenService = refreshTokenService,
                    exchangeCodeTtlSeconds = invalidTtl
                )
            }
        }
    }

    @Test
    @DisplayName("유효한 코드는 현재 학생 권한과 프로필로 Access/Refresh Token을 발급한다")
    fun `유효한 코드 교환 성공`() {
        val code = "valid-exchange-code"
        val student = linkedStudent(role = Role.ADMIN)
        every { exchangeCodeStore.consume(code) } returns STUDENT_ID
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student)
        every { jwtTokenProvider.createToken(BOJ_ID, Role.ADMIN.value) } returns "access-token"
        every { refreshTokenService.generateAndSave(BOJ_ID) } returns "refresh-token"

        val result = service.exchange(code)

        assertThat(result.accessToken).isEqualTo("access-token")
        assertThat(result.refreshToken).isEqualTo("refresh-token")
        assertThat(result.rating).isEqualTo(1_500)
        assertThat(result.tier).isEqualTo(Tier.GOLD)
        assertThat(result.tierLevel).isEqualTo(13)
        assertThat(result.provider).isEqualTo(Provider.GITHUB)
        verify(exactly = 1) { exchangeCodeStore.consume(code) }
        verify(exactly = 1) { studentRepository.findById(STUDENT_ID) }
        verify(exactly = 1) { jwtTokenProvider.createToken(BOJ_ID, Role.ADMIN.value) }
        verify(exactly = 1) { refreshTokenService.generateAndSave(BOJ_ID) }
    }

    @Test
    @DisplayName("존재하지 않거나 만료된 코드는 동일 오류로 거절하고 토큰을 발급하지 않는다")
    fun `유효하지 않은 코드 교환 실패`() {
        val code = "invalid-or-expired-code"
        every { exchangeCodeStore.consume(code) } returns null

        val exception = assertThrows<BusinessException> {
            service.exchange(code)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.OAUTH_EXCHANGE_CODE_INVALID)
        assertThat(exception.message).isEqualTo(ErrorCode.OAUTH_EXCHANGE_CODE_INVALID.message)
        verify(exactly = 1) { exchangeCodeStore.consume(code) }
        verify(exactly = 0) { studentRepository.findById(any()) }
        verify(exactly = 0) { jwtTokenProvider.createToken(any(), any()) }
        verify(exactly = 0) { refreshTokenService.generateAndSave(any()) }
    }

    @Test
    @DisplayName("같은 코드는 첫 교환만 성공하고 재사용 시 동일 오류로 거절한다")
    fun `교환 코드 재사용 실패`() {
        val code = "single-use-code"
        val student = linkedStudent(role = Role.USER)
        every { exchangeCodeStore.consume(code) } returnsMany listOf(STUDENT_ID, null)
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student)
        every { jwtTokenProvider.createToken(BOJ_ID, Role.USER.value) } returns "access-token"
        every { refreshTokenService.generateAndSave(BOJ_ID) } returns "refresh-token"

        val firstResult = service.exchange(code)
        val replayException = assertThrows<BusinessException> {
            service.exchange(code)
        }

        assertThat(firstResult.accessToken).isEqualTo("access-token")
        assertThat(replayException.errorCode).isEqualTo(ErrorCode.OAUTH_EXCHANGE_CODE_INVALID)
        assertThat(replayException.message).isEqualTo(ErrorCode.OAUTH_EXCHANGE_CODE_INVALID.message)
        verify(exactly = 2) { exchangeCodeStore.consume(code) }
        verify(exactly = 1) { studentRepository.findById(STUDENT_ID) }
        verify(exactly = 1) { jwtTokenProvider.createToken(BOJ_ID, Role.USER.value) }
        verify(exactly = 1) { refreshTokenService.generateAndSave(BOJ_ID) }
    }

    @Test
    @DisplayName("코드가 가리키는 학생이 GUEST이면 토큰을 발급하지 않는다")
    fun `GUEST 학생 코드 교환 실패`() {
        val code = "guest-student-code"
        every { exchangeCodeStore.consume(code) } returns STUDENT_ID
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(linkedStudent(role = Role.GUEST))

        val exception = assertThrows<BusinessException> {
            service.exchange(code)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.OAUTH_EXCHANGE_CODE_INVALID)
        verify(exactly = 0) { jwtTokenProvider.createToken(any(), any()) }
        verify(exactly = 0) { refreshTokenService.generateAndSave(any()) }
    }

    @Test
    @DisplayName("코드 소비 뒤 학생이 삭제됐으면 토큰을 발급하지 않는다")
    fun `삭제된 학생 코드 교환 실패`() {
        val code = "deleted-student-code"
        every { exchangeCodeStore.consume(code) } returns STUDENT_ID
        every { studentRepository.findById(STUDENT_ID) } returns Optional.empty()

        val exception = assertThrows<BusinessException> {
            service.exchange(code)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.OAUTH_EXCHANGE_CODE_INVALID)
        verify(exactly = 0) { jwtTokenProvider.createToken(any(), any()) }
        verify(exactly = 0) { refreshTokenService.generateAndSave(any()) }
    }

    @Test
    @DisplayName("코드 소비 뒤 BOJ ID 연결이 사라졌으면 토큰을 발급하지 않는다")
    fun `BOJ 미연동 학생 코드 교환 실패`() {
        val code = "unlinked-student-code"
        every { exchangeCodeStore.consume(code) } returns STUDENT_ID
        every {
            studentRepository.findById(STUDENT_ID)
        } returns Optional.of(linkedStudent(role = Role.USER).copy(bojId = null))

        val exception = assertThrows<BusinessException> {
            service.exchange(code)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.OAUTH_EXCHANGE_CODE_INVALID)
        verify(exactly = 0) { jwtTokenProvider.createToken(any(), any()) }
        verify(exactly = 0) { refreshTokenService.generateAndSave(any()) }
    }

    private fun linkedStudent(role: Role): Student {
        return Student(
            id = STUDENT_ID,
            nickname = Nickname("oauth-user"),
            provider = Provider.GITHUB,
            providerId = "github-provider-id",
            bojId = BojId(BOJ_ID),
            rating = 1_500,
            solvedAcTierLevel = SolvedAcTierLevel(13),
            currentTier = Tier.GOLD,
            role = role,
            termsAgreed = true
        )
    }

    companion object {
        private const val STUDENT_ID = "student-id"
        private const val BOJ_ID = "oauth_boj_user"
    }
}
