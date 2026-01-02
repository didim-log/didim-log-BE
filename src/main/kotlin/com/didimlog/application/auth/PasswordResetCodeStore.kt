package com.didimlog.application.auth

/**
 * 비밀번호 재설정 코드 저장소
 * - resetCode 단위로 studentId를 저장한다.
 * - TTL이 있는 저장소(Redis 등)를 권장한다.
 */
interface PasswordResetCodeStore {

    /**
     * 비밀번호 재설정 코드를 저장한다.
     *
     * @param resetCode 재설정 코드 (키로 사용)
     * @param studentId 학생 ID (값으로 저장)
     * @param ttlSeconds TTL (초)
     */
    fun save(resetCode: String, studentId: String, ttlSeconds: Long)

    /**
     * 재설정 코드로 학생 ID를 조회한다.
     *
     * @param resetCode 재설정 코드
     * @return 학생 ID (없으면 null)
     */
    fun find(resetCode: String): String?

    /**
     * 재설정 코드를 삭제한다.
     *
     * @param resetCode 재설정 코드
     */
    fun delete(resetCode: String)
}




