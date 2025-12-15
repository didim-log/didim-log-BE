package com.didimlog.global.auth.oauth.info

import com.didimlog.domain.enums.Provider
import org.springframework.security.oauth2.core.user.OAuth2User

/**
 * Naver OAuth2 사용자 정보를 파싱하는 구현체
 * Naver 응답 형식: { "response": { "id": "abc123", "email": "user@naver.com", "name": "User Name", ... } }
 */
class NaverOAuth2UserInfo(
    private val attributes: Map<String, Any>
) : OAuth2UserInfo {

    private val response: Map<String, Any> by lazy {
        attributes["response"] as? Map<String, Any>
            ?: throw IllegalStateException("Naver 응답(response)을 찾을 수 없습니다.")
    }

    override fun getProviderId(): String {
        return response["id"]?.toString()
            ?: throw IllegalStateException("Naver 사용자 ID를 찾을 수 없습니다.")
    }

    override fun getProvider(): Provider = Provider.NAVER

    override fun getEmail(): String? {
        return response["email"] as? String
    }

    override fun getName(): String {
        return response["name"] as? String
            ?: response["nickname"] as? String
            ?: "Naver User"
    }
}

