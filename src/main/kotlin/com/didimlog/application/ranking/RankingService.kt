package com.didimlog.application.ranking

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
class RankingService(
    private val studentRepository: StudentRepository
) {

    /**
     * Rating(점수) 기준 상위 랭킹을 조회한다.
     * 동점자 처리: rating이 같으면 solvedCount(푼 문제 수)가 많은 순,
     * 그것도 같으면 가입일 순(id 기준, MongoDB ObjectId의 타임스탬프 사용).
     *
     * @param limit 조회할 상위 랭킹 수 (기본값: 100)
     * @return Rating이 높은 순서대로 정렬된 랭킹 리스트
     */
    fun getTopRankers(limit: Int = 100): List<RankingInfo> {
        // Rating 기준으로 상위 limit * 2명을 조회 (동점자 처리를 위해 여유분 확보)
        val candidates = studentRepository.findTop100ByOrderByRatingDesc()
            .take(limit * 2)

        // 동점자 처리: rating -> solvedCount -> id(가입일) 순으로 정렬
        val sortedRankers = candidates
            .sortedWith(
                compareByDescending<Student> { it.rating }
                    .thenByDescending { it.solutions.getAll().size } // solvedCount
                    .thenBy { it.id } // id가 작을수록 먼저 가입 (MongoDB ObjectId는 타임스탬프 포함)
            )
            .take(limit)

        // 순위 계산 (동점자도 같은 순위로 처리)
        return calculateRanks(sortedRankers)
    }

    /**
     * 정렬된 학생 리스트를 순위 정보로 변환한다.
     * 동점자는 같은 순위를 부여한다.
     *
     * @param sortedStudents 정렬된 학생 리스트
     * @return 순위 정보 리스트
     */
    private fun calculateRanks(sortedStudents: List<Student>): List<RankingInfo> {
        if (sortedStudents.isEmpty()) {
            return emptyList()
        }

        val rankers = mutableListOf<RankingInfo>()
        var currentRank = 1
        var previousRating: Int? = null
        var previousSolvedCount: Int? = null

        sortedStudents.forEachIndexed { index, student ->
            val solvedCount = student.solutions.getAll().size

            // 이전 학생과 rating과 solvedCount가 모두 같지 않으면 순위 변경
            if (previousRating == null || previousSolvedCount == null ||
                previousRating != student.rating || previousSolvedCount != solvedCount
            ) {
                currentRank = index + 1
            }

            rankers.add(
                RankingInfo(
                    rank = currentRank,
                    student = student
                )
            )

            previousRating = student.rating
            previousSolvedCount = solvedCount
        }

        return rankers
    }
}

/**
 * 랭킹 정보를 담는 내부 데이터 클래스
 */
data class RankingInfo(
    val rank: Int,
    val student: Student
)
