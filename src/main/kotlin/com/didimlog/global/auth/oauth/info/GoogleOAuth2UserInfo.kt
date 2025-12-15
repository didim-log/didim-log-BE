package com.didimlog.global.auth.oauth.info

import com.didimlog.domain.enums.Provider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode

/**
 * Google OAuth2 사용자 정보를 파싱하는 구현체
 * Google 응답 형식: { "sub": "123456789", "email": "user@gmail.com", "name": "User Name", ... }
 */
class GoogleOAuth2UserInfo(
    private val attributes: Map<String, Any>
) : OAuth2UserInfo {

    override fun getProviderId(): String {
        return attributes["sub"]?.toString()
            ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Google 사용자 ID(sub)를 찾을 수 없습니다.")
    }

    override fun getProvider(): Provider = Provider.GOOGLE

    override fun getEmail(): String? {
        return attributes["email"] as? String
    }

    override fun getName(): String {
        return attributes["name"] as? String
            ?: attributes["email"]?.toString()?.substringBefore("@")
            ?: "Google User"
    }
}

