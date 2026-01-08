package com.didimlog.application.auth.boj

@JvmInline
value class BojVerificationCode(val value: String) {
    init {
        require(value.isNotBlank()) { "인증 코드는 비어 있을 수 없습니다." }
    }
}

/**
 * BOJ 프로필 상태 메시지에서 인증 코드가 "정확히" 포함되는지 판별한다.
 * - 부분 문자열 매칭(예: 1234가 12345에 포함됨)을 방지한다.
 */
class BojVerificationCodeMatcher {

    fun matches(statusMessage: BojProfileStatusMessage, code: BojVerificationCode): Boolean {
        val message = statusMessage.value.trim()
        val target = code.value.trim()

        if (message == target) {
            return true
        }

        val escaped = Regex.escape(target)
        val pattern = Regex("(^|[\\s\\W])$escaped([\\s\\W]|\$)")
        return pattern.containsMatchIn(message)
    }
}





