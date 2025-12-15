package com.didimlog.application.auth.boj

/**
 * BOJ 소유권 인증 코드 저장소
 * - sessionId 단위로 코드를 저장한다.
 * - TTL이 있는 저장소(Redis 등)를 권장한다.
 */
interface BojVerificationCodeStore {

    fun save(sessionId: String, code: String, ttlSeconds: Long)

    fun find(sessionId: String): String?

    fun delete(sessionId: String)
}

