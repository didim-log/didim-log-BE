package com.didimlog.domain.enums

/**
 * 피드백 유형을 나타내는 Enum
 */
enum class FeedbackType(val value: String) {
    BUG("BUG"), // 버그 리포트
    SUGGESTION("SUGGESTION"); // 건의사항

    companion object {
        fun from(value: String): FeedbackType? {
            return entries.find { it.value == value.uppercase() }
        }
    }
}














