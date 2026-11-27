package com.didimlog.problem.application.collector

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.JOB_INDEX_KEY
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.JOB_TTL_SECONDS
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.failureKey
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.jobKey
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.jobStateKeys
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.leaseKey
import com.didimlog.problem.application.collector.ProblemJobRedisKeys.targetKey
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.COMPARE_AND_SET_JOB_SCRIPT
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.CREATE_JOB_SCRIPT
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_FAILURE_LEDGER_INVALID
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_LEASE_CONFLICT
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_LEASE_DURATION_INVALID
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_LEASE_INVALID
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_STATE_CONFLICT
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_STATE_MISSING
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.JOB_STATE_UPDATED
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.MAX_JOB_STATE_RETRIES
import com.didimlog.problem.application.collector.ProblemJobRedisScripts.OWNED_COMPARE_AND_SET_JOB_SCRIPT
import com.didimlog.problem.application.collector.ProblemJobStatusCalculator.isTerminal
import com.didimlog.problem.application.collector.ProblemJobStatusCalculator.normalize
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant

internal data class JobSnapshot(
    val rawJson: String,
    val job: JobStatusUnifiedResponse
)

internal data class FailureLedger(
    val exists: Boolean,
    val problemIds: Set<String>
)

/**
 * Redis에 저장한 수집 작업 상태를 읽고, Lua CAS로 갱신한다.
 *
 * - 상태 JSON과 실패 항목 원장 읽기, 전체 작업 목록 읽기
 * - 새 작업 생성, 소유자 없는 CAS, worker lease 소유자 CAS
 * - 소유자 없는 실패 처리(markFailed)
 */
