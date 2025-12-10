package com.didimlog.global.auth.oauth.info

import com.didimlog.domain.enums.Provider
import org.springframework.security.oauth2.core.user.OAuth2User

/**
 * OAuth2UserInfo 객체를 생성하는 팩토리 클래스
 * registrationId에 따라 적절한 OAuth2UserInfo 구현체를 반환한다.
 */
object OAuth2UserInfoFactory {

    /**
     * registrationId와 OAuth2User attributes를 받아서 적절한 OAuth2UserInfo 구현체를 생성한다.
     *
     * @param registrationId 소셜 로그인 제공자 ID (google, github, naver)
     * @param attributes OAuth2User의 attributes 맵
     * @return 해당 제공자에 맞는 OAuth2UserInfo 구현체
     * @throws IllegalArgumentException 지원하지 않는 제공자인 경우
     */
    fun create(registrationId: String, attributes: Map<String, Any>): OAuth2UserInfo {
        val provider = Provider.from(registrationId)
            ?: throw IllegalArgumentException("지원하지 않는 소셜 로그인 제공자입니다: $registrationId")

        return when (provider) {
            Provider.GOOGLE -> GoogleOAuth2UserInfo(attributes)
            Provider.GITHUB -> GithubOAuth2UserInfo(attributes)
            Provider.NAVER -> NaverOAuth2UserInfo(attributes)
            Provider.BOJ -> throw IllegalArgumentException("BOJ는 OAuth2를 지원하지 않습니다.")
        }
    }
}

