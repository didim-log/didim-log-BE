package com.didimlog.ui.dto

/**
 * 문제 통계 응답 DTO
 * 관리자가 문제 크롤링 상태를 확인하기 위한 통계 정보를 제공한다.
 */
data class ProblemStatsResponse(
    val totalCount: Long,
    val minProblemId: Int?,
    val maxProblemId: Int?
)


