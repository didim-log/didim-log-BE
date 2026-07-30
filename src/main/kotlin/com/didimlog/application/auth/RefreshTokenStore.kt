package com.didimlog.application.auth

/**
 * Refresh Token 저장소 인터페이스
 */
interface RefreshTokenStore {
    /**
     * Refresh Token을 저장한다.
     *
     * @param token Refresh Token
     * @param studentId 변경 불가능한 학생 ID
     * @param ttlSeconds TTL (초 단위)
     */
    fun save(token: String, studentId: String, ttlSeconds: Long)

    /**
     * Refresh Token이 서명된 학생의 활성 토큰인지 확인한다.
     *
     * 원자적 교체 직전의 빠른 거절을 위한 조회이며, 최종 유효성은 rotate가 다시 확인한다.
     */
    fun matches(token: String, studentId: String): Boolean

    /**
     * 기존 Refresh Token을 새 Refresh Token으로 교체한다.
     *
     * @param oldToken 기존 Refresh Token
     * @param newToken 새 Refresh Token
     * @param studentId 서명된 기존 토큰의 학생 ID
     * @param ttlSeconds 새 Refresh Token TTL (초 단위)
     * @return 교체에 성공하면 true
     */
    fun rotate(
        oldToken: String,
        newToken: String,
        studentId: String,
        ttlSeconds: Long
    ): Boolean

    /**
     * 사용자의 모든 Refresh Token을 삭제한다.
     *
     * @param studentId 변경 불가능한 학생 ID
     */
    fun deleteByStudentId(studentId: String)
}









