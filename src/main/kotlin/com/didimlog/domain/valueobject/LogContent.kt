package com.didimlog.domain.valueobject

@JvmInline
value class LogContent(val value: String) {
    init {
        require(value.isNotBlank()) { "로그 내용은 필수입니다." }
    }
}










