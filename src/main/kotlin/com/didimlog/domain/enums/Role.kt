package com.didimlog.domain.enums

/**
 * 사용자 권한을 나타내는 Enum
 * GUEST: 소셜 로그인만 완료하고 BOJ 인증을 하지 않은 상태
 * USER: BOJ 인증을 완료한 정회원
 * ADMIN: 관리자 권한 (크롤링, 유저 관리 등)
 */
enum class Role(val value: String) {
    GUEST("GUEST"),
    USER("USER"),
    ADMIN("ADMIN");

    companion object {
        /**
         * 문자열 값을 받아서 해당하는 Role을 반환한다.
         *
         * @param value Role 문자열 값 (대문자)
         * @return 해당하는 Role 또는 null (없는 경우)
         */
        fun from(value: String): Role? {
            return entries.find { it.value == value.uppercase() }
        }
    }
}
