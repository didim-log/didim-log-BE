package com.didimlog.application.dashboard

import com.didimlog.application.recommendation.RecommendationService
import com.didimlog.domain.Problem
import com.didimlog.domain.Solution
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 대시보드 서비스
 * 학생의 현재 상태와 최근 활동, 추천 문제를 종합하여 대시보드 정보를 제공한다.
 */
@Service
class DashboardService(
    private val studentRepository: StudentRepository,
    private val recommendationService: RecommendationService
) {

    /**
     * 학생의 대시보드 정보를 조회한다.
     * 현재 티어, 최근 풀이 기록, 추천 문제를 포함한다.
     *
     * @param studentId 학생 ID
     * @return 대시보드 정보
     * @throws IllegalArgumentException 학생을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getDashboard(studentId: String): DashboardInfo {
        val student = findStudentOrThrow(studentId)
        val recentSolutions = getRecentSolutions(student)
        val recommendedProblems = recommendationService.recommendProblems(studentId, count = 3)

        return DashboardInfo(
            currentTier = student.tier(),
            recentSolutions = recentSolutions,
            recommendedProblems = recommendedProblems
        )
    }

    private fun findStudentOrThrow(studentId: String): Student {
        return studentRepository.findById(studentId)
            .orElseThrow { IllegalArgumentException("학생을 찾을 수 없습니다. id=$studentId") }
    }

    private fun getRecentSolutions(student: Student): List<Solution> {
        return student.solutions
            .getAll()
            .sortedByDescending { it.solvedAt }
            .take(10)
    }
}

/**
 * 대시보드 정보를 담는 데이터 클래스
 */
data class DashboardInfo(
    val currentTier: Tier,
    val recentSolutions: List<Solution>,
    val recommendedProblems: List<Problem>
)

