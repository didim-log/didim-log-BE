package com.didimlog.application.student

/**
 * 같은 학생의 계정 삭제와 사용자 소유 데이터 쓰기가 동시에 실행되지 않게 한다.
 */
interface StudentLifecycleCoordinator {

    /**
     * 작업 실행 중에만 학생별 잠금을 유지하며, 이미 잠겨 있으면 재시도 가능한 충돌로 거절한다.
     */
    fun <T> execute(studentId: String, action: () -> T): T
}
