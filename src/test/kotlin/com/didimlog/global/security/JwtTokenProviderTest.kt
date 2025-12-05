package com.didimlog.global.security

import com.didimlog.DidimLogApplication
import com.didimlog.global.auth.JwtTokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [DidimLogApplication::class])
@DisplayName("JwtTokenProvider 테스트")
class JwtTokenProviderTest {

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    @DisplayName("createToken은 사용자 ID를 기반으로 JWT 토큰을 생성한다")
    fun `createToken으로 토큰 생성`() {
        // given
        val userId = "user123"

        // when
        val token = jwtTokenProvider.createToken(userId)

        // then
        assertThat(token).isNotBlank()
        assertThat(token.split(".")).hasSize(3) // JWT는 header.payload.signature 형식
    }

    @Test
    @DisplayName("getSubject는 JWT 토큰에서 사용자 ID를 추출한다")
    fun `getSubject로 사용자 ID 추출`() {
        // given
        val userId = "user123"
        val token = jwtTokenProvider.createToken(userId)

        // when
        val extractedUserId = jwtTokenProvider.getSubject(token)

        // then
        assertThat(extractedUserId).isEqualTo(userId)
    }

    @Test
    @DisplayName("validateToken은 유효한 토큰에 대해 true를 반환한다")
    fun `validateToken으로 유효한 토큰 검증`() {
        // given
        val userId = "user123"
        val token = jwtTokenProvider.createToken(userId)

        // when
        val isValid = jwtTokenProvider.validateToken(token)

        // then
        assertThat(isValid).isTrue()
    }

    @Test
    @DisplayName("validateToken은 잘못된 토큰에 대해 false를 반환한다")
    fun `validateToken으로 잘못된 토큰 검증`() {
        // given
        val invalidToken = "invalid.token.here"

        // when
        val isValid = jwtTokenProvider.validateToken(invalidToken)

        // then
        assertThat(isValid).isFalse()
    }
}
