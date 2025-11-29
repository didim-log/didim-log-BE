package com.didimlog.application.recommendation

import com.didimlog.domain.Problem
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
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
     * @param studentId 학생 ID
     * @param count 추천할 문제 개수
     * @return 추천 문제 목록 (풀 수 있는 문제가 없으면 빈 리스트)
     */
    fun recommendProblems(studentId: String, count: Int): List<Problem> {
        val student = findStudentOrThrow(studentId)
        val targetDifficultyLevel = calculateTargetDifficultyLevel(student.tier())

        val candidateProblems = findCandidateProblems(targetDifficultyLevel)
        val solvedProblemIds = student.getSolvedProblemIds()
        val unsolvedProblems = filterUnsolvedProblems(candidateProblems, solvedProblemIds)

        return selectRandomProblems(unsolvedProblems, count)
    }

    private fun findStudentOrThrow(studentId: String): Student {
        return studentRepository.findById(studentId)
            .orElseThrow { IllegalArgumentException("학생을 찾을 수 없습니다. id=$studentId") }
    }

    /**
     * 현재 티어보다 한 단계 높은 난이도 레벨을 계산한다.
     * 무한 성장 로직: Tier Enum에 정의되지 않은 상위 난이도도 계산 가능하다.
     * 예: PLATINUM(4) -> 5 (DIAMOND 급), DIAMOND(5) -> 6 (RUBY 급) 등
     *
     * @param currentTier 현재 티어
     * @return 타겟 난이도 레벨 (현재 티어 레벨 + 1)
     */
    private fun calculateTargetDifficultyLevel(currentTier: Tier): Int {
        return currentTier.level + 1
    }

    private fun findCandidateProblems(targetDifficultyLevel: Int): List<Problem> {
        return problemRepository.findByDifficultyLevelBetween(
            min = targetDifficultyLevel,
            max = targetDifficultyLevel
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

