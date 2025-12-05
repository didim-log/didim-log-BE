package com.didimlog.application.leaderboard

import com.didimlog.application.ranking.RankingService
import com.didimlog.domain.Student
import com.didimlog.domain.Solutions
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RankingService 테스트 (LeaderboardService에서 리팩토링됨)")
class LeaderboardServiceTest {

    private val studentRepository: StudentRepository = mockk()

    private val rankingService = RankingService(studentRepository)

    @Test
    @DisplayName("Rating 기준 내림차순으로 정렬된 랭킹을 반환한다")
    fun `Rating 기준 내림차순 정렬`() {
        // given
        val student1 = Student(
            id = "student-1",
            nickname = Nickname("user1"),
            provider = Provider.BOJ,
            providerId = "user1",
            bojId = BojId("user1"),
            password = "password1",
            rating = 1500,
            currentTier = Tier.PLATINUM,
            role = Role.USER,
            solutions = Solutions(),
            consecutiveSolveDays = 10
        )
        val student2 = Student(
            id = "student-2",
            nickname = Nickname("user2"),
            provider = Provider.BOJ,
            providerId = "user2",
            bojId = BojId("user2"),
            password = "password2",
            rating = 2000,
            currentTier = Tier.DIAMOND,
            role = Role.USER,
            solutions = Solutions(),
            consecutiveSolveDays = 5
        )
        val student3 = Student(
            id = "student-3",
            nickname = Nickname("user3"),
            provider = Provider.BOJ,
            providerId = "user3",
            bojId = BojId("user3"),
            password = "password3",
            rating = 1000,
            currentTier = Tier.GOLD,
            role = Role.USER,
            solutions = Solutions(),
            consecutiveSolveDays = 3
        )

        val students = listOf(student2, student1, student3) // Rating: 2000, 1500, 1000

        every { studentRepository.findTop100ByOrderByRatingDesc() } returns students

        // when
        val result = rankingService.getTopRankers()

        // then
        assertThat(result).hasSize(3)
        assertThat(result[0].rank).isEqualTo(1)
        assertThat(result[0].student.rating).isEqualTo(2000)
        assertThat(result[0].student.nickname.value).isEqualTo("user2")

        assertThat(result[1].rank).isEqualTo(2)
        assertThat(result[1].student.rating).isEqualTo(1500)
        assertThat(result[1].student.nickname.value).isEqualTo("user1")

        assertThat(result[2].rank).isEqualTo(3)
        assertThat(result[2].student.rating).isEqualTo(1000)
        assertThat(result[2].student.nickname.value).isEqualTo("user3")
    }

    @Test
    @DisplayName("순위는 1부터 시작하여 순차적으로 매겨진다")
    fun `순위 번호는 1부터 시작`() {
        // given
        val students = (1..5).map { index ->
            Student(
                id = "student-$index",
                nickname = Nickname("user$index"),
                provider = Provider.BOJ,
                providerId = "user$index",
                bojId = BojId("user$index"),
                password = "password$index",
                rating = 1000 - (index * 100), // 1000, 900, 800, 700, 600
                currentTier = Tier.GOLD,
                role = Role.USER,
                solutions = Solutions(),
                consecutiveSolveDays = index
            )
        }

        every { studentRepository.findTop100ByOrderByRatingDesc() } returns students

        // when
        val result = rankingService.getTopRankers()

        // then
        assertThat(result).hasSize(5)
        result.forEachIndexed { index, ranker ->
            assertThat(ranker.rank).isEqualTo(index + 1)
        }
    }

    @Test
    @DisplayName("랭킹이 비어있으면 빈 리스트를 반환한다")
    fun `빈 랭킹 반환`() {
        // given
        every { studentRepository.findTop100ByOrderByRatingDesc() } returns emptyList()

        // when
        val result = rankingService.getTopRankers()

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("100명 이상의 데이터가 있어도 상위 100명만 반환한다")
    fun `최대 100명 반환`() {
        // given
        val students = (1..150).map { index ->
            Student(
                id = "student-$index",
                nickname = Nickname("user$index"),
                provider = Provider.BOJ,
                providerId = "user$index",
                bojId = BojId("user$index"),
                password = "password$index",
                rating = 2000 - index, // 내림차순으로 정렬됨
                currentTier = Tier.GOLD,
                role = Role.USER,
                solutions = Solutions(),
                consecutiveSolveDays = index
            )
        }

        every { studentRepository.findTop100ByOrderByRatingDesc() } returns students.take(100)

        // when
        val result = rankingService.getTopRankers()

        // then
        assertThat(result).hasSize(100)
        assertThat(result[0].rank).isEqualTo(1)
        assertThat(result[99].rank).isEqualTo(100)
    }
}

