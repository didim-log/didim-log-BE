package com.didimlog.problem.application.collector

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.problem.domain.Problem
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * 작업 대상 manifest에서 아직 처리할 대상을 고른 결과.
 *
 * 명시 ID 목록 다음에 범위(메타데이터 작업만)가 이어진다.
 */
internal data class ManifestTargetSelection(
    val explicitIds: List<String>,
    val range: JobRange?
)

/**
 * 작업 대상 manifest와 문제 목록을 다루는 순수 함수.
 *
 * - manifest 검증과 대상 수 계산, 대상 위치와 ID 변환, 처리한 앞부분 잘라내기
 * - 문제 목록 정렬, 범위 필터, 체크포인트 이후 판별, 요청 범위 검증
 */
internal object ProblemJobTargets {
    fun validateAndCountTargetManifest(
        manifest: ProblemJobTargetManifest
    ): Int {
        require(manifest.version == ProblemJobTargetManifest.CURRENT_VERSION) {
            "지원하지 않는 작업 대상 manifest 버전입니다. version=${manifest.version}"
        }
        require(manifest.jobId.isNotBlank()) {
            "작업 대상 manifest jobId가 비어 있습니다."
        }
        require(manifest.explicitIds.none(String::isBlank)) {
            "작업 대상 manifest에 빈 ID가 있습니다. jobId=${manifest.jobId}"
        }
        require(manifest.explicitIds.distinct().size == manifest.explicitIds.size) {
            "작업 대상 manifest에 중복 ID가 있습니다. jobId=${manifest.jobId}"
        }

        val targetRange = manifest.range
        val rangeCount = if (targetRange == null) {
            0L
        } else {
            require(manifest.jobType == ProblemJobType.COLLECT_METADATA) {
                "메타데이터 작업 외에는 범위 manifest를 사용할 수 없습니다. jobId=${manifest.jobId}"
            }
            val start = requireNotNull(targetRange.start) {
                "작업 대상 manifest 범위의 start가 없습니다. jobId=${manifest.jobId}"
            }
            val end = requireNotNull(targetRange.end) {
                "작업 대상 manifest 범위의 end가 없습니다. jobId=${manifest.jobId}"
            }
            require(start > 0 && end > 0 && start <= end) {
                "작업 대상 manifest 범위가 올바르지 않습니다. jobId=${manifest.jobId}, start=$start, end=$end"
            }
            end.toLong() - start.toLong() + 1L
        }

        if (manifest.jobType == ProblemJobType.COLLECT_METADATA) {
            val explicitProblemIds = manifest.explicitIds.map { problemId ->
                requireNotNull(problemId.toIntOrNull()) {
                    "메타데이터 작업 대상 ID가 숫자가 아닙니다. jobId=${manifest.jobId}, problemId=$problemId"
                }
            }
            require(explicitProblemIds.all { it > 0 }) {
                "메타데이터 작업 대상 ID는 1 이상이어야 합니다. jobId=${manifest.jobId}"
            }
            if (targetRange != null) {
                val start = requireNotNull(targetRange.start)
                val end = requireNotNull(targetRange.end)
                require(explicitProblemIds.none { it in start..end }) {
                    "작업 대상 manifest의 명시 ID와 범위가 겹칩니다. jobId=${manifest.jobId}"
                }
            }
        }

        val targetCount = manifest.explicitIds.size.toLong() + rangeCount
        require(targetCount <= Int.MAX_VALUE.toLong()) {
            "작업 대상 수가 허용 범위를 벗어났습니다. jobId=${manifest.jobId}, count=$targetCount"
        }
        return targetCount.toInt()
    }

    fun targetPosition(
        manifest: ProblemJobTargetManifest,
        targetId: String
    ): Int? {
        val explicitIndex = manifest.explicitIds.indexOf(targetId)
        if (explicitIndex >= 0) {
            return explicitIndex
        }

        val start = manifest.range?.start ?: return null
        val end = manifest.range.end ?: return null
        val numericId = targetId.toIntOrNull() ?: return null
        if (numericId !in start..end) {
            return null
        }
        return manifest.explicitIds.size + (numericId - start)
    }

