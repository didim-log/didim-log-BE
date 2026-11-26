package com.didimlog.problem.api.dto

import com.didimlog.problem.application.category.AlgorithmHierarchyUtils

/**
 * 추천 카테고리 메타 응답 DTO
 */
data class ProblemCategoryMetaResponse(
    val canonical: String,
    val englishName: String,
    val koreanName: String,
    val aliases: List<String>,
    val parents: List<String>,
    val children: List<String>,
    val related: List<String>
) {
    companion object {
        fun from(meta: AlgorithmHierarchyUtils.CategoryMeta): ProblemCategoryMetaResponse {
            return ProblemCategoryMetaResponse(
                canonical = meta.canonical,
                englishName = meta.englishName,
                koreanName = meta.koreanName,
                aliases = meta.aliases,
                parents = meta.parents,
                children = meta.children,
                related = meta.related
            )
        }
    }
}
