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
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("RefreshTokenService 테스트")
class RefreshTokenServiceTest {

    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val refreshTokenStore: RefreshTokenStore = mockk()
    private val studentRepository: StudentRepository = mockk()
    private val credentialSessionCoordinator = RecordingCredentialSessionCoordinator()

    private val refreshTokenService = RefreshTokenService(
        jwtTokenProvider = jwtTokenProvider,
        refreshTokenStore = refreshTokenStore,
        studentRepository = studentRepository,
        credentialSessionCoordinator = credentialSessionCoordinator,
        refreshTokenExpiration = 604800000L
    )

    @Test
    @DisplayName("저장된 Student를 학생 ID 소유자로 사용해 Refresh Token을 발급한다")
    fun `Student 기반 Refresh Token 생성 성공`() {
        val student = student(STUDENT_ID, "test123")
        val refreshToken = "refresh-token"
        every {
            jwtTokenProvider.createRefreshToken("test123", STUDENT_ID, 0)
        } returns refreshToken
        every { refreshTokenStore.save(refreshToken, STUDENT_ID, 604800L) } returns Unit

        assertThat(refreshTokenService.generateAndSave(student)).isEqualTo(refreshToken)

        assertThat(credentialSessionCoordinator.strictStudentIds).isEmpty()
        verify(exactly = 0) { studentRepository.findByBojId(BojId("test123")) }
    }

    @Test
    @DisplayName("refresh는 학생 ID로 사용자를 조회해 새 토큰으로 교체한다")
    fun `토큰 갱신 성공`() {
        val oldRefreshToken = "old-refresh-token"
        val bojId = "test123"
        val newAccessToken = "new-access-token"
        val newRefreshToken = "new-refresh-token"
        val student = student(STUDENT_ID, bojId)

        every { jwtTokenProvider.validateToken(oldRefreshToken) } returns true
        every { jwtTokenProvider.isRefreshToken(oldRefreshToken) } returns true
        every { jwtTokenProvider.getSubject(oldRefreshToken) } returns bojId
        every { jwtTokenProvider.getStudentId(oldRefreshToken) } returns STUDENT_ID
        every { refreshTokenStore.matches(oldRefreshToken, STUDENT_ID) } returns true
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student)
        every { jwtTokenProvider.getCredentialVersion(oldRefreshToken) } returns 0
        every {
            jwtTokenProvider.createToken(bojId, STUDENT_ID, student.credentialVersion, Role.USER.value)
        } returns newAccessToken
        every {
            jwtTokenProvider.createRefreshToken(bojId, STUDENT_ID, 0)
        } returns newRefreshToken
        every {
            refreshTokenStore.rotate(
                oldToken = oldRefreshToken,
                newToken = newRefreshToken,
                studentId = STUDENT_ID,
                ttlSeconds = 604800L
            )
        } returns true

        val result = refreshTokenService.refresh(oldRefreshToken)