    fun targetIdAt(
        manifest: ProblemJobTargetManifest,
        index: Int
    ): String {
        if (index < manifest.explicitIds.size) {
            return manifest.explicitIds[index]
        }
        val start = manifest.range?.start
            ?: targetManifestConflict(manifest.jobId, "manifest 대상 위치가 범위를 벗어났습니다.")
        val end = manifest.range.end
            ?: targetManifestConflict(manifest.jobId, "manifest 범위의 end가 없습니다.")
        val numericId = start.toLong() + index.toLong() - manifest.explicitIds.size.toLong()
        if (numericId > end.toLong()) {
            targetManifestConflict(manifest.jobId, "manifest 대상 위치가 범위를 벗어났습니다.")
        }
        return numericId.toString()
    }

    fun dropManifestTargets(
        manifest: ProblemJobTargetManifest,
        processedCount: Int
    ): ManifestTargetSelection {
        if (processedCount < manifest.explicitIds.size) {
            return ManifestTargetSelection(
                explicitIds = manifest.explicitIds.drop(processedCount),
                range = manifest.range
            )
        }

        val consumedFromRange = processedCount - manifest.explicitIds.size
        val range = manifest.range ?: return ManifestTargetSelection(emptyList(), null)
        val start = requireNotNull(range.start)
        val end = requireNotNull(range.end)
        val remainingStart = start.toLong() + consumedFromRange.toLong()
        return if (remainingStart > end.toLong()) {
            ManifestTargetSelection(emptyList(), null)
        } else {
            ManifestTargetSelection(
                explicitIds = emptyList(),
                range = JobRange(remainingStart.toInt(), end)
            )
        }
    }

    fun metadataTargetIds(
        explicitTargetIds: List<Int>,
        targetRange: JobRange?
    ): Iterable<Int> {
        return sequence {
            yieldAll(explicitTargetIds)
            if (targetRange != null) {
                val start = requireNotNull(targetRange.start)
                val end = requireNotNull(targetRange.end)
                yieldAll(start..end)
            }
        }.asIterable()
    }

    fun targetManifestConflict(jobId: String, reason: String): Nothing {
        throw BusinessException(
            ErrorCode.RESOURCE_STATE_CONFLICT,
            "작업 대상 manifest가 올바르지 않습니다. jobId=$jobId, reason=$reason"
        )
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    fun orderProblems(problems: List<Problem>): List<Problem> {
        return problems
            .distinctBy { it.id.value }
            .sortedWith(
                compareBy<Problem> { it.id.value.toIntOrNull() == null }
                    .thenBy { it.id.value.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.id.value }
            )
    }

    fun filterProblemsByRange(
        problems: List<Problem>,
        start: Int?,
        end: Int?
    ): List<Problem> {
        if (start == null || end == null) {
            return problems
        }

        return problems.filter { problem ->
            val numericProblemId = problem.id.value.toIntOrNull() ?: return@filter false
            numericProblemId in start..end
        }
    }

    fun isAfterCheckpoint(problem: Problem, checkpointExclusive: Int?): Boolean {
        if (checkpointExclusive == null) {
            return true
        }
        val numericId = problem.id.value.toIntOrNull() ?: return true
        return numericId > checkpointExclusive
    }

    fun validateRange(start: Int?, end: Int?) {
        if ((start == null) != (end == null)) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "start와 end는 함께 제공되어야 합니다.")
        }
        if (start != null && end != null && start > end) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "start는 end보다 클 수 없습니다. start=$start, end=$end")
        }
        if (start != null && start <= 0) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "start는 1 이상이어야 합니다. start=$start")
        }
        if (end != null && end <= 0) {
            throw BusinessException(ErrorCode.INVALID_RANGE, "end는 1 이상이어야 합니다. end=$end")
        }
    }
}
