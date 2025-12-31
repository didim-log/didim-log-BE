package com.didimlog.global.auth.oauth.info

import com.didimlog.domain.enums.Provider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode

/**
 * GitHub OAuth2 사용자 정보를 파싱하는 구현체
 * GitHub 응답 형식: { "id": 12345678, "login": "username", "email": "user@example.com", "name": "User Name", ... }
 * 
 * **주의사항:**
 * - GitHub은 이메일 정보가 없을 수 있습니다 (프로필에서 이메일을 비공개로 설정한 경우)
 * - id는 숫자 타입이므로 Number로 받아서 String으로 변환합니다
 */
class GithubOAuth2UserInfo(
    private val attributes: Map<String, Any>
) : OAuth2UserInfo {

    override fun getProviderId(): String {
        // GitHub은 id (숫자)를 우선 사용하고, 없으면 login (아이디) 사용
        val id = (attributes["id"] as? Number)?.toString()
            ?: (attributes["id"] as? String)
        val login = attributes["login"] as? String
        
        return id ?: login ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "GitHub 사용자 ID를 찾을 수 없습니다.")
    }

    override fun getProvider(): Provider = Provider.GITHUB

    override fun getEmail(): String? {
        // GitHub은 이메일이 없을 수 있으므로 nullable로 처리
        return attributes["email"] as? String
    }

    override fun getName(): String {
        // GitHub은 login (아이디) 또는 name (실명) 사용
        return attributes["login"] as? String
            ?: attributes["name"] as? String
            ?: "GitHub User"
    }
}

