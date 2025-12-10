package com.didimlog.global.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

/**
 * JWT 토큰 생성 및 검증을 담당하는 컴포넌트
 */
@Component
class JwtTokenProvider(
    @Value("\${app.jwt.secret}")
    private val secret: String,
    @Value("\${app.jwt.expiration}")
    private val expiration: Long
) {

    private val secretKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * JWT 토큰을 생성한다.
     *
     * @param subject 토큰의 주체 (보통 사용자 ID 또는 BOJ ID)
     * @return 생성된 JWT 토큰
     */
    fun createToken(subject: String): String {
        val now = Date()
        val expiryDate = Date(now.time + expiration)

        return Jwts.builder()
            .subject(subject)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(secretKey)
            .compact()
    }

    /**
     * 사용자 ID와 Role을 기반으로 JWT 토큰을 생성한다.
     * Role 정보를 Payload에 포함시켜 권한 기반 접근 제어에 사용한다.
     *
     * @param subject 토큰의 주체 (보통 사용자 ID 또는 BOJ ID)
     * @param role 사용자 권한 (USER, ADMIN 등)
     * @return 생성된 JWT 토큰
     */
    fun createToken(subject: String, role: String): String {
        val now = Date()
        val expiryDate = Date(now.time + expiration)

        return Jwts.builder()
            .subject(subject)
            .claim("role", role)
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

    private fun getClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload
    }
}
