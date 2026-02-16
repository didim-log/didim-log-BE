package com.didimlog.global.auth.oauth.info

import com.didimlog.domain.enums.Provider
import com.didimlog.global.exception.BusinessException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuth2UserInfoFactory 및 Provider 파서 테스트")
class OAuth2UserInfoFactoryTest {

    @Test
    fun `registrationId 에 따라 올바른 OAuth2UserInfo 구현체를 생성한다`() {
        val google = OAuth2UserInfoFactory.create("google", mapOf("sub" to "g-1"))
        val github = OAuth2UserInfoFactory.create("github", mapOf("id" to 123))
        val naver = OAuth2UserInfoFactory.create("naver", mapOf("response" to mapOf("id" to "n-1")))

        assertThat(google).isInstanceOf(GoogleOAuth2UserInfo::class.java)
        assertThat(github).isInstanceOf(GithubOAuth2UserInfo::class.java)
        assertThat(naver).isInstanceOf(NaverOAuth2UserInfo::class.java)
    }

    @Test
    fun `지원하지 않는 registrationId 는 예외가 발생한다`() {
        assertThatThrownBy {
            OAuth2UserInfoFactory.create("kakao", emptyMap())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("지원하지 않는 소셜 로그인 제공자")
    }

    @Test
    fun `BOJ provider 는 OAuth2 미지원 예외가 발생한다`() {
        assertThatThrownBy {
            OAuth2UserInfoFactory.create("boj", emptyMap())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("BOJ는 OAuth2를 지원하지 않습니다")
    }

    @Test
    fun `GoogleOAuth2UserInfo 는 sub email name 을 파싱한다`() {
        val info = GoogleOAuth2UserInfo(
            mapOf(
                "sub" to "g-123",
                "email" to "user@gmail.com",
                "name" to "Google User"
            )
        )

        assertThat(info.getProviderId()).isEqualTo("g-123")
        assertThat(info.getProvider()).isEqualTo(Provider.GOOGLE)
        assertThat(info.getEmail()).isEqualTo("user@gmail.com")
        assertThat(info.getName()).isEqualTo("Google User")
    }

    @Test
    fun `GoogleOAuth2UserInfo 는 name 이 없으면 email prefix 를 사용한다`() {
        val info = GoogleOAuth2UserInfo(
            mapOf(
                "sub" to "g-123",
                "email" to "fallback@gmail.com"
            )
        )

        assertThat(info.getName()).isEqualTo("fallback")
    }

    @Test
    fun `GoogleOAuth2UserInfo 는 sub 가 없으면 BusinessException`() {
        val info = GoogleOAuth2UserInfo(mapOf("email" to "x@gmail.com"))

        assertThatThrownBy { info.getProviderId() }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("sub")
    }

    @Test
    fun `GithubOAuth2UserInfo 는 id login email 을 파싱한다`() {
        val info = GithubOAuth2UserInfo(
            mapOf(
                "id" to 123456,
                "login" to "octocat",
                "email" to "octo@github.com"
            )
        )

        assertThat(info.getProviderId()).isEqualTo("123456")
        assertThat(info.getProvider()).isEqualTo(Provider.GITHUB)
        assertThat(info.getEmail()).isEqualTo("octo@github.com")
        assertThat(info.getName()).isEqualTo("octocat")
    }

    @Test
    fun `GithubOAuth2UserInfo 는 id 없으면 login 을 providerId 로 사용한다`() {
        val info = GithubOAuth2UserInfo(mapOf("login" to "fallback-login"))

        assertThat(info.getProviderId()).isEqualTo("fallback-login")
    }

    @Test
    fun `GithubOAuth2UserInfo 는 id 와 login 모두 없으면 예외`() {
        val info = GithubOAuth2UserInfo(emptyMap())

        assertThatThrownBy { info.getProviderId() }
            .isInstanceOf(BusinessException::class.java)
            .hasMessageContaining("GitHub 사용자 ID")
    }

    @Test
    fun `NaverOAuth2UserInfo 는 response 내부 필드를 파싱한다`() {
        val info = NaverOAuth2UserInfo(
            mapOf(
                "response" to mapOf(
                    "id" to "n-123",
                    "email" to "user@naver.com",
                    "name" to "Naver User"
                )
            )
        )

        assertThat(info.getProviderId()).isEqualTo("n-123")
        assertThat(info.getProvider()).isEqualTo(Provider.NAVER)
        assertThat(info.getEmail()).isEqualTo("user@naver.com")
        assertThat(info.getName()).isEqualTo("Naver User")
    }

    @Test
    fun `NaverOAuth2UserInfo 는 name 이 없으면 nickname 을 사용한다`() {
        val info = NaverOAuth2UserInfo(
            mapOf(
                "response" to mapOf(
                    "id" to "n-123",
                    "nickname" to "Naver Nick"
                )
            )
        )

        assertThat(info.getName()).isEqualTo("Naver Nick")
    }

    @Test
    fun `NaverOAuth2UserInfo 는 response 가 없으면 예외`() {
        val info = NaverOAuth2UserInfo(emptyMap())

        assertThatThrownBy { info.getProviderId() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("response")
    }
}
