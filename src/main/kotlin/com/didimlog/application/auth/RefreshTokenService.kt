package com.didimlog.application.auth

import com.didimlog.domain.Student
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Refresh Token 서비스
 * Refresh Token의 생성, 검증, 회전을 담당한다.
 */
@Service
class RefreshTokenService(
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenStore: RefreshTokenStore,
    private val studentRepository: StudentRepository,
    private val credentialSessionCoordinator: CredentialSessionCoordinator,
    @Value("\${app.jwt.refresh-token-expiration}")
    private val refreshTokenExpiration: Long
) {

    private val log = LoggerFactory.getLogger(RefreshTokenService::class.java)

    data class RefreshResult(
        val accessToken: String,
        val refreshToken: String,
        val rating: Int,
        val tier: com.didimlog.domain.enums.Tier,
        val tierLevel: Int
    )

    fun generateAndSave(student: Student): String {
        val studentId = requireNotNull(student.id) {
            "저장된 학생만 Refresh Token을 발급할 수 있습니다."
        }
        val bojId = student.bojId?.value
            ?: throw BusinessException(
                ErrorCode.COMMON_INVALID_INPUT,
                "BOJ ID가 등록되지 않은 계정입니다."
            )
        val refreshToken = jwtTokenProvider.createRefreshToken(
            subject = bojId,
            studentId = studentId,
            credentialVersion = student.credentialVersion
        )
        val ttlSeconds = refreshTokenExpiration / 1000
        refreshTokenStore.save(refreshToken, studentId, ttlSeconds)
        log.debug("Refresh Token 생성 및 저장 완료: studentId=$studentId")
        return refreshToken
    }

    /**
     * Refresh Token을 검증하고 새로운 Access Token과 Refresh Token을 발급한다 (Token Rotation).
     *
     * @param refreshToken Refresh Token
     * @return 새로운 토큰과 사용자 표시 정보
     * @throws BusinessException Refresh Token이 유효하지 않거나 존재하지 않는 경우
     */
    fun refresh(refreshToken: String): RefreshResult {
        // Refresh Token 유효성 검증
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 Refresh Token입니다.")
        }

        if (!jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Refresh Token이 아닙니다.")
        }

        val signedBojId = jwtTokenProvider.getSubject(refreshToken)
        val studentId = jwtTokenProvider.getStudentId(refreshToken)
            ?: throw BusinessException(
                ErrorCode.COMMON_INVALID_INPUT,
                "학생 ID가 없는 기존 Refresh Token은 사용할 수 없습니다."
            )
        return credentialSessionCoordinator.executeWithCompletionCheck(studentId) {
            if (!refreshTokenStore.matches(refreshToken, studentId)) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Refresh Token이 존재하지 않습니다.")
            }

            // 사용자 존재 확인
            val student = studentRepository.findById(studentId)
                .orElseThrow {
                    BusinessException(ErrorCode.STUDENT_NOT_FOUND, "사용자를 찾을 수 없습니다. studentId=$studentId")
                }
            val currentBojId = student.bojId?.value
            if (currentBojId != signedBojId) {
                throw BusinessException(
                    ErrorCode.COMMON_INVALID_INPUT,
                    "계정 정보가 변경되어 만료된 Refresh Token입니다."
                )
            }
            if (jwtTokenProvider.getCredentialVersion(refreshToken) != student.credentialVersion) {
                throw BusinessException(
                    ErrorCode.COMMON_INVALID_INPUT,
                    "비밀번호 변경으로 만료된 Refresh Token입니다."
                )
            }

            val newAccessToken = jwtTokenProvider.createToken(
                subject = currentBojId,
                studentId = studentId,
                credentialVersion = student.credentialVersion,
                role = student.role.value
            )
            val newRefreshToken = jwtTokenProvider.createRefreshToken(
                subject = currentBojId,
                studentId = studentId,
                credentialVersion = student.credentialVersion
            )
            val ttlSeconds = refreshTokenExpiration / 1000

            val rotated = refreshTokenStore.rotate(
                oldToken = refreshToken,
                newToken = newRefreshToken,
                studentId = studentId,
                ttlSeconds = ttlSeconds
            )
            if (!rotated) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Refresh Token이 존재하지 않습니다.")
            }

            log.info("토큰 갱신 완료: studentId=$studentId")
            RefreshResult(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                rating = student.rating,
                tier = student.tier(),
                tierLevel = student.solvedAcTierLevel.value
            )
        }
    }

    /**
     * 사용자의 모든 Refresh Token을 삭제한다 (로그아웃 시 사용).
     *
     * @param studentId 변경 불가능한 학생 ID
     */
    fun revokeAllForStudent(studentId: String) {
        refreshTokenStore.deleteByStudentId(studentId)
        log.info("사용자 Refresh Token 전체 삭제 완료: studentId=$studentId")
    }

}
