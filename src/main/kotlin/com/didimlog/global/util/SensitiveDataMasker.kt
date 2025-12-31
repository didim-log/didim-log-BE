package com.didimlog.global.util

/**
 * 민감 정보 마스킹 유틸리티
 *
 * - 로그/감사(Audit) 용도로만 사용한다.
 * - Response DTO에는 민감 정보를 넣지 않는 것을 우선한다.
 */
object SensitiveDataMasker {

    fun maskEmail(email: String): String {
        val trimmed = email.trim()
        val atIndex = trimmed.indexOf('@')
        if (atIndex <= 0) {
            return "***"
        }

        val local = trimmed.substring(0, atIndex)
        val domain = trimmed.substring(atIndex + 1)
        val maskedLocal = maskKeepingFirst(local)
        if (domain.isBlank()) {
            return "$maskedLocal@***"
        }

        return "$maskedLocal@$domain"
    }

    fun maskId(value: String): String {
        return maskKeepingFirst(value.trim())
    }

    private fun maskKeepingFirst(value: String): String {
        if (value.isBlank()) {
            return "***"
        }
        if (value.length == 1) {
            return "*"
        }
        return value.first() + "***"
    }
}


