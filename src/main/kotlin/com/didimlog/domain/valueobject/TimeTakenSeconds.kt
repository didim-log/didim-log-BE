package com.didimlog.domain.valueobject

/**
 * 문제 풀이에 소요된 시간을 초 단위로 표현하는 Value Object
 * 음수 값이 저장되지 않도록 유효성 검사를 수행한다.
 *
 * @property value 풀이 시간(초)
 */
@JvmInline
value class TimeTakenSeconds(val value: Long) {

    init {
        require(value >= 0L) { "풀이 시간은 0초 이상이어야 합니다." }
    }
}
