package com.didimlog.application.auth.oauth

/**
 * OAuth 로그인 교환 코드를 짧은 시간 동안 보관하고 한 번만 소비한다.
 */
interface OAuthExchangeCodeStore {
    fun save(code: String, studentId: String, ttlSeconds: Long): Boolean

    fun consume(code: String): String?
}
