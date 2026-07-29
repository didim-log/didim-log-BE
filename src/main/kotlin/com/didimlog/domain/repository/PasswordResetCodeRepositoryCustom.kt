package com.didimlog.domain.repository

import com.didimlog.domain.PasswordResetCode
import java.time.LocalDateTime

interface PasswordResetCodeRepositoryCustom {

    /**
     * 학생의 활성 재설정 코드를 새 코드로 교체한다.
     *
     * 학생의 코드가 없으면 새 문서를 만들고, 이미 있으면 같은 문서를 갱신한다.
     *
     * @param studentId 코드 소유 학생 ID
     * @param resetCode 새 재설정 코드
     * @param expiresAt 코드 만료 시각
     * @param createdAt 코드 발급 시각
     * @return 저장된 재설정 코드
     */
    fun issueForStudent(
        studentId: String,
        resetCode: String,
        expiresAt: LocalDateTime,
        createdAt: LocalDateTime
    ): PasswordResetCode

    /**
     * 재설정 코드를 조회와 동시에 삭제한다.
     *
     * @param resetCode 소비할 재설정 코드
     * @return 소비한 코드, 코드가 없으면 null
     */
    fun consumeByResetCode(resetCode: String): PasswordResetCode?

    /**
     * 이번 발급 요청이 저장한 코드가 여전히 활성 코드일 때만 삭제한다.
     *
     * @param studentId 코드 소유 학생 ID
     * @param resetCode 이번 발급 요청에서 저장한 코드
     * @return 코드를 삭제했으면 true, 더 최신 코드로 교체됐거나 코드가 없으면 false
     */
    fun deleteIssuedCode(studentId: String, resetCode: String): Boolean
}
