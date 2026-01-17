package com.didimlog.ui.dto

import com.didimlog.domain.Solution
import java.time.LocalDateTime

/**
 * 풀이 기록 응답 DTO
 */
data class SolutionResponse(
    val problemId: String,
    val timeTaken: Long,
    val result: String,
    val solvedAt: LocalDateTime
) {
    companion object {
        fun from(solution: Solution): SolutionResponse {
            return SolutionResponse(
                problemId = solution.problemId.value,
                timeTaken = solution.timeTaken.value,
                result = solution.result.name,
                solvedAt = solution.solvedAt
            )
        }
    }
}




