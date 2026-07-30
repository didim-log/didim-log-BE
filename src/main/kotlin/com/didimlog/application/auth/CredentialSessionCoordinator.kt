package com.didimlog.application.auth

/**
 * 같은 학생의 비밀번호 검증·토큰 발급과 비밀번호 변경·세션 폐기를 직렬화한다.
 */
interface CredentialSessionCoordinator {
    /**
     * 작업 실행 중에만 잠금을 유지한다. 작업 완료 뒤 잠금 상태는 결과에 영향을 주지 않는다.
     */
    fun <T> execute(studentId: String, action: () -> T): T

    /**
     * 결과를 반환하기 직전에 잠금 소유권을 다시 확인해야 하는 발급 작업에 사용한다.
     */
    fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T
}
