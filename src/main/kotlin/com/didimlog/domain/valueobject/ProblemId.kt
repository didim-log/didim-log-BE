package com.didimlog.domain.valueobject

/**
 * 알고리즘 문제의 식별자를 나타내는 Value Object
 * 원시 문자열을 포장하여 문제 ID 사용 시 타입 안정성과 유효성 검사를 보장한다.
 *
 * @property value 문제 ID 문자열 (공백 불가)
 */
@JvmInline
value class ProblemId(val value: String) {

    init {
        require(value.isNotBlank()) { "문제 ID는 필수입니다." }
    }
}


