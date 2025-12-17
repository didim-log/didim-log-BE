package com.didimlog.global.auth.oauth.info

import com.didimlog.domain.enums.Provider

/**
 * OAuth2 소셜 로그인 제공자별 사용자 정보를 표준화하는 인터페이스
 * 각 제공자(Google, GitHub, Naver)의 응답 형식이 다르므로 이를 통일된 형태로 추출한다.
 */
interface OAuth2UserInfo {
    /**
     * 소셜 로그인 제공자의 사용자 고유 ID를 반환한다.
     * @return 제공자별 사용자 ID (Google: sub, GitHub: id/login, Naver: response.id)
     */
    fun getProviderId(): String

    /**
     * 소셜 로그인 제공자를 반환한다.
     * @return Provider enum (GOOGLE, GITHUB, NAVER)
     */
    fun getProvider(): Provider

    /**
     * 사용자 이메일을 반환한다.
     * GitHub의 경우 이메일이 없을 수 있으므로 nullable이다.
     * @return 사용자 이메일 또는 null
     */
    fun getEmail(): String?

    /**
     * 사용자 이름(닉네임)을 반환한다.
     * @return 사용자 이름 또는 기본값
     */
    fun getName(): String
}





