package com.didimlog.application.ranking

import com.didimlog.domain.Solution
import com.didimlog.domain.Student
import com.didimlog.domain.Solutions
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

@DisplayName("RankingService 테스트")
class RankingServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val rankingService = RankingService(studentRepository)

    @Test
    @DisplayName("Rating 기준 내림차순으로 정렬되는지 검증한다")
    fun `Rating 기준 내림차순 정렬 검증`() {
        // given
        val student1 = createStudent("user1", rating = 1000, solvedCount = 10)
        val student2 = createStudent("user2", rating = 2000, solvedCount = 5)
        val student3 = createStudent("user3", rating = 1500, solvedCount = 8)

        every { studentRepository.findTop100ByOrderByRatingDesc() } returns listOf(
            student1, student2, student3
        )

        // when
        val result = rankingService.getTopRankers(limit = 10)

        // then
        assertThat(result).hasSize(3)
        assertThat(result[0].rank).isEqualTo(1)
        assertThat(result[0].student.rating).isEqualTo(2000) // 가장 높은 rating
        assertThat(result[1].rank).isEqualTo(2)
        assertThat(result[1].student.rating).isEqualTo(1500)
        assertThat(result[2].rank).isEqualTo(3)
        assertThat(result[2].student.rating).isEqualTo(1000) // 가장 낮은 rating
    }

    @Test
    @DisplayName("Rating이 같으면 solvedCount가 많은 순으로 정렬되는지 검증한다")
    fun `동점자 처리 - solvedCount 기준 정렬 검증`() {
        // given
        val student1 = createStudent("user1", rating = 1000, solvedCount = 5)
        val student2 = createStudent("user2", rating = 1000, solvedCount = 10)
        val student3 = createStudent("user3", rating = 1000, solvedCount = 8)

        every { studentRepository.findTop100ByOrderByRatingDesc() } returns listOf(
            student1, student2, student3
        )

        // when
        val result = rankingService.getTopRankers(limit = 10)

        // then
        assertThat(result).hasSize(3)
        assertThat(result[0].rank).isEqualTo(1)
        assertThat(result[0].student.nickname.value).isEqualTo("user2") // solvedCount 10
        assertThat(result[0].student.solutions.getAll().size).isEqualTo(10)
        assertThat(result[1].rank).isEqualTo(2)
        assertThat(result[1].student.nickname.value).isEqualTo("user3") // solvedCount 8
        assertThat(result[1].student.solutions.getAll().size).isEqualTo(8)
        assertThat(result[2].rank).isEqualTo(3)
        assertThat(result[2].student.nickname.value).isEqualTo("user1") // solvedCount 5
        assertThat(result[2].student.solutions.getAll().size).isEqualTo(5)
    }

    @Test
    @DisplayName("Rating과 solvedCount가 같으면 id(가입일) 순으로 정렬되는지 검증한다")
    fun `동점자 처리 - id 기준 정렬 검증`() {
        // given
        val student1 = createStudent("user1", rating = 1000, solvedCount = 5, id = "id1")
        val student2 = createStudent("user2", rating = 1000, solvedCount = 5, id = "id2")
        val student3 = createStudent("user3", rating = 1000, solvedCount = 5, id = "id3")

        every { studentRepository.findTop100ByOrderByRatingDesc() } returns listOf(
            student1, student2, student3
        )

        // when
        val result = rankingService.getTopRankers(limit = 10)

        // then
        assertThat(result).hasSize(3)
        // id가 작을수록 먼저 가입한 것으로 간주 (문자열 비교)
        assertThat(result[0].student.id).isEqualTo("id1")
        assertThat(result[1].student.id).isEqualTo("id2")
        assertThat(result[2].student.id).isEqualTo("id3")
    }

    @Test
    @DisplayName("동점자는 같은 순위를 부여받는지 검증한다")
    fun `동점자 같은 순위 부여 검증`() {
        // given
        val student1 = createStudent("user1", rating = 1000, solvedCount = 10)
        val student2 = createStudent("user2", rating = 1000, solvedCount = 10) // 동점
        val student3 = createStudent("user3", rating = 900, solvedCount = 5)

        every { studentRepository.findTop100ByOrderByRatingDesc() } returns listOf(
            student1, student2, student3
        )

        // when
        val result = rankingService.getTopRankers(limit = 10)

        // then
        assertThat(result).hasSize(3)
        assertThat(result[0].rank).isEqualTo(1) // user1
        assertThat(result[1].rank).isEqualTo(1) // user2 (동점)
        assertThat(result[2].rank).isEqualTo(3) // user3
    }

    @Test
    @DisplayName("limit 파라미터가 제대로 적용되는지 검증한다")
    fun `limit 파라미터 적용 검증`() {
        // given
        val students = (1..20).map { i ->
            createStudent("user$i", rating = 2000 - i * 10, solvedCount = 10)
        }

        every { studentRepository.findTop100ByOrderByRatingDesc() } returns students

        // when
        val result = rankingService.getTopRankers(limit = 10)

        // then
        assertThat(result).hasSize(10)
        assertThat(result.map { it.student.nickname.value }).containsExactly(
            "user1", "user2", "user3", "user4", "user5",
            "user6", "user7", "user8", "user9", "user10"
        )
    }

    @Test
    @DisplayName("기본 limit 값(100)이 적용되는지 검증한다")
    fun `기본 limit 값 적용 검증`() {
        // given
        val students = (1..150).map { i ->
            createStudent("user$i", rating = 2000 - i * 10, solvedCount = 10)
        }

        every { studentRepository.findTop100ByOrderByRatingDesc() } returns students

        // when
        val result = rankingService.getTopRankers() // limit 생략

        // then
        assertThat(result).hasSize(100)
    }

    @Test
    @DisplayName("랭킹이 비어있으면 빈 리스트를 반환한다")
    fun `빈 랭킹 반환 검증`() {
        // given
        every { studentRepository.findTop100ByOrderByRatingDesc() } returns emptyList()

        // when
        val result = rankingService.getTopRankers(limit = 10)

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("복합 정렬 조건이 모두 적용되는지 검증한다")
    fun `복합 정렬 조건 적용 검증`() {
        // given
        val student1 = createStudent("user1", rating = 2000, solvedCount = 10, id = "id1")
        val student2 = createStudent("user2", rating = 2000, solvedCount = 15, id = "id2") // rating 동일, solvedCount 더 많음
        val student3 = createStudent("user3", rating = 2000, solvedCount = 15, id = "id3") // rating, solvedCount 동일
        val student4 = createStudent("user4", rating = 1500, solvedCount = 20, id = "id4") // rating 낮음

        every { studentRepository.findTop100ByOrderByRatingDesc() } returns listOf(
            student1, student2, student3, student4
        )

        // when
        val result = rankingService.getTopRankers(limit = 10)

        // then
        assertThat(result).hasSize(4)
        // 1등: user2 (rating 2000, solvedCount 15, id가 더 작음)
        assertThat(result[0].rank).isEqualTo(1)
        assertThat(result[0].student.nickname.value).isEqualTo("user2")
        // 1등(동점): user3 (rating 2000, solvedCount 15, id가 더 큼) - 동점자이므로 같은 순위
        assertThat(result[1].rank).isEqualTo(1) // 동점자이므로 같은 순위
        assertThat(result[1].student.nickname.value).isEqualTo("user3")
        // 3등: user1 (rating 2000, solvedCount 10)
        assertThat(result[2].rank).isEqualTo(3)
        assertThat(result[2].student.nickname.value).isEqualTo("user1")
        // 4등: user4 (rating 1500)
        assertThat(result[3].rank).isEqualTo(4)
        assertThat(result[3].student.nickname.value).isEqualTo("user4")
    }

    private fun createStudent(
        nickname: String,
        rating: Int,
        solvedCount: Int,
        id: String = "student-$nickname"
    ): Student {
        val solutions = Solutions().apply {
            repeat(solvedCount) { i ->
                add(
                    Solution(
                        problemId = ProblemId("problem-$i"),
                        timeTaken = TimeTakenSeconds(100),
                        result = ProblemResult.SUCCESS,
                        solvedAt = LocalDateTime.now()
                    )
                )
            }
        }

        val tier = try {
            Tier.fromRating(rating)
        } catch (e: Exception) {
            Tier.BRONZE // 기본값
        }

        return Student(
            id = id,
            nickname = Nickname(nickname),
            provider = Provider.BOJ,
            providerId = nickname,
            bojId = BojId(nickname), // BOJ ID는 nickname과 동일하게 사용
            password = "encoded-password",
            rating = rating,
            currentTier = tier,
            role = Role.USER,
            solutions = solutions
        )
    }
}

