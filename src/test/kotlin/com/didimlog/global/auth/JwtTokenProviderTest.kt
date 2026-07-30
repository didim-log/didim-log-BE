package com.didimlog.global.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

@DisplayName("JwtTokenProvider 테스트")
class JwtTokenProviderTest {

    private val secret = "test-secret-key-for-jwt-token-provider-test-12345678901234567890"
    private val expiration = 3600000L // 1시간
    private val refreshTokenExpiration = 604800000L // 7일
    private val secretKey: SecretKey =
        Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    
    private fun createJwtTokenProvider(): JwtTokenProvider {
        return JwtTokenProvider(secret, expiration, refreshTokenExpiration)
    }

    @Test
    @DisplayName("createToken은 Access Token 용도와 role을 포함한 JWT 토큰을 생성한다")
    fun `토큰 생성 성공`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val subject = "testuser"
        val role = "USER"

        // when
        val token = jwtTokenProvider.createToken(subject, role)
        val claims = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload

        // then
        assertThat(token).isNotNull()
        assertThat(jwtTokenProvider.getSubject(token)).isEqualTo(subject)
        assertThat(jwtTokenProvider.getRole(token)).isEqualTo(role)
        assertThat(jwtTokenProvider.validateToken(token)).isTrue()
        assertThat(jwtTokenProvider.isAccessToken(token)).isTrue()
        assertThat(jwtTokenProvider.isRefreshToken(token)).isFalse()
        assertThat(claims["type"]).isEqualTo("access")
    }

    @Test
    @DisplayName("createToken은 role 정보를 포함한 JWT 토큰을 생성한다")
    fun `role 포함 토큰 생성 성공`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val subject = "testuser"
        val role = "USER"

        // when
        val token = jwtTokenProvider.createToken(subject, role)

        // then
        assertThat(token).isNotNull()
        assertThat(jwtTokenProvider.getSubject(token)).isEqualTo(subject)
        assertThat(jwtTokenProvider.getRole(token)).isEqualTo(role)
        assertThat(jwtTokenProvider.validateToken(token)).isTrue()
    }

    @Test
    @DisplayName("createRefreshToken은 고유한 jti를 포함한 Refresh Token을 생성한다")
    fun `Refresh Token jti 생성`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val subject = "testuser"
        val parser = Jwts.parser()
            .verifyWith(secretKey)
            .build()

        // when
        val tokens = List(20) {
            jwtTokenProvider.createRefreshToken(subject)
        }
        val claims = tokens.map { token ->
            parser.parseSignedClaims(token).payload
        }

        // then
        assertThat(claims.map { it.id })
            .allSatisfy { jti -> assertThat(jti).isNotBlank() }
            .doesNotHaveDuplicates()
        assertThat(tokens).doesNotHaveDuplicates()
        assertThat(claims.map { it.subject }).containsOnly(subject)
        assertThat(claims.map { it["type"] }).containsOnly("refresh")
        assertThat(tokens).allSatisfy { token ->
            assertThat(jwtTokenProvider.isRefreshToken(token)).isTrue()
            assertThat(jwtTokenProvider.isAccessToken(token)).isFalse()
        }
    }

    @Test
    @DisplayName("getSubject는 토큰에서 주체를 추출한다")
    fun `토큰에서 주체 추출 성공`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val subject = "testuser"
        val token = jwtTokenProvider.createToken(subject, "USER")

        // when
        val extractedSubject = jwtTokenProvider.getSubject(token)

        // then
        assertThat(extractedSubject).isEqualTo(subject)
    }

    @Test
    @DisplayName("getRole은 토큰에서 role을 추출한다")
    fun `토큰에서 role 추출 성공`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val subject = "testuser"
        val role = "ADMIN"
        val token = jwtTokenProvider.createToken(subject, role)

        // when
        val extractedRole = jwtTokenProvider.getRole(token)

        // then
        assertThat(extractedRole).isEqualTo(role)
    }

    @Test
    @DisplayName("getRole은 role이 없는 토큰에서 null을 반환한다")
    fun `role 없는 토큰에서 null 반환`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val subject = "testuser"
        val token = createSignedToken(subject, type = "access")

        // when
        val extractedRole = jwtTokenProvider.getRole(token)

        // then
        assertThat(extractedRole).isNull()
    }

    @Test
    @DisplayName("validateToken은 유효한 토큰에 대해 true를 반환한다")
    fun `유효한 토큰 검증 성공`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val subject = "testuser"
        val token = jwtTokenProvider.createToken(subject, "USER")

        // when
        val isValid = jwtTokenProvider.validateToken(token)

        // then
        assertThat(isValid).isTrue()
    }

    @Test
    @DisplayName("isAccessToken은 type이 없는 기존 토큰을 거부한다")
    fun `type 없는 토큰은 Access Token이 아님`() {
        val jwtTokenProvider = createJwtTokenProvider()
        val token = createSignedToken("testuser", role = "USER")

        assertThat(jwtTokenProvider.validateToken(token)).isTrue()
        assertThat(jwtTokenProvider.isAccessToken(token)).isFalse()
    }

    @Test
    @DisplayName("isAccessToken은 type 값의 대소문자를 보정하지 않는다")
    fun `잘못된 type 토큰은 Access Token이 아님`() {
        val jwtTokenProvider = createJwtTokenProvider()
        val token = createSignedToken("testuser", type = "ACCESS", role = "USER")

        assertThat(jwtTokenProvider.validateToken(token)).isTrue()
        assertThat(jwtTokenProvider.isAccessToken(token)).isFalse()
    }

    @Test
    @DisplayName("validateToken은 만료된 토큰에 대해 false를 반환한다")
    fun `만료된 토큰 검증 실패`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val subject = "testuser"
        val expiredSecretKey: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
        val expiredToken = Jwts.builder()
            .subject(subject)
            .issuedAt(Date(System.currentTimeMillis() - 7200000)) // 2시간 전
            .expiration(Date(System.currentTimeMillis() - 3600000)) // 1시간 전 (만료됨)
            .signWith(expiredSecretKey)
            .compact()

        // when
        val isValid = jwtTokenProvider.validateToken(expiredToken)

        // then
        assertThat(isValid).isFalse()
    }

    @Test
    @DisplayName("validateToken은 잘못된 서명의 토큰에 대해 false를 반환한다")
    fun `잘못된 서명 토큰 검증 실패`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val subject = "testuser"
        val wrongSecret = "wrong-secret-key-for-jwt-token-provider-test-12345678901234567890"
        val wrongSecretKey: SecretKey = Keys.hmacShaKeyFor(wrongSecret.toByteArray(StandardCharsets.UTF_8))
        val invalidToken = Jwts.builder()
            .subject(subject)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expiration))
            .signWith(wrongSecretKey)
            .compact()

        // when
        val isValid = jwtTokenProvider.validateToken(invalidToken)

        // then
        assertThat(isValid).isFalse()
    }

    @Test
    @DisplayName("validateToken은 형식이 잘못된 토큰에 대해 false를 반환한다")
    fun `형식이 잘못된 토큰 검증 실패`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val invalidToken = "invalid.token.format"

        // when
        val isValid = jwtTokenProvider.validateToken(invalidToken)

        // then
        assertThat(isValid).isFalse()
    }

    @Test
    @DisplayName("getSubject는 만료된 토큰에서 예외를 발생시킨다")
    fun `만료된 토큰에서 주체 추출 시 예외 발생`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val subject = "testuser"
        val expiredSecretKey: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
        val expiredToken = Jwts.builder()
            .subject(subject)
            .issuedAt(Date(System.currentTimeMillis() - 7200000))
            .expiration(Date(System.currentTimeMillis() - 3600000))
            .signWith(expiredSecretKey)
            .compact()

        // when & then
        // 만료된 토큰에서 getSubject를 호출하면 getClaims()에서 ExpiredJwtException이 발생함
        assertThrows<Exception> {
            jwtTokenProvider.getSubject(expiredToken)
        }
    }

    @Test
    @DisplayName("getSubject는 잘못된 서명의 토큰에서 예외를 발생시킨다")
    fun `잘못된 서명 토큰에서 주체 추출 시 예외 발생`() {
        // given
        val jwtTokenProvider = createJwtTokenProvider()
        val subject = "testuser"
        val wrongSecret = "wrong-secret-key-for-jwt-token-provider-test-12345678901234567890"
        val wrongSecretKey: SecretKey = Keys.hmacShaKeyFor(wrongSecret.toByteArray(StandardCharsets.UTF_8))
        val invalidToken = Jwts.builder()
            .subject(subject)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expiration))
            .signWith(wrongSecretKey)
            .compact()

        // when & then
        assertThrows<Exception> {
            jwtTokenProvider.getSubject(invalidToken)
        }
    }

    private fun createSignedToken(
        subject: String,
        type: String? = null,
        role: String? = null
    ): String {
        val builder = Jwts.builder()
            .subject(subject)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expiration))

        if (type != null) {
            builder.claim("type", type)
        }
        if (role != null) {
            builder.claim("role", role)
        }

        return builder
            .signWith(secretKey)
            .compact()
    }
}













