package com.didimlog.problem.application.collector

import org.springframework.data.redis.core.script.DefaultRedisScript

/**
 * 작업 상태를 원자적으로 바꾸는 Lua 스크립트와 그 결과 코드.
 *
 * 스크립트 본문은 EVALSHA의 SHA1이 되므로 한 글자도 바꾸지 않는다.
 * [ProblemCollectorRedisContractTest]가 SHA1을 고정한다.
 */
internal object ProblemJobRedisScripts {
    /** CAS가 충돌했을 때 상태를 다시 읽어 재시도하는 최대 횟수 */
    const val MAX_JOB_STATE_RETRIES = 3

    const val JOB_FAILURE_LEDGER_INVALID = -2L
    const val JOB_STATE_MISSING = -1L
    const val JOB_STATE_CONFLICT = 0L
    const val JOB_STATE_UPDATED = 1L
    const val JOB_LEASE_CONFLICT = -3L
    const val JOB_LEASE_INVALID = -4L
    const val JOB_LEASE_DURATION_INVALID = -6L

    val CREATE_JOB_SCRIPT = DefaultRedisScript(
        """
        if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[3]) == 1 then
            return 0
        end

        local indexType = redis.call('TYPE', KEYS[2])
        if type(indexType) == 'table' then
            indexType = indexType['ok']
        end
        if indexType ~= 'none' and indexType ~= 'zset' then
            return -1
        end

        redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
        redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[3])
        redis.call('ZADD', KEYS[2], ARGV[4], ARGV[5])
        return 1
        """.trimIndent(),
        Long::class.java
    )
    val COMPARE_AND_SET_JOB_SCRIPT = DefaultRedisScript(
        """
        local current = redis.call('GET', KEYS[1])
        if not current then
            return -1
        end
        if current ~= ARGV[1] then
            return 0
        end

        local refreshTargetTtl = false
        if ARGV[6] ~= '' then
            local targetType = redis.call('TYPE', KEYS[3])
            if type(targetType) == 'table' then
                targetType = targetType['ok']
            end
            if targetType == 'string' then
                refreshTargetTtl = true
            end
        end

        if ARGV[4] ~= '' then
            local failureType = redis.call('TYPE', KEYS[2])
            if type(failureType) == 'table' then
                failureType = failureType['ok']
            end
            if failureType ~= 'none' and failureType ~= 'set' then
                return -2
            end
            redis.call('SADD', KEYS[2], ARGV[4])
        end

        redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
        if ARGV[5] ~= '0' then
            redis.call('EXPIRE', KEYS[2], ARGV[3])
        end
        if refreshTargetTtl then
            redis.call('EXPIRE', KEYS[3], ARGV[3])
        end
        if ARGV[7] == '1' then
            redis.call('DEL', KEYS[4])
        end
        return 1
        """.trimIndent(),
        Long::class.java
    )
    val CLAIM_JOB_SCRIPT = DefaultRedisScript(
        """
        local current = redis.call('GET', KEYS[1])
        if not current then
            return -1
        end
        if current ~= ARGV[1] then
            return 0
        end

        local leaseMillis = tonumber(ARGV[5])
        if not leaseMillis or leaseMillis < 1 then
            return -6
        end

        local leaseType = redis.call('TYPE', KEYS[4])
        if type(leaseType) == 'table' then
            leaseType = leaseType['ok']
        end
        if leaseType == 'string' then
            return -3
        end
        if leaseType ~= 'none' then
            return -4
        end

        local refreshTargetTtl = false
        if ARGV[6] ~= '' then
            local targetType = redis.call('TYPE', KEYS[3])
            if type(targetType) == 'table' then
                targetType = targetType['ok']
            end
            if targetType == 'string' then
                refreshTargetTtl = true
            end
        end

        local failureType = redis.call('TYPE', KEYS[2])
        if type(failureType) == 'table' then
            failureType = failureType['ok']
        end

        redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
        redis.call('SET', KEYS[4], ARGV[4], 'PX', ARGV[5])
        if failureType == 'set' then
            redis.call('EXPIRE', KEYS[2], ARGV[3])
        end
        if refreshTargetTtl then
            redis.call('EXPIRE', KEYS[3], ARGV[3])
        end
        return 1
        """.trimIndent(),
        Long::class.java
    )
    val OWNED_COMPARE_AND_SET_JOB_SCRIPT = DefaultRedisScript(
        """
        local current = redis.call('GET', KEYS[1])
        if not current then
            return -1
        end
        if current ~= ARGV[1] then
            return 0
        end

        local leaseMillis = tonumber(ARGV[8])
        if not leaseMillis or leaseMillis < 1 then
            return -6
        end

        local leaseType = redis.call('TYPE', KEYS[4])
        if type(leaseType) == 'table' then
            leaseType = leaseType['ok']
        end
        if leaseType == 'none' then
            return -3
        end
        if leaseType ~= 'string' then
            return -4
        end
        if redis.call('GET', KEYS[4]) ~= ARGV[7] then
            return -3
        end

        local refreshTargetTtl = false
        if ARGV[6] ~= '' then
            local targetType = redis.call('TYPE', KEYS[3])
            if type(targetType) == 'table' then
                targetType = targetType['ok']
            end
            if targetType == 'string' then
                refreshTargetTtl = true
            end
        end

        if ARGV[4] ~= '' then
            local failureType = redis.call('TYPE', KEYS[2])
            if type(failureType) == 'table' then
                failureType = failureType['ok']
            end
            if failureType ~= 'none' and failureType ~= 'set' then
                return -2
            end
        end

        if ARGV[4] ~= '' then
            redis.call('SADD', KEYS[2], ARGV[4])
        end
        redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
        if ARGV[5] ~= '0' then
            redis.call('EXPIRE', KEYS[2], ARGV[3])
        end
        if refreshTargetTtl then
            redis.call('EXPIRE', KEYS[3], ARGV[3])
        end
        if ARGV[9] == '1' then
            redis.call('DEL', KEYS[4])
        else
            redis.call('PEXPIRE', KEYS[4], ARGV[8])
        end
        return 1
        """.trimIndent(),
        Long::class.java
    )
    val RENEW_JOB_LEASE_SCRIPT = DefaultRedisScript(
        """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('PEXPIRE', KEYS[1], ARGV[2])
        end
        return 0
        """.trimIndent(),
        Long::class.java
    )
    val RELEASE_JOB_LEASE_SCRIPT = DefaultRedisScript(
        """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """.trimIndent(),
        Long::class.java
    )
}
