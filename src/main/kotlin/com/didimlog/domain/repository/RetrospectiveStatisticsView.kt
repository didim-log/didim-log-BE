package com.didimlog.domain.repository

import com.didimlog.domain.enums.ProblemResult
import java.time.LocalDateTime

/**
 * 통계 집계에 필요한 회고 필드만 조회한다.
 */
data class RetrospectiveStatisticsView(
    val problemId: String,
    val createdAt: LocalDateTime,
    val solutionResult: ProblemResult? = null,
    val solvedCategory: String? = null
)
