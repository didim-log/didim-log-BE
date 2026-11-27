package com.didimlog.problem.application.collector

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 작업 하나를 실행하는 worker의 소유 정보.
 *
 * worker lease가 꺼져 있으면 attempt와 leaseValue가 null이다.
 * ownershipLost가 true가 되면 이 worker는 더 이상 상태를 갱신하지 않는다.
 */
internal data class JobWorkerContext(
    val attempt: ProblemJobWorkerAttempt?,
    val leaseValue: String?,
    val sourceJob: JobStatusUnifiedResponse,
    val ownershipLost: AtomicBoolean = AtomicBoolean(false)
)
