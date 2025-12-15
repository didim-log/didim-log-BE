package com.didimlog.domain.enums

/**
 * 피드백 처리 상태를 나타내는 Enum
 */
enum class FeedbackStatus(val value: String) {
    PENDING("PENDING"), // 접수 (대기 중)
    COMPLETED("COMPLETED"); // 완료

    companion object {
        fun from(value: String): FeedbackStatus? {
            return entries.find { it.value == value.uppercase() }
        }
    }
}

