package com.didimlog.application.admin

import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 관리자 대시보드 서비스
 * 관리자용 통계 정보를 제공한다.
 */
@Service
class AdminDashboardService(
    private val studentRepository: StudentRepository,
    private val retrospectiveRepository: RetrospectiveRepository,
    private val logRepository: LogRepository
) {

    /**
     * 관리자 대시보드 통계 정보를 조회한다.
     *
     * @return 대시보드 통계 정보
     */
    @Transactional(readOnly = true)
    fun getDashboardStats(): AdminDashboardStats {
        val totalUsers = studentRepository.count()
        val todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN)
        val todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX)
        
        // 오늘 가입한 회원 수는 MongoDB ObjectId의 타임스탬프를 활용하거나 별도 필드가 필요함
        // 현재는 createdAt 필드가 없으므로, 간단하게 전체 회원 수만 반환
        // 향후 createdAt 필드 추가 시 수정 필요
        val todaySignups = 0L // TODO: createdAt 필드 추가 후 구현
        
        // 총 해결된 문제 수는 모든 Student의 solutions에서 SUCCESS인 Solution 개수를 집계
        val totalSolvedProblems = studentRepository.findAll()
            .sumOf { student ->
                student.solutions.getAll().count { it.isSuccess() }
            }
        
        // 오늘 작성된 회고 수
        val todayRetrospectives = retrospectiveRepository.findAll()
            .count { retrospective ->
                retrospective.createdAt.isAfter(todayStart) && retrospective.createdAt.isBefore(todayEnd)
            }
        
        // AI 생성 통계 계산
        val aiMetrics = calculateAiMetrics()

        return AdminDashboardStats(
            totalUsers = totalUsers,
            todaySignups = todaySignups,
            totalSolvedProblems = totalSolvedProblems.toLong(),
            todayRetrospectives = todayRetrospectives.toLong(),
            aiMetrics = aiMetrics
        )
    }

    /**
     * AI 생성 시간 통계를 계산한다.
     *
     * @return AI 생성 통계 (평균 소요 시간, 총 생성 수, 타임아웃 수)
     */
    private fun calculateAiMetrics(): AiMetrics {
        val logs = logRepository.findAll()
        val completedLogs = logs.filter { it.aiReviewDurationMillis != null }
        
        val totalCount = completedLogs.size
        if (totalCount == 0) {
            return AiMetrics(
                averageDurationMillis = null,
                totalGeneratedCount = 0L,
                timeoutCount = 0L
            )
        }

        val averageDuration = completedLogs.mapNotNull { it.aiReviewDurationMillis }.average().toLong()
        
        // 타임아웃된 로그는 aiReviewStatus가 FAILED이고 aiReviewDurationMillis가 30초(30000ms) 이상인 경우로 판단
        val timeoutCount = logs.count { 
            it.aiReviewStatus == com.didimlog.domain.enums.AiReviewStatus.FAILED && 
            (it.aiReviewDurationMillis == null || it.aiReviewDurationMillis >= 30_000)
        }

        return AiMetrics(
            averageDurationMillis = averageDuration,
            totalGeneratedCount = totalCount.toLong(),
            timeoutCount = timeoutCount.toLong()
        )
    }
}

/**
 * 관리자 대시보드 통계 정보
 */
data class AdminDashboardStats(
    val totalUsers: Long,
    val todaySignups: Long,
    val totalSolvedProblems: Long,
    val todayRetrospectives: Long,
    val aiMetrics: AiMetrics
)

/**
 * AI 생성 통계 정보
 */
data class AiMetrics(
    val averageDurationMillis: Long?, // 평균 AI 생성 시간 (밀리초), null이면 아직 생성된 리뷰가 없음
    val totalGeneratedCount: Long, // 총 생성된 AI 리뷰 수
    val timeoutCount: Long // 타임아웃된 리뷰 수
)
