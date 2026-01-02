package com.didimlog.application.auth

/**
 * Refresh Token 저장소 인터페이스
 */
interface RefreshTokenStore {
    /**
     * Refresh Token을 저장한다.
     *
     * @param token Refresh Token
     * @param bojId 사용자 BOJ ID
     * @param ttlSeconds TTL (초 단위)
     */
    fun save(token: String, bojId: String, ttlSeconds: Long)

    /**
     * Refresh Token으로 사용자 BOJ ID를 조회한다.
     *
     * @param token Refresh Token
     * @return 사용자 BOJ ID (없으면 null)
     */
    fun find(token: String): String?

    /**
     * Refresh Token을 삭제한다.
     *
     * @param token Refresh Token
     */
    fun delete(token: String)

    /**
     * 사용자의 모든 Refresh Token을 삭제한다.
     *
     * @param bojId 사용자 BOJ ID
     */
    fun deleteByBojId(bojId: String)
}

