package com.didimlog.ui.dto

import com.didimlog.domain.Problem

/**
 * 문제 상세 정보를 반환하는 DTO
 * 도메인 엔티티를 직접 노출하지 않고, 필요한 정보만 추출하여 반환한다.
 */
data class ProblemDetailResponse(
    val id: String,
    val title: String,
    val category: String,
    val difficulty: String,
    val difficultyLevel: Int,
    val url: String,
    val descriptionHtml: String?,
    val inputDescriptionHtml: String?,
    val outputDescriptionHtml: String?,
    val sampleInputs: List<String>?,
    val sampleOutputs: List<String>?,
    val tags: List<String>
) {
    companion object {
        fun from(problem: Problem): ProblemDetailResponse {
            return ProblemDetailResponse(
                id = problem.id.value,
                title = problem.title,
                category = problem.category.englishName,
                difficulty = problem.difficulty.name,
                difficultyLevel = problem.difficultyLevel,
                url = problem.url,
                descriptionHtml = problem.descriptionHtml,
                inputDescriptionHtml = problem.inputDescriptionHtml,
                outputDescriptionHtml = problem.outputDescriptionHtml,
                sampleInputs = problem.sampleInputs,
                sampleOutputs = problem.sampleOutputs,
                tags = problem.tags
            )
        }
    }
}
