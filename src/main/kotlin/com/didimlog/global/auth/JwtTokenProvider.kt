package com.didimlog.global.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

/**
 * JWT 토큰 생성 및 검증을 담당하는 컴포넌트
 */
@Component
class JwtTokenProvider(
    @Value("\${app.jwt.secret}")
    private val secret: String,
    @Value("\${app.jwt.expiration}")
    private val expiration: Long,
    @Value("\${app.jwt.refresh-token-expiration}")
    private val refreshTokenExpiration: Long
) {

    data class AccessTokenIdentity(
        val bojId: String,
        val studentId: String,
        val credentialVersion: Long,
        val role: String
    )

    companion object {
        private const val TOKEN_TYPE_CLAIM = "type"
        private const val ACCESS_TOKEN_TYPE = "access"
        private const val REFRESH_TOKEN_TYPE = "refresh"
        private const val CREDENTIAL_VERSION_CLAIM = "credentialVersion"
        private const val STUDENT_ID_CLAIM = "studentId"
    }

    private val secretKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 변경 불가능한 학생 ID와 자격 증명 버전을 포함한 Access Token을 생성한다.
     *
     * @param subject 토큰 발급 시점의 BOJ ID
     * @param studentId 변경 불가능한 학생 ID
     * @param credentialVersion 비밀번호·권한·BOJ ID 변경 시 증가하는 자격 상태 버전
     * @param role 사용자 권한 (USER, ADMIN 등)
     * @return 생성된 JWT 토큰
     */
    fun createToken(
        subject: String,
        studentId: String,
        credentialVersion: Long,
        role: String
    ): String {
        require(studentId.isNotBlank()) { "학생 ID는 비어 있을 수 없습니다." }
        require(credentialVersion >= 0) { "자격 증명 버전은 음수일 수 없습니다." }
        val now = Date()
        val expiryDate = Date(now.time + expiration)

        return Jwts.builder()
            .subject(subject)
            .claim("role", role)
            .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
            .claim(CREDENTIAL_VERSION_CLAIM, credentialVersion)
            .claim(STUDENT_ID_CLAIM, studentId)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey)
            .compact()
    }

    /**
     * JWT 토큰에서 Role을 추출한다.
     *
     * @param token JWT 토큰
     * @return 토큰에 포함된 Role (없으면 null)
     */
    fun getRole(token: String): String? {
        val claims = getClaims(token)
        return claims["role"] as? String
    }

    /**
     * JWT 토큰에서 주체(Subject)를 추출한다.
     *
     * @param token JWT 토큰
     * @return 토큰의 주체 (사용자 ID 또는 BOJ ID)
     */
    fun getSubject(token: String): String {
        val claims = getClaims(token)
        return claims.subject
    }

    /**
     * Refresh Token에 서명된 자격 증명 버전을 반환한다.
     * 버전 도입 전에 발급된 토큰은 0으로 처리한다.
     */
    fun getCredentialVersion(token: String): Long {
        return getCredentialVersionOrNull(token) ?: 0
    }

    /**
     * JWT에 서명된 자격 증명 버전을 반환한다.
     * claim이 없는 기존 토큰은 null을 반환한다.
     */
    fun getCredentialVersionOrNull(token: String): Long? {
        val value = getClaims(token)[CREDENTIAL_VERSION_CLAIM] ?: return null
        return (value as? Number)?.toLong()
            ?: throw IllegalArgumentException("잘못된 자격 증명 버전 claim입니다.")
    }

    /**
     * Refresh Token에 서명된 변경 불가능한 학생 ID를 반환한다.
     * 학생 ID 도입 전에 발급된 토큰은 null을 반환한다.
     */
    fun getStudentId(token: String): String? {
        return (getClaims(token)[STUDENT_ID_CLAIM] as? String)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Access Token의 인증 식별자를 한 번의 서명·만료 검증으로 읽는다.
     * 필수 claim이 없거나 형식이 잘못된 토큰은 null을 반환한다.
     */
    fun getAccessTokenIdentity(token: String): AccessTokenIdentity? {
        return try {
            val claims = getClaims(token)
            if (claims[TOKEN_TYPE_CLAIM] != ACCESS_TOKEN_TYPE) {
                return null
            }

            val bojId = claims.subject?.takeIf { it.isNotBlank() } ?: return null
            val studentId = (claims[STUDENT_ID_CLAIM] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val credentialVersion = (claims[CREDENTIAL_VERSION_CLAIM] as? Number)
                ?.toLong()
                ?.takeIf { it >= 0 }
                ?: return null
            val role = (claims["role"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: return null

            AccessTokenIdentity(
                bojId = bojId,
                studentId = studentId,
                credentialVersion = credentialVersion,
                role = role
            )
        } catch (exception: Exception) {
            null
        }
    }

    /**
     * 변경 불가능한 학생 ID를 소유자로 지정해 Refresh Token을 생성한다.
     *
     * @param subject 토큰 발급 시점의 BOJ ID
     * @param studentId 변경 불가능한 학생 ID
     * @param credentialVersion 비밀번호·권한·BOJ ID 변경 시 증가하는 자격 상태 버전
     * @return 생성된 Refresh Token
     */
    fun createRefreshToken(subject: String, studentId: String, credentialVersion: Long): String {
        require(studentId.isNotBlank()) { "학생 ID는 비어 있을 수 없습니다." }
        require(credentialVersion >= 0) { "자격 증명 버전은 음수일 수 없습니다." }
        val now = Date()
        val expiryDate = Date(now.time + refreshTokenExpiration)

        return Jwts.builder()
            .subject(subject)
            .id(UUID.randomUUID().toString())
            .claim(TOKEN_TYPE_CLAIM, REFRESH_TOKEN_TYPE)
            .claim(CREDENTIAL_VERSION_CLAIM, credentialVersion)
            .claim(STUDENT_ID_CLAIM, studentId)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey)
            .compact()
    }

    /**
     * JWT 토큰의 유효성을 검증한다.
     *
     * @param token JWT 토큰
     * @return 유효하면 true, 그렇지 않으면 false
     */
    fun validateToken(token: String): Boolean {
        return try {
            val claims = getClaims(token)
            !claims.expiration.before(Date())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Refresh Token인지 확인한다.
     *
     * @param token JWT 토큰
     * @return Refresh Token이면 true, 그렇지 않으면 false
     */
    fun isRefreshToken(token: String): Boolean {
        return try {
            val claims = getClaims(token)
            claims[TOKEN_TYPE_CLAIM] == REFRESH_TOKEN_TYPE
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Access Token인지 확인한다.
     *
     * @param token JWT 토큰
     * @return Access Token이면 true, 그렇지 않으면 false
     */
    fun isAccessToken(token: String): Boolean {
        return try {
            val claims = getClaims(token)
            claims[TOKEN_TYPE_CLAIM] == ACCESS_TOKEN_TYPE
        } catch (e: Exception) {
            false
        }
    }

    private fun getClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload
    }
}
