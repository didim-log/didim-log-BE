package com.didimlog.domain.valueobject

@JvmInline
value class AiReview(val value: String) {
    init {
        require(value.isNotBlank()) { "AI 리뷰는 비어 있을 수 없습니다." }
    }
}










