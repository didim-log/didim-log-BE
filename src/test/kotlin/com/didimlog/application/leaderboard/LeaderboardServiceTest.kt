package com.didimlog.application.leaderboard

import com.didimlog.application.ranking.RankingService
import com.didimlog.domain.Student
import com.didimlog.domain.Solutions
import com.didimlog.domain.enums.RankingPeriod
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.repository.StudentRetrospectiveCount
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

@DisplayName("RankingService 테스트 (회고 수 기준)")
class LeaderboardServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val retrospectiveRepository: RetrospectiveRepository = mockk()

    private val rankingService = RankingService(studentRepository, retrospectiveRepository)

    @Test
    @DisplayName("회고 작성 수 기준 내림차순으로 랭킹을 반환한다")
    fun `회고 수 기준 내림차순 정렬`() {
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

        val page = PageImpl(
            listOf(
                StudentRetrospectiveCount(studentId = "student-1", retrospectiveCount = 10),
                StudentRetrospectiveCount(studentId = "student-2", retrospectiveCount = 5),
                StudentRetrospectiveCount(studentId = "student-3", retrospectiveCount = 1)
            ),
            PageRequest.of(0, 100),
            3
        )

        every { retrospectiveRepository.findTopStudentsByRetrospectiveCount(RankingPeriod.TOTAL, any()) } returns page
        every { studentRepository.findAllById(listOf("student-1", "student-2", "student-3")) } returns listOf(
            student1, student2, student3
        )

        // when
        val result = rankingService.getTopRankers()

        // then
        assertThat(result).hasSize(3)
        assertThat(result[0].rank).isEqualTo(1)
        assertThat(result[0].student.nickname.value).isEqualTo("user1")
        assertThat(result[0].retrospectiveCount).isEqualTo(10)

        assertThat(result[1].rank).isEqualTo(2)
        assertThat(result[1].student.nickname.value).isEqualTo("user2")
        assertThat(result[1].retrospectiveCount).isEqualTo(5)

        assertThat(result[2].rank).isEqualTo(3)
        assertThat(result[2].student.nickname.value).isEqualTo("user3")
        assertThat(result[2].retrospectiveCount).isEqualTo(1)
    }

    @Test
    @DisplayName("랭킹이 비어있으면 빈 리스트를 반환한다")
    fun `빈 랭킹 반환`() {
        // given
        val page = PageImpl(
            emptyList<StudentRetrospectiveCount>(),
            PageRequest.of(0, 100),
            0
        )
        every { retrospectiveRepository.findTopStudentsByRetrospectiveCount(RankingPeriod.TOTAL, any()) } returns page
        every { studentRepository.findAllById(emptyList()) } returns emptyList()

        // when
        val result = rankingService.getTopRankers()

        // then
        assertThat(result).isEmpty()
    }
}

