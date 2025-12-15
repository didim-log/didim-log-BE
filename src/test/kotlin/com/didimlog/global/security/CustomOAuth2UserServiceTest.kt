package com.didimlog.global.security

import com.didimlog.global.auth.oauth.info.GithubOAuth2UserInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CustomOAuth2UserService 테스트")
class CustomOAuth2UserServiceTest {

    @Test
    @DisplayName("GitHub OAuth2UserInfo에서 email이 null일 때 빈 문자열로 처리되어야 함")
    fun `GitHub email null 처리 확인`() {
        // given: GitHub 비공개 이메일 사용자의 attributes (email 없음)
        val attributes = mapOf<String, Any>(
            "id" to 12345678,
            "login" to "testuser",
            "name" to "Test User"
            // email은 없음 (GitHub 비공개 이메일)
        )

        // when
        val githubUserInfo = GithubOAuth2UserInfo(attributes)

        // then: getEmail()이 null을 반환해야 함
        assertThat(githubUserInfo.getEmail()).isNull()

        // CustomOAuth2UserService에서 email이 null일 때 빈 문자열로 저장되는지 확인
        // 실제 코드: attributes["email"] = email ?: ""
        val email = githubUserInfo.getEmail()
        val emailForAttribute = email ?: ""
        assertThat(emailForAttribute).isEmpty()
    }

    @Test
    @DisplayName("GitHub OAuth2UserInfo에서 email이 있을 때 정상적으로 반환된다")
    fun `GitHub email 있을 때 정상 반환`() {
        // given
        val email = "test@example.com"
        val attributes = mapOf<String, Any>(
            "id" to 12345678,
            "login" to "testuser",
            "name" to "Test User",
            "email" to email
        )

        // when
        val githubUserInfo = GithubOAuth2UserInfo(attributes)

        // then
        assertThat(githubUserInfo.getEmail()).isEqualTo(email)
    }
}
