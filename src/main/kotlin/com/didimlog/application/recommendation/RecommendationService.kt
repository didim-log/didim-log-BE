package com.didimlog.application.recommendation

import com.didimlog.domain.Problem
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 문제 추천 서비스
 * 학생의 현재 티어보다 한 단계 높은 난이도의 문제 중, 아직 풀지 않은 문제를 추천한다.
 * 무한 성장(Continuous Growth) 로직을 지원하여, 최대 티어에 도달해도 상위 난이도 문제를 추천한다.
 */
@Service
@Transactional(readOnly = true)
class RecommendationService(
    private val studentRepository: StudentRepository,
    private val problemRepository: ProblemRepository
) {

    /**
     * 학생에게 추천할 문제 목록을 반환한다.
     * 현재 티어보다 한 단계 높은 난이도의 문제 중, 아직 풀지 않은 문제를 랜덤으로 선택한다.
     * 무한 성장 로직: Tier Enum에 정의되지 않은 상위 난이도(DIAMOND, RUBY 등)도 추천 가능하다.
     *
     * @param bojId BOJ ID (JWT 토큰에서 추출)
     * @param count 추천할 문제 개수
     * @param category 문제 카테고리 (선택사항, null이면 모든 카테고리)
     * @return 추천 문제 목록 (풀 수 있는 문제가 없으면 빈 리스트)
     */
    fun recommendProblems(bojId: String, count: Int, category: String? = null): List<Problem> {
        val student = findStudentByBojIdOrThrow(bojId)
        val (minLevel, maxLevel) = calculateTargetDifficultyLevelRange(student.tier())

        val candidateProblems = findCandidateProblems(minLevel, maxLevel, category)
        val solvedProblemIds = student.getSolvedProblemIds()
        val unsolvedProblems = filterUnsolvedProblems(candidateProblems, solvedProblemIds)

        return selectRandomProblems(unsolvedProblems, count)
    }

    private fun findStudentByBojIdOrThrow(bojId: String): Student {
        val bojIdVo = BojId(bojId)
        return studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }
    }

    /**
     * 현재 티어보다 한 단계 높은 난이도 레벨 범위를 계산한다.
     * 타겟 범위: UserLevel + 1 ~ +2
     * 무한 성장 로직: 최대 티어에 도달한 경우에도 상위 난이도 문제를 추천한다.
     * 예: BRONZE 티어(레벨 1~5) 학생 -> 레벨 6~7 문제 추천
     * 예: RUBY 티어(레벨 26~30) 학생 -> 레벨 31~32 문제 추천
     *
     * @param currentTier 현재 티어
     * @return 타겟 난이도 레벨 범위 (minLevel, maxLevel) Pair
     */
    private fun calculateTargetDifficultyLevelRange(currentTier: Tier): Pair<Int, Int> {
        val nextTier = currentTier.next()
        if (nextTier == currentTier) {
            // 최대 티어인 경우, 현재 티어의 최대 레벨 + 1 ~ +2를 반환 (무한 성장)
            val minLevel = currentTier.maxLevel + 1
            return Pair(minLevel, minLevel + 1)
        }
        // 다음 티어의 최소 레벨 ~ 최소 레벨 + 1
        val minLevel = nextTier.minLevel
        return Pair(minLevel, minLevel + 1)
    }

    private fun findCandidateProblems(minLevel: Int, maxLevel: Int, category: String?): List<Problem> {
        if (category != null) {
            // API에서 받은 category 문자열을 ProblemCategory의 englishName과 비교
            // ProblemCategory.entries에서 englishName이 일치하는 것을 찾아서 사용
            val categoryEnglishName = ProblemCategory.entries
                .find { it.englishName.equals(category, ignoreCase = true) }
                ?.englishName
                ?: category // 매칭 안 되면 원본 문자열 사용
            
            return problemRepository.findByLevelBetweenAndCategory(
                min = minLevel,
                max = maxLevel,
                category = categoryEnglishName
            )
        }
        return problemRepository.findByLevelBetween(
            min = minLevel,
            max = maxLevel
        )
    }

    private fun filterUnsolvedProblems(
        candidateProblems: List<Problem>,
        solvedProblemIds: Set<com.didimlog.domain.valueobject.ProblemId>
    ): List<Problem> {
        return candidateProblems.filter { problem ->
            !solvedProblemIds.contains(problem.id)
        }
    }

    private fun selectRandomProblems(problems: List<Problem>, count: Int): List<Problem> {
        if (problems.size <= count) {
            return problems.shuffled()
        }
        return problems.shuffled().take(count)
    }
}
