package com.didimlog.application.ranking

import com.didimlog.domain.Student
import com.didimlog.domain.enums.RankingPeriod
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 랭킹 서비스
 * 사용자들의 Rating(점수) 기준 랭킹을 조회한다.
 */
@Service
@Transactional(readOnly = true)
class RankingService(
    private val studentRepository: StudentRepository,
    private val retrospectiveRepository: RetrospectiveRepository
) {

    /**
     * 회고 작성 수 기준 상위 랭킹을 조회한다.
     * 동점자는 같은 순위를 부여한다.
     *
     * @param limit 조회할 상위 랭킹 수 (기본값: 100)
     * @param period 집계 기간
     * @return 회고 작성 수가 높은 순서대로 정렬된 랭킹 리스트
     */
    fun getTopRankers(
        limit: Int = 100,
        period: RankingPeriod = RankingPeriod.TOTAL
    ): List<RankingInfo> {
        val pageable = PageRequest.of(0, limit)
        val rankedCounts = retrospectiveRepository.findTopStudentsByRetrospectiveCount(period, pageable)
        if (rankedCounts.content.isEmpty()) {
            return emptyList()
        }

        val studentIds = rankedCounts.content.map { it.studentId }
        val students = studentRepository.findAllById(studentIds).associateBy { it.id }

        val orderedCandidates = rankedCounts.content.mapNotNull { row ->
            val student = students[row.studentId] ?: return@mapNotNull null
            RankedStudent(student = student, retrospectiveCount = row.retrospectiveCount)
        }

        return calculateRanks(orderedCandidates)
    }

    /**
     * 정렬된 랭킹 후보 리스트를 순위 정보로 변환한다.
     * 동점자는 같은 순위를 부여한다.
     *
     * @param sortedCandidates 정렬된 랭킹 후보 리스트
     * @return 순위 정보 리스트
     */
    private fun calculateRanks(sortedCandidates: List<RankedStudent>): List<RankingInfo> {
        if (sortedCandidates.isEmpty()) {
            return emptyList()
        }

        val rankers = mutableListOf<RankingInfo>()
        var currentRank = 1
        var previousRetrospectiveCount: Long? = null

        sortedCandidates.forEachIndexed { index, candidate ->
            val currentCount = candidate.retrospectiveCount
            if (previousRetrospectiveCount == null || previousRetrospectiveCount != currentCount) {
                currentRank = index + 1
            }

            rankers.add(
                RankingInfo(
                    rank = currentRank,
                    student = candidate.student,
                    retrospectiveCount = currentCount
                )
            )

            previousRetrospectiveCount = currentCount
        }

        return rankers
    }
}

private data class RankedStudent(
    val student: Student,
    val retrospectiveCount: Long
)

/**
 * 랭킹 정보를 담는 내부 데이터 클래스
 */
data class RankingInfo(
    val rank: Int,
    val student: Student,
    val retrospectiveCount: Long
)
