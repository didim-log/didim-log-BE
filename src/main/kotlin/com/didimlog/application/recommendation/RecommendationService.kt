package com.didimlog.application.recommendation

import com.didimlog.application.utils.TagUtils
import com.didimlog.domain.Problem
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

    /**
     * 문제 추천 서비스
     * 학생의 현재 티어 레벨 범위에서 -2 ~ +2 단계의 난이도 문제 중, 아직 풀지 않은 문제를 추천한다.
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
     * 현재 티어 레벨 범위에서 -2 ~ +2 단계의 난이도 문제 중, 아직 풀지 않은 문제를 랜덤으로 선택한다.
     * 무한 성장 로직: Tier Enum에 정의되지 않은 상위 난이도(DIAMOND, RUBY 등)도 추천 가능하다.
     *
     * @param bojId BOJ ID (JWT 토큰에서 추출)
     * @param count 추천할 문제 개수
     * @param category 문제 카테고리 (선택사항, null이면 모든 카테고리)
     * @param language 문제 언어 (선택사항, "ko" 또는 "en", null이면 모든 언어)
     * @return 추천 문제 목록 (풀 수 있는 문제가 없으면 빈 리스트)
     */
    fun recommendProblems(bojId: String, count: Int, category: String? = null, language: String? = null): List<Problem> {
        val student = findStudentByBojIdOrThrow(bojId)
        // Solved.ac tierLevel(1=Bronze 5 ...)을 추천 난이도 계산의 Source of Truth로 사용한다.
        // 로그인/새로고침 시점에 동기화된 tierLevel을 기준으로 범위를 계산한다.
        val effectiveTierLevel = student.solvedAcTierLevel.value
        val (minLevel, maxLevel) = calculateTargetDifficultyLevelRange(effectiveTierLevel)

        val candidateProblems = findCandidateProblems(minLevel, maxLevel, category, language)
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
     * 현재 티어 레벨 범위에서 -2 ~ +2 단계의 난이도 레벨 범위를 계산한다.
     * 타겟 범위: (현재 티어 최소 레벨 - 2) ~ (현재 티어 최대 레벨 + 2)
     * 최소 레벨 제약: 계산된 최소 레벨이 1 미만인 경우 1로 제한
     * 무한 성장 로직: 최대 티어에 도달한 경우에도 상위 난이도 문제를 추천한다.
     * 예: BRONZE 티어(레벨 1~5) 학생 -> 레벨 (1-2) ~ (5+2) = 레벨 1~7 문제 추천
     * 예: SILVER 티어(레벨 6~10) 학생 -> 레벨 (6-2) ~ (10+2) = 레벨 4~12 문제 추천
     * 예: RUBY 티어(레벨 26~30) 학생 -> 레벨 (26-2) ~ (30+2) = 레벨 24~32 문제 추천
     * 예: UNRATED 티어(레벨 0) 학생 -> 레벨 1~2 (Bronze V ~ Bronze IV) 문제 추천
     *
     * @param tierLevel Solved.ac 사용자 티어 레벨 (0=Unrated, 1~30=Bronze 5~Ruby 1, 31=Master)
     * @return 타겟 난이도 레벨 범위 (minLevel, maxLevel) Pair
     */
    private fun calculateTargetDifficultyLevelRange(tierLevel: Int): Pair<Int, Int> {
        // UNRATED 특별 처리: Bronze V(레벨 1) ~ Bronze IV(레벨 2) 추천
        if (tierLevel <= 0) {
            return Pair(1, 2)
        }
        
        val minLevel = (tierLevel - 2).coerceAtLeast(1)
        val maxLevel = tierLevel + 2
        return Pair(minLevel, maxLevel)
    }

    private fun findCandidateProblems(minLevel: Int, maxLevel: Int, category: String?, language: String?): List<Problem> {
        val problems = if (category != null) {
            // 1. 태그 별칭을 공식 전체 이름으로 변환 (예: "BFS" -> "Breadth-first Search")
            val normalizedCategory = TagUtils.normalizeTagName(category)
            
            // 2. ProblemCategory enum에서 englishName 또는 enum 이름으로 매칭
            val categoryEnglishName = ProblemCategory.entries
                .find { 
                    // enum 이름 매칭 (예: BFS, DFS, DP)
                    it.name.equals(normalizedCategory, ignoreCase = true) ||
                    // englishName 매칭 (예: "Breadth-first Search")
                    it.englishName.equals(normalizedCategory, ignoreCase = true)
                }
                ?.englishName
                ?: normalizedCategory // 매칭 안 되면 정규화된 문자열 사용
            
            problemRepository.findByLevelBetweenAndCategory(
                min = minLevel,
                max = maxLevel,
                category = categoryEnglishName
            )
        } else {
            // 레거시 스키마(difficultyLevel)도 함께 지원한다.
            problemRepository.findByLevelBetweenFlexible(min = minLevel, max = maxLevel)
        }
        
        // language 필터 적용
        return if (language != null) {
            problems.filter { it.language == language }
        } else {
            problems
        }
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
