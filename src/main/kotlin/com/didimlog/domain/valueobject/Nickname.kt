package com.didimlog.domain.valueobject

/**
 * 사용자 닉네임을 나타내는 Value Object
 * 원시값 포장을 통해 타입 안정성을 확보하고 유효성 검사를 수행한다.
 *
 * @property value 닉네임 문자열 (2~20자, 공백 불가)
 */
@JvmInline
value class Nickname(val value: String) {
    init {
        require(value.isNotBlank()) { "닉네임은 필수입니다." }
        require(value.length in 2..20) { "닉네임은 2자 이상 20자 이하여야 합니다." }
    }
}
