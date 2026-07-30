package com.didimlog.application.auth

import com.didimlog.application.student.StudentLifecycleCoordinator

/**
 * 같은 학생의 비밀번호 검증·토큰 발급과 비밀번호 변경·세션 폐기를 직렬화한다.
 *
 * 기존 인증 서비스의 타입 이름을 유지하면서 계정 생명주기 잠금을 함께 사용한다.
 */
interface CredentialSessionCoordinator : StudentLifecycleCoordinator {

    /**
     * 결과를 반환하기 직전에 잠금 소유권을 다시 확인해야 하는 발급 작업에 사용한다.
     */
    fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T
}
