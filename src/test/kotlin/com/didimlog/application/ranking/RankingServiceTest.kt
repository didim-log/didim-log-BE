package com.didimlog.application.ranking

import com.didimlog.domain.Solution
import com.didimlog.domain.Student
import com.didimlog.domain.Solutions
import com.didimlog.domain.enums.RankingPeriod
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.repository.StudentRetrospectiveCount
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
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

@DisplayName("RankingService 테스트")
class RankingServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val retrospectiveRepository: RetrospectiveRepository = mockk()
    private val rankingService = RankingService(studentRepository, retrospectiveRepository)

    @Test
    @DisplayName("회고 작성 수 기준 내림차순으로 랭킹이 구성된다")
    fun `회고 작성 수 기준 내림차순 랭킹`() {
        // given
        val student1 = createStudent("user1", rating = 1000, solvedCount = 0, id = "student-1")
        val student2 = createStudent("user2", rating = 2000, solvedCount = 0, id = "student-2")
        val student3 = createStudent("user3", rating = 1500, solvedCount = 0, id = "student-3")

        val page = PageImpl(
            listOf(
                StudentRetrospectiveCount(studentId = "student-2", retrospectiveCount = 10),
                StudentRetrospectiveCount(studentId = "student-3", retrospectiveCount = 8),
                StudentRetrospectiveCount(studentId = "student-1", retrospectiveCount = 5)
            ),
            PageRequest.of(0, 10),
            3
        )

        every { retrospectiveRepository.findTopStudentsByRetrospectiveCount(RankingPeriod.TOTAL, any()) } returns page
        every { studentRepository.findAllById(listOf("student-2", "student-3", "student-1")) } returns listOf(
            student2, student3, student1
        )

        // when
        val result = rankingService.getTopRankers(limit = 10, period = RankingPeriod.TOTAL)

        // then
        assertThat(result).hasSize(3)
        assertThat(result[0].rank).isEqualTo(1)
        assertThat(result[0].student.nickname.value).isEqualTo("user2")
        assertThat(result[0].retrospectiveCount).isEqualTo(10)
        assertThat(result[1].rank).isEqualTo(2)
        assertThat(result[1].student.nickname.value).isEqualTo("user3")
        assertThat(result[1].retrospectiveCount).isEqualTo(8)
        assertThat(result[2].rank).isEqualTo(3)
        assertThat(result[2].student.nickname.value).isEqualTo("user1")
        assertThat(result[2].retrospectiveCount).isEqualTo(5)
    }

    @Test
    @DisplayName("회고 작성 수 동점자는 같은 순위를 부여받는다")
    fun `회고 작성 수 동점자 같은 순위`() {
        // given
        val student1 = createStudent("user1", rating = 1000, solvedCount = 0, id = "student-1")
        val student2 = createStudent("user2", rating = 1200, solvedCount = 0, id = "student-2")
        val student3 = createStudent("user3", rating = 900, solvedCount = 0, id = "student-3")

        val page = PageImpl(
            listOf(
                StudentRetrospectiveCount(studentId = "student-1", retrospectiveCount = 10),
                StudentRetrospectiveCount(studentId = "student-2", retrospectiveCount = 10),
                StudentRetrospectiveCount(studentId = "student-3", retrospectiveCount = 5)
            ),
            PageRequest.of(0, 10),
            3
        )

        every { retrospectiveRepository.findTopStudentsByRetrospectiveCount(RankingPeriod.TOTAL, any()) } returns page
        every { studentRepository.findAllById(listOf("student-1", "student-2", "student-3")) } returns listOf(
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
    @DisplayName("limit 파라미터가 repository paging에 반영된다")
    fun `limit 파라미터 적용`() {
        // given
        val student1 = createStudent("user1", rating = 1000, solvedCount = 0, id = "student-1")

        val page = PageImpl(
            listOf(
                StudentRetrospectiveCount(studentId = "student-1", retrospectiveCount = 1)
            ),
            PageRequest.of(0, 1),
            1
        )

        every { retrospectiveRepository.findTopStudentsByRetrospectiveCount(RankingPeriod.TOTAL, any()) } returns page
        every { studentRepository.findAllById(listOf("student-1")) } returns listOf(student1)

        // when
        val result = rankingService.getTopRankers(limit = 1)

        // then
        assertThat(result).hasSize(1)
    }

    @Test
    @DisplayName("기본 limit 값(100)이 적용되는지 검증한다")
    fun `기본 limit 값 적용 검증`() {
        // given
        val page = PageImpl(
            emptyList<StudentRetrospectiveCount>(),
            PageRequest.of(0, 100),
            0
        )
        every { retrospectiveRepository.findTopStudentsByRetrospectiveCount(RankingPeriod.TOTAL, any()) } returns page
        every { studentRepository.findAllById(emptyList()) } returns emptyList()

        // when
        val result = rankingService.getTopRankers() // limit 생략

        // then
        assertThat(result).isEmpty()
    }

    @Test
    @DisplayName("랭킹이 비어있으면 빈 리스트를 반환한다")
    fun `빈 랭킹 반환 검증`() {
        // given
        val page = PageImpl(
            emptyList<StudentRetrospectiveCount>(),
            PageRequest.of(0, 10),
            0
        )
        every { retrospectiveRepository.findTopStudentsByRetrospectiveCount(RankingPeriod.TOTAL, any()) } returns page

        // when
        val result = rankingService.getTopRankers(limit = 10)

        // then
        assertThat(result).isEmpty()
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