internal class ProblemJobStateStore(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val workerLeaseProperties: ProblemCollectorWorkerLeaseProperties
) {

    // 분리 전과 같은 로그 카테고리를 유지한다.
    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)

    fun readJobSnapshot(jobId: String): JobSnapshot? {
        val json = redisTemplate.opsForValue().get(jobKey(jobId)) ?: return null
        return deserializeJob(jobId, json)
            ?.let { JobSnapshot(json, it) }
    }

    fun readJob(jobId: String): JobStatusUnifiedResponse? {
        return readJobSnapshot(jobId)?.job
    }

    fun readFailureLedger(jobId: String): FailureLedger {
        val key = failureKey(jobId)
        return when (redisTemplate.type(key)) {
            DataType.NONE -> FailureLedger(exists = false, problemIds = emptySet())

            DataType.SET -> FailureLedger(
                exists = true,
                problemIds = redisTemplate.opsForSet().members(key).orEmpty()
            )

            else -> throw BusinessException(
                ErrorCode.RESOURCE_STATE_CONFLICT,
                "실패 항목 원장 Redis 타입이 올바르지 않습니다. jobId=$jobId"
            )
        }
    }

    /**
     * index의 모든 작업 상태를 읽는다.
     *
     * 조회 경로에서도 쓰기가 일어난다: 상태 key가 사라진 작업은 index에서 ZREM하고
     * 실패 원장·대상 manifest·lease key를 함께 DEL한다.
     */
    fun loadAllJobsPruningStaleIndex(): List<JobStatusUnifiedResponse> {
        val ids = redisTemplate.opsForZSet()
            .reverseRange(JOB_INDEX_KEY, 0, -1)
            .orEmpty()
            .toList()
        if (ids.isEmpty()) {
            return emptyList()
        }

        val statusKeys = ids.map(::jobKey)
        val statusValues = redisTemplate.opsForValue().multiGet(statusKeys).orEmpty()
        val jobs = mutableListOf<JobStatusUnifiedResponse>()
        val staleIds = mutableListOf<String>()

        ids.forEachIndexed { index, jobId ->
            val job = readBatchJob(jobId, statusValues.getOrNull(index))
            if (job == null) {
                staleIds.add(jobId)
            } else {
                jobs.add(job)
            }
        }

        if (staleIds.isNotEmpty()) {
            redisTemplate.opsForZSet().remove(JOB_INDEX_KEY, *staleIds.toTypedArray())
            redisTemplate.delete(
                staleIds.flatMap { jobId ->
                    listOf(failureKey(jobId), targetKey(jobId), leaseKey(jobId))
                }
            )
        }

        return jobs.sortedByDescending { it.queuedAt }
    }

    fun persistNewJob(
        job: JobStatusUnifiedResponse,
        manifestJson: String
    ): JobStatusUnifiedResponse {
        val normalized = normalize(job)
        val key = jobKey(normalized.jobId)
        val json = objectMapper.writeValueAsString(normalized)
        val result = redisTemplate.execute(
            CREATE_JOB_SCRIPT,
            listOf(key, JOB_INDEX_KEY, targetKey(normalized.jobId)),
            json,
            manifestJson,
            JOB_TTL_SECONDS.toString(),
            normalized.queuedAt.toString(),
            normalized.jobId
        )
        check(result == JOB_STATE_UPDATED) {
            "작업 상태 생성에 실패했습니다. jobId=${normalized.jobId}, result=$result"
        }
        return normalized
    }

    fun compareAndSetJob(
        snapshot: JobSnapshot,
        updated: JobStatusUnifiedResponse,
        failedProblemId: String? = null
    ): JobStatusUnifiedResponse? {
        val normalized = normalize(updated)
        check(normalized.targetManifest == snapshot.job.targetManifest) {
            "작업 상태 갱신 중 대상 manifest 참조를 변경할 수 없습니다. jobId=${normalized.jobId}"
        }
        val result = redisTemplate.execute(
            COMPARE_AND_SET_JOB_SCRIPT,
            jobStateKeys(normalized.jobId),
            snapshot.rawJson,
            objectMapper.writeValueAsString(normalized),
            JOB_TTL_SECONDS.toString(),
            failedProblemId.orEmpty(),
            normalized.failCount.toString(),
            normalized.targetManifest?.schemaVersion?.toString().orEmpty(),
            if (isTerminal(normalized.status)) "1" else "0"
        )
        return when (result) {
            JOB_STATE_UPDATED -> normalized
            JOB_FAILURE_LEDGER_INVALID -> throw IllegalStateException(
                "실패 항목 원장 Redis 타입이 올바르지 않습니다. jobId=${normalized.jobId}"
            )
            JOB_STATE_MISSING,
            JOB_STATE_CONFLICT -> null
            else -> throw IllegalStateException(
                "알 수 없는 작업 상태 갱신 결과입니다. jobId=${normalized.jobId}, result=$result"
            )
        }
    }

    fun compareAndSetWorkerJob(
        snapshot: JobSnapshot,
        updated: JobStatusUnifiedResponse,
        worker: JobWorkerContext,
        failedProblemId: String? = null
    ): JobStatusUnifiedResponse? {
        if (worker.attempt == null) {
            return compareAndSetJob(snapshot, updated, failedProblemId)
        }
        if (worker.ownershipLost.get() || snapshot.job.workerAttempt != worker.attempt) {
            return null
        }

        val normalized = normalize(updated)
        check(normalized.targetManifest == snapshot.job.targetManifest) {
            "작업 상태 갱신 중 대상 manifest 참조를 변경할 수 없습니다. jobId=${normalized.jobId}"
        }
        check(normalized.workerAttempt == worker.attempt) {
            "worker 상태 갱신 중 attempt를 변경할 수 없습니다. jobId=${normalized.jobId}"
        }
        val result = redisTemplate.execute(
            OWNED_COMPARE_AND_SET_JOB_SCRIPT,
            jobStateKeys(normalized.jobId),
            snapshot.rawJson,
            objectMapper.writeValueAsString(normalized),
            JOB_TTL_SECONDS.toString(),
            failedProblemId.orEmpty(),
            normalized.failCount.toString(),
            normalized.targetManifest?.schemaVersion?.toString().orEmpty(),
            requireNotNull(worker.leaseValue),
            workerLeaseProperties.leaseDuration.toMillis().toString(),
            if (isTerminal(normalized.status)) "1" else "0"
        )
        return when (result) {
            JOB_STATE_UPDATED -> normalized
            JOB_FAILURE_LEDGER_INVALID -> throw IllegalStateException(
                "실패 항목 원장 Redis 타입이 올바르지 않습니다. jobId=${normalized.jobId}"
            )
            JOB_LEASE_INVALID -> throw IllegalStateException(
                "작업 lease Redis 타입이 올바르지 않습니다. jobId=${normalized.jobId}"
            )
            JOB_LEASE_DURATION_INVALID -> throw IllegalStateException(
                "작업 lease 시간이 올바르지 않습니다. jobId=${normalized.jobId}"
            )
            JOB_LEASE_CONFLICT -> {
                worker.ownershipLost.set(true)
                null
            }
            JOB_STATE_MISSING,
            JOB_STATE_CONFLICT -> null
            else -> throw IllegalStateException(
                "알 수 없는 worker 상태 갱신 결과입니다. jobId=${normalized.jobId}, result=$result"
            )
        }
    }

    fun markFailed(jobId: String, errorCode: String, message: String): Boolean {
        repeat(MAX_JOB_STATE_RETRIES + 1) {
            val snapshot = readJobSnapshot(jobId) ?: return false
            if (snapshot.job.status != JobStatus.PENDING && snapshot.job.status != JobStatus.RUNNING) {
                return false
            }

            val now = nowEpochSeconds()
            val failed = snapshot.job.copy(
                status = JobStatus.FAILED,
                completedAt = now,
                lastHeartbeatAt = now,
                errorCode = errorCode,
                errorMessage = message
            )
            if (compareAndSetJob(snapshot, failed) != null) {
                return true
            }
        }
        log.warn("job failure CAS retries exhausted: jobId=$jobId")
        return false
    }

    fun nowEpochSeconds(): Long {
        return Instant.now().epochSecond
    }

    private fun deserializeJob(jobId: String, json: String): JobStatusUnifiedResponse? {
        return runCatching { objectMapper.readValue(json, JobStatusUnifiedResponse::class.java) }
            .onFailure { e ->
                log.warn("Failed to deserialize job status: jobId=$jobId, error=${e.message}")
            }
            .getOrNull()
    }

    private fun readBatchJob(jobId: String, json: String?): JobStatusUnifiedResponse? {
        if (json != null) {
            return deserializeJob(jobId, json)
        }

        return when (redisTemplate.type(jobKey(jobId))) {
            DataType.NONE -> null
            DataType.STRING -> readJob(jobId)
            else -> throw BusinessException(
                ErrorCode.RESOURCE_STATE_CONFLICT,
                "작업 상태 Redis 타입이 올바르지 않습니다. jobId=$jobId"
            )
        }
    }
}
