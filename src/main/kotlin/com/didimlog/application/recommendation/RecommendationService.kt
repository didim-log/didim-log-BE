package com.didimlog.application.recommendation

import com.didimlog.application.utils.AlgorithmHierarchyUtils
import com.didimlog.application.utils.ProblemLanguageDetector
import com.didimlog.domain.Problem
import com.didimlog.domain.Student
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 카테고리 필터 정책
 * - EXACT: primary category 정확 일치만 허용
 * - HIERARCHY: primary category + 선택 카테고리의 하위 태그 확장
 * - RELATED: HIERARCHY + 연관 카테고리(부모/형제) 확장
 */
enum class CategoryFilterMode {
    EXACT,
    HIERARCHY,
    RELATED
}

/**
 * 추천 문제와 매칭 근거
 */
data class RecommendationProblemMatch(
    val problem: Problem,
    val matchedByPrimary: Boolean,
    val matchedByTags: Boolean,
    val expandedFrom: List<String>
)

/**
 * 문제 추천 서비스
 * 학생의 현재 티어 레벨 범위에서 -2 ~ +2 단계의 난이도 문제 중, 아직 풀지 않은 문제를 추천한다.
 */
@Service
@Transactional(readOnly = true)
class RecommendationService(
    private val studentRepository: StudentRepository,
    private val problemRepository: ProblemRepository
) {

    fun recommendProblems(
        studentId: String,
        count: Int,
        category: String? = null,
        language: String? = null,
        filterMode: CategoryFilterMode = CategoryFilterMode.RELATED
    ): List<Problem> {
        return recommendProblemsDetailed(studentId, count, category, language, filterMode)
            .map { it.problem }
    }

    fun recommendProblemsDetailed(
        studentId: String,
        count: Int,
        category: String? = null,
        language: String? = null,
        filterMode: CategoryFilterMode = CategoryFilterMode.RELATED
    ): List<RecommendationProblemMatch> {
        val student = findStudentByIdOrThrow(studentId)
        val effectiveTierLevel = student.solvedAcTierLevel.value
        val (minLevel, maxLevel) = calculateTargetDifficultyLevelRange(effectiveTierLevel)

        val candidateProblems = findCandidateProblems(minLevel, maxLevel, category, language, filterMode)
        val solvedProblemIds = student.getSolvedProblemIds()
        val unsolvedProblems = filterUnsolvedProblems(candidateProblems, solvedProblemIds)

        return selectRandomProblems(unsolvedProblems, count)
    }

    private fun findStudentByIdOrThrow(studentId: String): Student {
        return studentRepository.findById(studentId)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. studentId=$studentId")
            }
    }

    /**
     * 현재 티어 레벨 범위에서 -2 ~ +2 단계의 난이도 레벨 범위를 계산한다.
     */
    private fun calculateTargetDifficultyLevelRange(tierLevel: Int): Pair<Int, Int> {
        if (tierLevel <= 0) {
            return Pair(1, 2)
        }

        val minLevel = (tierLevel - 2).coerceAtLeast(1)
        val maxLevel = tierLevel + 2
        return Pair(minLevel, maxLevel)
    }

    private fun findCandidateProblems(
        minLevel: Int,
        maxLevel: Int,
        category: String?,
        language: String?,
        filterMode: CategoryFilterMode
    ): List<RecommendationProblemMatch> {
        if (category == null) {
            val problems = problemRepository.findByLevelBetweenFlexible(min = minLevel, max = maxLevel)
            val filtered = if (language != null) applyLanguageFilter(problems, language) else problems
            return filtered.map {
                RecommendationProblemMatch(
                    problem = it,
                    matchedByPrimary = false,
                    matchedByTags = false,
                    expandedFrom = emptyList()
                )
            }
        }

        val categoryEnglishName = AlgorithmHierarchyUtils.findCategoryEnglishName(category)
        val targetCategories = resolveTargetCategories(categoryEnglishName, filterMode)

        val expandedTagsByCategory = if (filterMode == CategoryFilterMode.EXACT) {
            emptyMap()
        } else {
            targetCategories.associateWith { targetCategory ->
                AlgorithmHierarchyUtils.getExpandedTags(targetCategory)
            }
        }

        val allExpandedTags = expandedTagsByCategory
            .values
            .flatten()
            .distinctByLowercase()

        val candidates = problemRepository.findRecommendationCandidates(
            min = minLevel,
            max = maxLevel,
            targetCategories = targetCategories,
            expandedTags = allExpandedTags
        )

        val filteredByLanguage = if (language != null) {
            applyLanguageFilter(candidates, language)
        } else {
            candidates
        }

        return filteredByLanguage.map { problem ->
            val matchedPrimaryCategories = targetCategories.filter { targetCategory ->
                problem.category.englishName.equals(targetCategory, ignoreCase = true)
            }

            val matchedTagCategories = expandedTagsByCategory
                .filter { (_, expandedTags) ->
                    problem.tags.any { problemTag ->
                        expandedTags.any { expandedTag ->
                            problemTag.equals(expandedTag, ignoreCase = true)
                        }
                    }
                }
                .keys
                .toList()

            RecommendationProblemMatch(
                problem = problem,
                matchedByPrimary = matchedPrimaryCategories.isNotEmpty(),
                matchedByTags = matchedTagCategories.isNotEmpty(),
                expandedFrom = (matchedPrimaryCategories + matchedTagCategories).distinctByLowercase()
            )
        }
    }

    private fun resolveTargetCategories(categoryEnglishName: String, filterMode: CategoryFilterMode): List<String> {
        return when (filterMode) {
            CategoryFilterMode.EXACT,
            CategoryFilterMode.HIERARCHY -> listOf(categoryEnglishName)
            CategoryFilterMode.RELATED -> listOf(categoryEnglishName) + AlgorithmHierarchyUtils.getRelatedCategories(categoryEnglishName)
        }.distinctByLowercase()
    }

    /**
     * 언어 필터를 적용한다.
     *
     * language=ko 인 경우에는 저장된 language 필드뿐 아니라 본문/제목 기반 판별 결과를 함께 확인해
     * 오판정된 영어 문제가 내려가지 않도록 strict 하게 필터링한다.
     */
    private fun applyLanguageFilter(problems: List<Problem>, language: String): List<Problem> {
        val normalizedLanguage = language.trim().lowercase()

        return when (normalizedLanguage) {
            "ko" -> problems.filter { isStrictKoreanProblem(it) }
            "en" -> problems.filter { isStrictEnglishProblem(it) }
            else -> problems.filter { it.language.equals(normalizedLanguage, ignoreCase = true) }
        }
    }

    private fun isStrictKoreanProblem(problem: Problem): Boolean {
        val detected = ProblemLanguageDetector.detect(problem)
        if (detected == "en") {
            return false
        }
        if (detected == "ko") {
            return true
        }
        return problem.language.equals("ko", ignoreCase = true)
    }

    private fun isStrictEnglishProblem(problem: Problem): Boolean {
        val detected = ProblemLanguageDetector.detect(problem)
        if (detected == "ko") {
            return false
        }
        if (detected == "en") {
            return true
        }
        return problem.language.equals("en", ignoreCase = true)
    }

    private fun filterUnsolvedProblems(
        candidateProblems: List<RecommendationProblemMatch>,
        solvedProblemIds: Set<ProblemId>
    ): List<RecommendationProblemMatch> {
        return candidateProblems.filter { candidate ->
            !solvedProblemIds.contains(candidate.problem.id)
        }
    }

    private fun selectRandomProblems(problems: List<RecommendationProblemMatch>, count: Int): List<RecommendationProblemMatch> {
        if (problems.size <= count) {
            return problems.shuffled()
        }
        return problems.shuffled().take(count)
    }
}

private fun List<String>.distinctByLowercase(): List<String> {
    return this.distinctBy { it.lowercase() }
}
