package com.didimlog.problem.application.collector

/**
 * 수집 작업이 쓰는 Redis 키 형식과 TTL.
 *
 * 작업마다 상태(status), 실패 항목 원장(failures), 대상 manifest(targets),
 * worker lease(lease) 키를 두고, 작업 목록은 index sorted set으로 찾는다.
 */
internal object ProblemJobRedisKeys {
    const val JOB_KEY_PREFIX = "problem:job:status:"
    const val JOB_FAILURE_KEY_PREFIX = "problem:job:failures:"
    const val JOB_TARGET_KEY_PREFIX = "problem:job:targets:"
    const val JOB_LEASE_KEY_PREFIX = "problem:job:lease:"
    const val JOB_INDEX_KEY = "problem:job:index"
    const val JOB_TTL_SECONDS = 86400L

    fun jobKey(jobId: String): String {
        return "$JOB_KEY_PREFIX$jobId"
    }

    fun failureKey(jobId: String): String {
        return "$JOB_FAILURE_KEY_PREFIX$jobId"
    }

    fun targetKey(jobId: String): String {
        return "$JOB_TARGET_KEY_PREFIX$jobId"
    }

    fun leaseKey(jobId: String): String {
        return "$JOB_LEASE_KEY_PREFIX$jobId"
    }

    /** Lua 스크립트의 KEYS[1..4] 순서: 상태, 실패 원장, 대상 manifest, lease */
    fun jobStateKeys(jobId: String): List<String> {
        return listOf(
            jobKey(jobId),
            failureKey(jobId),
            targetKey(jobId),
            leaseKey(jobId)
        )
    }
}
