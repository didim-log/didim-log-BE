package com.didimlog.application.utils

import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory

/**
 * 문제 분류 정보를 프론트 표시 친화적인 전략 카테고리로 정규화한다.
 */
object ProblemCategoryViewResolver {

    private val strategyPriority = listOf(
        ProblemCategory.BFS,
        ProblemCategory.DFS,
        ProblemCategory.DIJKSTRA,
        ProblemCategory.SHORTEST_PATH,
        ProblemCategory.DP,
        ProblemCategory.GREEDY,
        ProblemCategory.BINARY_SEARCH,
        ProblemCategory.TWO_POINTER,
        ProblemCategory.BRUTEFORCE,
        ProblemCategory.BACKTRACKING,
        ProblemCategory.GRAPH_TRAVERSAL,
        ProblemCategory.DATA_STRUCTURES,
        ProblemCategory.STRING,
        ProblemCategory.MATHEMATICS,
        ProblemCategory.IMPLEMENTATION
    )

    data class CategoryView(
        val primaryCategory: String,
        val secondaryCategories: List<String>,
        val normalizedTags: List<String>
    )

    fun resolve(problem: Problem): CategoryView {
        val normalizedTagEnums = normalizeTags(problem.tags)
        val fallbackCategory = problem.category

        val primary = strategyPriority.firstOrNull { normalizedTagEnums.contains(it) }
            ?: normalizedTagEnums.firstOrNull()
            ?: fallbackCategory

        val secondary = normalizedTagEnums
            .filter { it != primary }
            .distinct()
            .map { it.name }

        val normalizedTags = if (normalizedTagEnums.isEmpty()) {
            listOf(fallbackCategory.name)
        } else {
            normalizedTagEnums.distinct().map { it.name }
        }

        return CategoryView(
            primaryCategory = primary.name,
            secondaryCategories = secondary,
            normalizedTags = normalizedTags
        )
    }

    private fun normalizeTags(tags: List<String>): List<ProblemCategory> {
        return tags.mapNotNull { raw ->
            ProblemCategory.entries.find {
                it.name.equals(raw, ignoreCase = true) ||
                    it.englishName.equals(raw, ignoreCase = true) ||
                    it.koreanName.equals(raw, ignoreCase = true)
            }
        }
    }
}
