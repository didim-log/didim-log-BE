package com.didimlog.domain.valueobject

@JvmInline
value class LogTitle(val value: String) {
    init {
        require(value.isNotBlank()) { "로그 제목은 필수입니다." }
    }
}





