package com.didimlog.domain.valueobject

import com.didimlog.domain.validation.NicknamePolicy

/**
 * 사용자 닉네임을 나타내는 Value Object
 * 원시값 포장을 통해 타입 안정성을 확보하고 유효성 검사를 수행한다.
 *
 * @property value 닉네임 문자열 (2~12자, 공백 불가)
 */
@JvmInline
value class Nickname(val value: String) {
    init {
        NicknamePolicy.validate(value)
    }
}
