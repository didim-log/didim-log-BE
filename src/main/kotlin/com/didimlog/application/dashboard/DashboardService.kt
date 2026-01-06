package com.didimlog.application.dashboard

import com.didimlog.application.quote.QuoteService
import com.didimlog.domain.Quote
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.enums.SolvedAcTierStep
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 대시보드 서비스
 * 학생의 오늘의 활동 중심으로 경량화된 대시보드 정보를 제공한다.
 */
@Service
class DashboardService(
    private val studentRepository: StudentRepository,
    private val retrospectiveRepository: RetrospectiveRepository,
    private val quoteService: QuoteService
) {

    /**
     * 학생의 대시보드 정보를 조회한다.
     * 오늘의 활동(오늘 푼 문제), 기본 프로필 정보, 랜덤 명언을 포함한다.
     *
     * @param bojId BOJ ID (JWT 토큰에서 추출)
     * @return 대시보드 정보
     * @throws BusinessException 학생을 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getDashboard(bojId: String): DashboardInfo {
        val student = findStudentByBojIdOrThrow(bojId)
        val todayRetrospectives = getTodayRetrospectives(student)
        val quote = quoteService.getRandomQuote()

        // 소셜 로그인 사용자의 경우 bojId가 null일 수 있으므로 처리
        val bojIdValue = student.bojId?.value
            ?: throw BusinessException(
                ErrorCode.COMMON_INVALID_INPUT,
                "BOJ 인증이 완료되지 않은 사용자입니다. BOJ 계정을 연동해주세요."
            )

        val tierProgress = SolvedAcTierStep.UNRATED.calculateProgress(student.rating)

        return DashboardInfo(
            studentProfile = StudentProfile(
                nickname = student.nickname.value,
                bojId = bojIdValue,
                currentTier = student.tier(),
                solvedAcTierLevel = student.solvedAcTierLevel.value,
                consecutiveSolveDays = student.consecutiveSolveDays,
                primaryLanguage = student.primaryLanguage,
                isOnboardingFinished = student.isOnboardingFinished
            ),
            todaySolvedCount = todayRetrospectives.size,
            todaySolvedProblems = todayRetrospectives.map { TodaySolvedProblem.from(it) },
            quote = quote?.let { QuoteInfo.from(it) },
            currentTierTitle = tierProgress.currentTierTitle,
            nextTierTitle = tierProgress.nextTierTitle,
            currentRating = tierProgress.currentRating,
            requiredRatingForNextTier = tierProgress.requiredRatingForNextTier,
            progressPercentage = tierProgress.progressPercentage
        )
    }

    private fun findStudentByBojIdOrThrow(bojId: String): Student {
        val bojIdVo = BojId(bojId)
        return studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. bojId=$bojId")
            }
    }

    private fun getTodayRetrospectives(student: Student): List<Retrospective> {
        val today = LocalDate.now()
        val startOfDay = today.atStartOfDay()
        val endOfDay = today.atTime(23, 59, 59)

        val studentId = student.id
            ?: return emptyList()

        return retrospectiveRepository.findByStudentIdAndCreatedAtBetween(
            studentId = studentId,
            startDate = startOfDay,
            endDate = endOfDay
        ).sortedByDescending { it.createdAt }
    }
}

/**
 * 대시보드 정보를 담는 데이터 클래스
 */
data class DashboardInfo(
    val studentProfile: StudentProfile,
    val todaySolvedCount: Int,
    val todaySolvedProblems: List<TodaySolvedProblem>,
    val quote: QuoteInfo?,
    val currentTierTitle: String,
    val nextTierTitle: String,
    val currentRating: Int,
    val requiredRatingForNextTier: Int,
    val progressPercentage: Int
)

/**
 * 학생 프로필 정보
 */
data class StudentProfile(
    val nickname: String,
    val bojId: String,
    val currentTier: Tier,
    val solvedAcTierLevel: Int,
    val consecutiveSolveDays: Int,
    val primaryLanguage: com.didimlog.domain.enums.PrimaryLanguage? = null,
    val isOnboardingFinished: Boolean = false
)

/**
 * 오늘 푼 문제 정보
 */
data class TodaySolvedProblem(
    val problemId: String,
    val result: String,
    val solvedAt: LocalDateTime
) {
    companion object {
        fun from(retrospective: Retrospective): TodaySolvedProblem {
            return TodaySolvedProblem(
                problemId = retrospective.problemId,
                result = retrospective.solutionResult?.name ?: "UNKNOWN",
                solvedAt = retrospective.createdAt
            )
        }
    }
}

/**
 * 명언 정보
 */
data class QuoteInfo(
    val id: String,
    val content: String,
    val author: String
) {
    companion object {
        fun from(quote: Quote): QuoteInfo {
            return QuoteInfo(
                id = quote.id ?: "",
                content = quote.content,
                author = quote.author
            )
        }
    }
}
