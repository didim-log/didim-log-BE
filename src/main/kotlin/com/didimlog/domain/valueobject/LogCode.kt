package com.didimlog.domain.valueobject

@JvmInline
value class LogCode(val value: String) {
    init {
        require(value.isNotBlank()) { "로그 코드는 필수입니다." }
        require(value.length <= 5000) { "로그 코드는 5000자 이하여야 합니다." }
    }
}












