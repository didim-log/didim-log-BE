package com.didimlog.application.dashboard

import com.didimlog.application.recommendation.RecommendationService
import com.didimlog.domain.Problem
import com.didimlog.domain.Solution
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
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
     * @param bojId BOJ ID (JWT 토큰에서 추출)
     * @return 대시보드 정보
     * @throws BusinessException 학생을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getDashboard(bojId: String): DashboardInfo {
        val student = findStudentByBojIdOrThrow(bojId)
        val recentSolutions = getRecentSolutions(student)
        val recommendedProblems = recommendationService.recommendProblems(bojId, count = 3)

        return DashboardInfo(
            currentTier = student.tier(),
            recentSolutions = recentSolutions,
            recommendedProblems = recommendedProblems
        )
    }

    private fun findStudentByBojIdOrThrow(bojId: String): Student {
        val bojIdVo = BojId(bojId)
        return studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }
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

