package com.didimlog.domain.enums

/**
 * 소셜 로그인 제공자(Provider)를 나타내는 Enum
 * OAuth2 인증에 사용되는 다양한 소셜 서비스를 구분한다.
 */
enum class Provider(val value: String) {
    GOOGLE("google"),
    GITHUB("github"),
    NAVER("naver"),
    BOJ("boj"); // 기존 BOJ 로그인 방식

    companion object {
        /**
         * 문자열 값을 받아서 해당하는 Provider를 반환한다.
         *
         * @param value Provider 문자열 값 (소문자)
         * @return 해당하는 Provider 또는 null (없는 경우)
         */
        fun from(value: String): Provider? {
            return entries.find { it.value == value.lowercase() }
        }
    }
}
