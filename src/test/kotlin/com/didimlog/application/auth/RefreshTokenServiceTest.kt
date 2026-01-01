package com.didimlog.application.auth

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
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

@DisplayName("RefreshTokenService 테스트")
class RefreshTokenServiceTest {

    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val refreshTokenStore: RefreshTokenStore = mockk()
    private val studentRepository: StudentRepository = mockk()

    private val refreshTokenService = RefreshTokenService(
        jwtTokenProvider = jwtTokenProvider,
        refreshTokenStore = refreshTokenStore,
        studentRepository = studentRepository,
        refreshTokenExpiration = 604800000L // 7일
    )

    @Test
    @DisplayName("generateAndSave는 Refresh Token을 생성하고 저장한다")
    fun `Refresh Token 생성 및 저장 성공`() {
        // given
        val bojId = "test123"
        val refreshToken = "refresh-token-123"

        every { jwtTokenProvider.createRefreshToken(bojId) } returns refreshToken
        every { refreshTokenStore.save(refreshToken, bojId, 604800L) } returns Unit

        // when
        val result = refreshTokenService.generateAndSave(bojId)

        // then
        assertThat(result).isEqualTo(refreshToken)
        verify(exactly = 1) { jwtTokenProvider.createRefreshToken(bojId) }
        verify(exactly = 1) { refreshTokenStore.save(refreshToken, bojId, 604800L) }
    }

    @Test
    @DisplayName("refresh는 유효한 Refresh Token으로 새로운 Access Token과 Refresh Token을 발급한다")
    fun `토큰 갱신 성공`() {
        // given
        val oldRefreshToken = "old-refresh-token"
        val bojId = "test123"
        val newAccessToken = "new-access-token"
        val newRefreshToken = "new-refresh-token"

        val student = Student(
            nickname = Nickname("test"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )

        every { jwtTokenProvider.validateToken(oldRefreshToken) } returns true
        every { jwtTokenProvider.isRefreshToken(oldRefreshToken) } returns true
        every { refreshTokenStore.find(oldRefreshToken) } returns bojId
        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.of(student)
        every { refreshTokenStore.delete(oldRefreshToken) } returns Unit
        every { jwtTokenProvider.createToken(bojId, Role.USER.value) } returns newAccessToken
        every { jwtTokenProvider.createRefreshToken(bojId) } returns newRefreshToken
        every { refreshTokenStore.save(newRefreshToken, bojId, 604800L) } returns Unit

        // when
        val result = refreshTokenService.refresh(oldRefreshToken)

        // then
        assertThat(result.first).isEqualTo(newAccessToken)
        assertThat(result.second).isEqualTo(newRefreshToken)
        verify(exactly = 1) { refreshTokenStore.delete(oldRefreshToken) }
        verify(exactly = 1) { refreshTokenStore.save(newRefreshToken, bojId, 604800L) }
    }

    @Test
    @DisplayName("refresh는 유효하지 않은 Refresh Token에 대해 예외를 발생시킨다")
    fun `유효하지 않은 Refresh Token으로 갱신 실패`() {
        // given
        val invalidRefreshToken = "invalid-token"

        every { jwtTokenProvider.validateToken(invalidRefreshToken) } returns false

        // when & then
        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(invalidRefreshToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("유효하지 않은 Refresh Token")
        verify(exactly = 0) { refreshTokenStore.find(any()) }
    }

    @Test
    @DisplayName("refresh는 Access Token에 대해 예외를 발생시킨다")
    fun `Access Token으로 갱신 시도 실패`() {
        // given
        val accessToken = "access-token"

        every { jwtTokenProvider.validateToken(accessToken) } returns true
        every { jwtTokenProvider.isRefreshToken(accessToken) } returns false

        // when & then
        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(accessToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("Refresh Token이 아닙니다")
        verify(exactly = 0) { refreshTokenStore.find(any()) }
    }

    @Test
    @DisplayName("refresh는 존재하지 않는 Refresh Token에 대해 예외를 발생시킨다")
    fun `존재하지 않는 Refresh Token으로 갱신 실패`() {
        // given
        val refreshToken = "refresh-token"

        every { jwtTokenProvider.validateToken(refreshToken) } returns true
        every { jwtTokenProvider.isRefreshToken(refreshToken) } returns true
        every { refreshTokenStore.find(refreshToken) } returns null

        // when & then
        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(refreshToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("Refresh Token이 존재하지 않습니다")
        verify(exactly = 0) { studentRepository.findByBojId(any()) }
    }

    @Test
    @DisplayName("refresh는 사용자를 찾을 수 없으면 예외를 발생시킨다")
    fun `사용자 없음으로 갱신 실패`() {
        // given
        val refreshToken = "refresh-token"
        val bojId = "test123"

        every { jwtTokenProvider.validateToken(refreshToken) } returns true
        every { jwtTokenProvider.isRefreshToken(refreshToken) } returns true
        every { refreshTokenStore.find(refreshToken) } returns bojId
        every { studentRepository.findByBojId(BojId(bojId)) } returns Optional.empty()

        // when & then
        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(refreshToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        assertThat(exception.message).contains("사용자를 찾을 수 없습니다")
        verify(exactly = 0) { refreshTokenStore.delete(any()) }
    }

    @Test
    @DisplayName("revokeAll은 사용자의 모든 Refresh Token을 삭제한다")
    fun `사용자 Refresh Token 전체 삭제`() {
        // given
        val bojId = "test123"

        every { refreshTokenStore.deleteByBojId(bojId) } returns Unit

        // when
        refreshTokenService.revokeAll(bojId)

        // then
        verify(exactly = 1) { refreshTokenStore.deleteByBojId(bojId) }
    }
}

