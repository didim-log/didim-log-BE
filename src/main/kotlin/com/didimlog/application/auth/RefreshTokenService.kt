package com.didimlog.application.auth

import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Refresh Token 서비스
 * Refresh Token의 생성, 검증, 회전을 담당한다.
 */
@Service
class RefreshTokenService(
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenStore: RefreshTokenStore,
    private val studentRepository: StudentRepository,
    @Value("\${app.jwt.refresh-token-expiration}")
    private val refreshTokenExpiration: Long
) {

    private val log = LoggerFactory.getLogger(RefreshTokenService::class.java)

    /**
     * Refresh Token을 생성하고 저장한다.
     *
     * @param bojId 사용자 BOJ ID
     * @return 생성된 Refresh Token
     */
    fun generateAndSave(bojId: String): String {
        val refreshToken = jwtTokenProvider.createRefreshToken(bojId)
        val ttlSeconds = refreshTokenExpiration / 1000
        refreshTokenStore.save(refreshToken, bojId, ttlSeconds)
        log.debug("Refresh Token 생성 및 저장 완료: bojId=$bojId")
        return refreshToken
    }

    /**
     * Refresh Token을 검증하고 새로운 Access Token과 Refresh Token을 발급한다 (Token Rotation).
     *
     * @param refreshToken Refresh Token
     * @return 새로운 Access Token과 Refresh Token 쌍
     * @throws BusinessException Refresh Token이 유효하지 않거나 존재하지 않는 경우
     */
    @Transactional
    fun refresh(refreshToken: String): Pair<String, String> {
        // Refresh Token 유효성 검증
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 Refresh Token입니다.")
        }

        if (!jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Refresh Token이 아닙니다.")
        }

        // Redis에서 사용자 정보 조회
        val bojId = refreshTokenStore.find(refreshToken)
            ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Refresh Token이 존재하지 않습니다.")

        // 사용자 존재 확인
        val student = studentRepository.findByBojId(BojId(bojId))
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "사용자를 찾을 수 없습니다. bojId=$bojId")
            }

        // 기존 Refresh Token 삭제 (Token Rotation)
        refreshTokenStore.delete(refreshToken)

        // 새로운 Access Token 생성
        val newAccessToken = jwtTokenProvider.createToken(bojId, student.role.value)

        // 새로운 Refresh Token 생성 및 저장
        val newRefreshToken = generateAndSave(bojId)

        log.info("토큰 갱신 완료: bojId=$bojId")
        return Pair(newAccessToken, newRefreshToken)
    }

    /**
     * 사용자의 모든 Refresh Token을 삭제한다 (로그아웃 시 사용).
     *
     * @param bojId 사용자 BOJ ID
     */
    fun revokeAll(bojId: String) {
        refreshTokenStore.deleteByBojId(bojId)
        log.info("사용자 Refresh Token 전체 삭제 완료: bojId=$bojId")
    }
}

