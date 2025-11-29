package com.didimlog.domain.valueobject

/**
 * 백준 온라인 저지(BOJ) 사용자 ID를 나타내는 Value Object
 * 원시값 포장을 통해 타입 안정성을 확보하고 유효성 검사를 수행한다.
 *
 * @property value BOJ ID 문자열 (영문자, 숫자, 언더스코어만 허용)
 */
@JvmInline
value class BojId(val value: String) {
    init {
        require(value.matches(Regex("^[a-zA-Z0-9_]+$"))) { "유효하지 않은 BOJ ID 형식입니다." }
    }
}
