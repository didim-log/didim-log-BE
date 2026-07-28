package com.didimlog.application.auth.boj

/**
 * BOJ 소유권 인증 코드 저장소
 * - sessionId 단위로 코드를 저장한다.
 * - TTL이 있는 저장소(Redis 등)를 권장한다.
 */
interface BojVerificationCodeStore {

    fun save(sessionId: String, code: String, ttlSeconds: Long)

    fun find(sessionId: String): String?

    /**
     * 값을 조회하면서 같은 연산에서 삭제한다.
     */
    fun consume(sessionId: String): String?

    /**
     * Rate Limiting 요청 횟수를 원자적으로 증가시킨다.
     *
     * @param key Rate Limit 키
     * @param ttlSeconds TTL (초)
     * @return 증가 후 요청 횟수
     */
    fun incrementRateLimitCount(key: String, ttlSeconds: Long): Long
}
