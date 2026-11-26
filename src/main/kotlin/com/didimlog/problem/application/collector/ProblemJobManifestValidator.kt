package com.didimlog.problem.application.collector

import com.didimlog.problem.application.collector.ProblemJobRedisKeys.targetKey
import com.didimlog.problem.application.collector.ProblemJobTargets.sha256
import com.didimlog.problem.application.collector.ProblemJobTargets.targetIdAt
import com.didimlog.problem.application.collector.ProblemJobTargets.targetManifestConflict
import com.didimlog.problem.application.collector.ProblemJobTargets.targetPosition
import com.didimlog.problem.application.collector.ProblemJobTargets.validateAndCountTargetManifest
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 작업 상태가 가리키는 대상 manifest를 읽어 검증하고, 작업을 이어서 실행할 수
 * 있는 상태인지 확인한다.
 *
 * 재시도(manifest 기준 대상 선택)와 재시작 후 이어서 실행이 함께 쓴다.
 * 검증에 실패하면 [targetManifestConflict]로 409 예외를 던진다.
 */
internal class ProblemJobManifestValidator(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val stateStore: ProblemJobStateStore
) {

    fun readTargetManifest(
        job: JobStatusUnifiedResponse
    ): ProblemJobTargetManifest? {
        val reference = job.targetManifest ?: return null
        if (reference.schemaVersion != ProblemJobTargetManifest.CURRENT_VERSION) {
            targetManifestConflict(
                job.jobId,
                "지원하지 않는 manifest 참조 버전입니다. version=${reference.schemaVersion}"
            )
        }

        val key = targetKey(job.jobId)
        if (redisTemplate.type(key) != DataType.STRING) {
            targetManifestConflict(job.jobId, "대상 manifest key가 없거나 문자열이 아닙니다.")
        }
        val rawJson = redisTemplate.opsForValue().get(key)
            ?: targetManifestConflict(job.jobId, "대상 manifest를 찾을 수 없습니다.")
        if (sha256(rawJson) != reference.sha256) {
            targetManifestConflict(job.jobId, "대상 manifest hash가 상태 참조와 일치하지 않습니다.")
        }

        val manifest = runCatching {
            objectMapper.readValue(rawJson, ProblemJobTargetManifest::class.java)
        }.getOrElse {
            targetManifestConflict(job.jobId, "대상 manifest JSON을 읽을 수 없습니다.")
        }
        validateManifestForJob(job, manifest)
        return manifest
    }

    fun validateManifestForJob(
        job: JobStatusUnifiedResponse,
        manifest: ProblemJobTargetManifest
    ): Int {
        if (manifest.jobId != job.jobId) {
            targetManifestConflict(job.jobId, "대상 manifest jobId가 작업 상태와 다릅니다.")
        }
        if (manifest.jobType != job.jobType) {
            targetManifestConflict(job.jobId, "대상 manifest 작업 유형이 작업 상태와 다릅니다.")
        }
        val targetCount = try {
            validateAndCountTargetManifest(manifest)
        } catch (e: IllegalArgumentException) {
            targetManifestConflict(job.jobId, e.message ?: "대상 manifest가 올바르지 않습니다.")
        }
        if (targetCount != job.totalCount) {
            targetManifestConflict(
                job.jobId,
                "대상 수가 작업 상태와 다릅니다. manifest=$targetCount, status=${job.totalCount}"
            )
        }
        return targetCount
    }

    fun validateResumeState(
        job: JobStatusUnifiedResponse
    ): ProblemJobTargetManifest {
        if (job.status != JobStatus.PENDING && job.status != JobStatus.RUNNING) {
            targetManifestConflict(job.jobId, "종료된 작업은 이어서 실행할 수 없습니다.")
        }
        if (job.completedAt != null) {
            targetManifestConflict(job.jobId, "실행 중 작업에 종료 시각이 있습니다.")
        }
        if (job.status == JobStatus.PENDING) {
            if (
                job.startedAt != null ||
                job.workerAttempt != null ||
                job.processedCount != 0 ||
                job.successCount != 0 ||
                job.failCount != 0
            ) {
                targetManifestConflict(job.jobId, "대기 작업에 실행 이력 또는 진행 상태가 있습니다.")
            }
        } else {
            val attempt = job.workerAttempt
                ?: targetManifestConflict(job.jobId, "실행 중 작업에 worker attempt가 없습니다.")
            if (
                attempt.schemaVersion != ProblemJobWorkerAttempt.CURRENT_SCHEMA_VERSION ||
                attempt.ownerId.isBlank() ||
                attempt.attemptId.isBlank() ||
                attempt.attemptNumber < 1
            ) {
                targetManifestConflict(job.jobId, "worker attempt가 올바르지 않습니다.")
            }
        }
        if (job.processedCount !in 0..job.totalCount) {
            targetManifestConflict(
                job.jobId,
                "처리 수가 대상 수 범위를 벗어났습니다. processed=${job.processedCount}, total=${job.totalCount}"
            )
        }
        if (job.successCount < 0 || job.failCount < 0) {
            targetManifestConflict(job.jobId, "성공 또는 실패 수가 음수입니다.")
        }
        if (job.successCount + job.failCount != job.processedCount) {
            targetManifestConflict(job.jobId, "처리 수가 성공 수와 실패 수의 합과 다릅니다.")
        }

        val manifest = readTargetManifest(job)
            ?: targetManifestConflict(job.jobId, "대상 manifest 참조가 없습니다.")
        if (job.processedCount == 0) {
            if (job.lastCheckpointId != null) {
                targetManifestConflict(job.jobId, "처리 전 작업에 체크포인트가 있습니다.")
            }
        } else {
            val expectedCheckpoint = targetIdAt(manifest, job.processedCount - 1)
            if (job.lastCheckpointId != expectedCheckpoint) {
                targetManifestConflict(job.jobId, "체크포인트가 manifest 처리 위치와 일치하지 않습니다.")
            }
        }

        val failureLedger = stateStore.readFailureLedger(job.jobId)
        if (failureLedger.problemIds.size != job.failCount) {
            targetManifestConflict(
                job.jobId,
                "실패 수가 실패 항목 기록 수와 다릅니다. fail=${job.failCount}, recorded=${failureLedger.problemIds.size}"
            )
        }
        failureLedger.problemIds.forEach { failedProblemId ->
            val position = targetPosition(manifest, failedProblemId)
                ?: targetManifestConflict(
                    job.jobId,
                    "실패 항목이 manifest에 없습니다. problemId=$failedProblemId"
                )
            if (position >= job.processedCount) {
                targetManifestConflict(
                    job.jobId,
                    "실패 항목이 처리 완료 prefix 밖에 있습니다. problemId=$failedProblemId"
                )
            }
        }
        return manifest
    }
}
