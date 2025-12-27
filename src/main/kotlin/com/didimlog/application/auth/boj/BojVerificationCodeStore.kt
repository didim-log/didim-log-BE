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

    /**
     * Rate Limiting을 위한 요청 횟수를 가져온다.
     *
     * @param key Rate Limit 키 (예: IP 주소 또는 세션 ID)
     * @return 현재 요청 횟수 (키가 없으면 0)
     */
    fun getRateLimitCount(key: String): Long

    /**
     * Rate Limiting 요청 횟수를 증가시킨다.
     *
     * @param key Rate Limit 키
     * @param ttlSeconds TTL (초)
     */
    fun incrementRateLimitCount(key: String, ttlSeconds: Long)
}

