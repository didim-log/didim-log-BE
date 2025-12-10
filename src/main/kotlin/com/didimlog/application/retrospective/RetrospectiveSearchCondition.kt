package com.didimlog.application.retrospective

import com.didimlog.domain.enums.ProblemCategory

/**
 * 회고 검색 조건
 */
data class RetrospectiveSearchCondition(
    val keyword: String? = null,
    val category: ProblemCategory? = null,
    val isBookmarked: Boolean? = null,
    val studentId: String? = null // Student 엔티티의 DB ID (@Id 필드)
)
