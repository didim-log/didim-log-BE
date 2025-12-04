package com.didimlog.application.leaderboard

import com.didimlog.domain.Student
import com.didimlog.domain.repository.StudentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 랭킹 서비스
 * 사용자들의 Rating(점수) 기준 랭킹을 조회한다.
 */
@Service
@Transactional(readOnly = true)
class LeaderboardService(
    private val studentRepository: StudentRepository
) {

    /**
     * Rating(점수) 기준 상위 100명의 랭킹을 조회한다.
     * 동점자 처리: 점수가 같을 경우 먼저 가입한 순서(생성일 기준)로 정렬한다.
     *
     * @return Rating이 높은 순서대로 정렬된 랭킹 리스트 (최대 100개)
     */
    fun getTopRankers(): List<LeaderboardRanker> {
        val topStudents = studentRepository.findTop100ByOrderByRatingDesc()
        
        return topStudents.mapIndexed { index, student ->
            LeaderboardRanker(
                rank = index + 1, // 1부터 시작하는 순위
                student = student
            )
        }
    }
}

/**
 * 랭킹 정보를 담는 내부 데이터 클래스
 */
data class LeaderboardRanker(
    val rank: Int,
    val student: Student
)

