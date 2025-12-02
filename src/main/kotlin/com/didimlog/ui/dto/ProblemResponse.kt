package com.didimlog.ui.dto

import com.didimlog.domain.Problem

/**
 * 문제 정보를 반환하는 DTO
 * 도메인 엔티티를 직접 노출하지 않고, 필요한 정보만 추출하여 반환한다.
 */
data class ProblemResponse(
    val id: String,
    val title: String,
    val category: String,
    val difficulty: String,
    val difficultyLevel: Int,
    val url: String
) {
    companion object {
        fun from(problem: Problem): ProblemResponse {
            return ProblemResponse(
                id = problem.id.value,
                title = problem.title,
                category = problem.category.englishName,
                difficulty = problem.difficulty.name,
                difficultyLevel = problem.difficultyLevel,
                url = problem.url
            )
        }
    }
}