        assertThat(result.accessToken).isEqualTo(newAccessToken)
        assertThat(result.refreshToken).isEqualTo(newRefreshToken)
        assertThat(result.rating).isEqualTo(student.rating)
        assertThat(result.tier).isEqualTo(student.tier())
        assertThat(result.tierLevel).isEqualTo(student.solvedAcTierLevel.value)
        assertThat(credentialSessionCoordinator.strictStudentIds).containsExactly(STUDENT_ID)
        verify(exactly = 1) { studentRepository.findById(STUDENT_ID) }
        verify(exactly = 0) { studentRepository.findByBojId(BojId(bojId)) }
    }

    @Test
    @DisplayName("학생 잠금에 실패하면 Refresh Token 저장소를 조회하거나 교체하지 않는다")
    fun `학생 잠금 실패 시 토큰 회전 중단`() {
        val refreshToken = "lock-conflict-token"
        every { jwtTokenProvider.validateToken(refreshToken) } returns true
        every { jwtTokenProvider.isRefreshToken(refreshToken) } returns true
        every { jwtTokenProvider.getSubject(refreshToken) } returns "test123"
        every { jwtTokenProvider.getStudentId(refreshToken) } returns STUDENT_ID
        val rejectingService = RefreshTokenService(
            jwtTokenProvider = jwtTokenProvider,
            refreshTokenStore = refreshTokenStore,
            studentRepository = studentRepository,
            credentialSessionCoordinator = RejectingCredentialSessionCoordinator(),
            refreshTokenExpiration = 604800000L
        )

        val exception = assertThrows<BusinessException> {
            rejectingService.refresh(refreshToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
        verify(exactly = 0) { refreshTokenStore.matches(any(), any()) }
        verify(exactly = 0) { studentRepository.findById(any()) }
        verify(exactly = 0) { refreshTokenStore.rotate(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("refresh는 유효하지 않은 Refresh Token을 거절한다")
    fun `유효하지 않은 Refresh Token으로 갱신 실패`() {
        val invalidRefreshToken = "invalid-token"
        every { jwtTokenProvider.validateToken(invalidRefreshToken) } returns false

        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(invalidRefreshToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("유효하지 않은 Refresh Token")
        verify(exactly = 0) { jwtTokenProvider.getSubject(any()) }
        verify(exactly = 0) { jwtTokenProvider.getStudentId(any()) }
        verify(exactly = 0) { refreshTokenStore.matches(any(), any()) }
        verify(exactly = 0) { refreshTokenStore.rotate(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("refresh는 Access Token을 거절한다")
    fun `Access Token으로 갱신 시도 실패`() {
        val accessToken = "access-token"
        every { jwtTokenProvider.validateToken(accessToken) } returns true
        every { jwtTokenProvider.isRefreshToken(accessToken) } returns false

        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(accessToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("Refresh Token이 아닙니다")
        verify(exactly = 0) { jwtTokenProvider.getSubject(any()) }
        verify(exactly = 0) { jwtTokenProvider.getStudentId(any()) }
        verify(exactly = 0) { refreshTokenStore.matches(any(), any()) }
    }

    @Test
    @DisplayName("studentId claim이 없는 기존 Refresh Token은 거절한다")
    fun `학생 ID 없는 기존 Refresh Token 갱신 실패`() {
        val refreshToken = "legacy-refresh-token"
        every { jwtTokenProvider.validateToken(refreshToken) } returns true
        every { jwtTokenProvider.isRefreshToken(refreshToken) } returns true
        every { jwtTokenProvider.getSubject(refreshToken) } returns "test123"
        every { jwtTokenProvider.getStudentId(refreshToken) } returns null

        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(refreshToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("학생 ID가 없는 기존 Refresh Token")
        verify(exactly = 0) { refreshTokenStore.matches(any(), any()) }
        verify(exactly = 0) { studentRepository.findById(any()) }
    }

    @Test
    @DisplayName("Redis에 존재하지 않는 Refresh Token은 거절한다")
    fun `존재하지 않는 Refresh Token으로 갱신 실패`() {
        val refreshToken = "refresh-token"
        every { jwtTokenProvider.validateToken(refreshToken) } returns true
        every { jwtTokenProvider.isRefreshToken(refreshToken) } returns true
        every { jwtTokenProvider.getSubject(refreshToken) } returns "test123"
        every { jwtTokenProvider.getStudentId(refreshToken) } returns STUDENT_ID
        every { refreshTokenStore.matches(refreshToken, STUDENT_ID) } returns false

        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(refreshToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("Refresh Token이 존재하지 않습니다")
        verify(exactly = 0) { studentRepository.findById(any()) }
        verify(exactly = 0) { refreshTokenStore.rotate(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("토큰 학생이 삭제되고 BOJ ID가 재사용되어도 새 소유자로 회전하지 않는다")
    fun `삭제된 학생 토큰은 동일 BOJ ID의 새 학생에게 이전되지 않음`() {
        val refreshToken = "deleted-owner-token"
        val replacement = student("student-b", "foo")
        every { jwtTokenProvider.validateToken(refreshToken) } returns true
        every { jwtTokenProvider.isRefreshToken(refreshToken) } returns true
        every { jwtTokenProvider.getSubject(refreshToken) } returns "foo"
        every { jwtTokenProvider.getStudentId(refreshToken) } returns "student-a"
        every { refreshTokenStore.matches(refreshToken, "student-a") } returns true
        every { studentRepository.findById("student-a") } returns Optional.empty()
        every { studentRepository.findByBojId(BojId("foo")) } returns Optional.of(replacement)

        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(refreshToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.STUDENT_NOT_FOUND)
        verify(exactly = 1) { studentRepository.findById("student-a") }
        verify(exactly = 0) { studentRepository.findByBojId(BojId("foo")) }
        verify(exactly = 0) { jwtTokenProvider.createToken(any(), any(), any(), any()) }
        verify(exactly = 0) { refreshTokenStore.rotate(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("토큰 학생의 BOJ ID가 변경되고 이전 ID가 재사용되어도 새 소유자로 회전하지 않는다")
    fun `변경된 BOJ ID의 기존 토큰은 동일 BOJ ID의 새 학생에게 이전되지 않음`() {
        val refreshToken = "renamed-owner-token"
        val originalOwner = student("student-a", "bar")
        val replacement = student("student-b", "foo")
        every { jwtTokenProvider.validateToken(refreshToken) } returns true
        every { jwtTokenProvider.isRefreshToken(refreshToken) } returns true
        every { jwtTokenProvider.getSubject(refreshToken) } returns "foo"
        every { jwtTokenProvider.getStudentId(refreshToken) } returns "student-a"
        every { refreshTokenStore.matches(refreshToken, "student-a") } returns true
        every { studentRepository.findById("student-a") } returns Optional.of(originalOwner)
        every { studentRepository.findByBojId(BojId("foo")) } returns Optional.of(replacement)

        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(refreshToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("계정 정보가 변경")
        verify(exactly = 0) { studentRepository.findByBojId(BojId("foo")) }
        verify(exactly = 0) { jwtTokenProvider.createToken(any(), any(), any(), any()) }
        verify(exactly = 0) { refreshTokenStore.rotate(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("refresh는 사전 확인 뒤 다른 요청이 먼저 교체하면 실패한다")
    fun `동시 교체에서 뒤 요청 실패`() {
        val refreshToken = "refresh-token"
        val bojId = "test123"
        val student = student(STUDENT_ID, bojId)
        every { jwtTokenProvider.validateToken(refreshToken) } returns true
        every { jwtTokenProvider.isRefreshToken(refreshToken) } returns true
        every { jwtTokenProvider.getSubject(refreshToken) } returns bojId
        every { jwtTokenProvider.getStudentId(refreshToken) } returns STUDENT_ID
        every { refreshTokenStore.matches(refreshToken, STUDENT_ID) } returns true
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student)
        every { jwtTokenProvider.getCredentialVersion(refreshToken) } returns 0
        every {
            jwtTokenProvider.createToken(bojId, STUDENT_ID, student.credentialVersion, Role.USER.value)
        } returns "new-access-token"
        every {
            jwtTokenProvider.createRefreshToken(bojId, STUDENT_ID, 0)
        } returns "new-refresh-token"
        every {
            refreshTokenStore.rotate(
                refreshToken,
                "new-refresh-token",
                STUDENT_ID,
                604800L
            )
        } returns false

        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(refreshToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("Refresh Token이 존재하지 않습니다")
        verify(exactly = 0) {
            refreshTokenStore.save("new-refresh-token", STUDENT_ID, any())
        }
    }

    @Test
    @DisplayName("비밀번호 변경 전 자격 증명 버전으로 발급된 Refresh Token은 거절한다")
    fun `이전 자격 증명 버전 Refresh Token 갱신 실패`() {
        val refreshToken = "old-credential-refresh-token"
        val student = student(STUDENT_ID, "test123").copy(credentialVersion = 1)
        every { jwtTokenProvider.validateToken(refreshToken) } returns true
        every { jwtTokenProvider.isRefreshToken(refreshToken) } returns true
        every { jwtTokenProvider.getSubject(refreshToken) } returns "test123"
        every { jwtTokenProvider.getStudentId(refreshToken) } returns STUDENT_ID
        every { refreshTokenStore.matches(refreshToken, STUDENT_ID) } returns true
        every { studentRepository.findById(STUDENT_ID) } returns Optional.of(student)
        every { jwtTokenProvider.getCredentialVersion(refreshToken) } returns 0

        val exception = assertThrows<BusinessException> {
            refreshTokenService.refresh(refreshToken)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("비밀번호 변경으로 만료")
        verify(exactly = 0) { jwtTokenProvider.createToken(any(), any(), any(), any()) }
        verify(exactly = 0) {
            jwtTokenProvider.createRefreshToken(any(), any(), any())
        }
        verify(exactly = 0) { refreshTokenStore.rotate(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("revokeAllForStudent는 학생의 모든 Refresh Token을 삭제한다")
    fun `학생 Refresh Token 전체 삭제`() {
        every { refreshTokenStore.deleteByStudentId(STUDENT_ID) } returns Unit

        refreshTokenService.revokeAllForStudent(STUDENT_ID)

        assertThat(credentialSessionCoordinator.strictStudentIds).isEmpty()
        verify(exactly = 1) { refreshTokenStore.deleteByStudentId(STUDENT_ID) }
    }

    private fun student(id: String, bojId: String): Student {
        return Student(
            id = id,
            nickname = Nickname("test"),
            provider = Provider.BOJ,
            providerId = bojId,
            bojId = BojId(bojId),
            password = "encoded-password",
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
    }

    private class RecordingCredentialSessionCoordinator : CredentialSessionCoordinator {
        val strictStudentIds = mutableListOf<String>()

        override fun <T> execute(studentId: String, action: () -> T): T = action()

        override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
            strictStudentIds += studentId
            return action()
        }
    }

    private class RejectingCredentialSessionCoordinator : CredentialSessionCoordinator {
        override fun <T> execute(studentId: String, action: () -> T): T = action()

        override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T {
            throw BusinessException(ErrorCode.SESSION_STATE_CONFLICT)
        }
    }

    companion object {
        private const val STUDENT_ID = "student-id"
    }
}
