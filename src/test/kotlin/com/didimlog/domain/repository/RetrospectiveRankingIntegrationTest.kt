package com.didimlog.domain.repository

import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.Solutions
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.RankingPeriod
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.data.domain.PageRequest

@DisplayName("Retrospective 랭킹 집계 통합 테스트")
@DataMongoTest
class RetrospectiveRankingIntegrationTest {

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var retrospectiveRepository: RetrospectiveRepository

    @BeforeEach
    fun setUp() {
        retrospectiveRepository.deleteAll()
        studentRepository.deleteAll()
    }

    @Test
    @DisplayName("TOTAL 기간에서 학생별 회고 수를 내림차순으로 집계하고 페이징한다")
    fun `TOTAL 집계 및 페이징`() {
        // given
        val student1 = studentRepository.save(createStudent(id = "student-1", nickname = "user1", rating = 1000))
        val student2 = studentRepository.save(createStudent(id = "student-2", nickname = "user2", rating = 2000))
        val student3 = studentRepository.save(createStudent(id = "student-3", nickname = "user3", rating = 1500))

        val now = LocalDateTime.now()
        retrospectiveRepository.saveAll(
            listOf(
                Retrospective(studentId = student1.id!!, problemId = "1000", content = "회고 내용 1111111111", createdAt = now),
                Retrospective(studentId = student1.id!!, problemId = "1001", content = "회고 내용 2222222222", createdAt = now),
                Retrospective(studentId = student2.id!!, problemId = "1002", content = "회고 내용 3333333333", createdAt = now),
                Retrospective(studentId = student2.id!!, problemId = "1003", content = "회고 내용 4444444444", createdAt = now),
                Retrospective(studentId = student2.id!!, problemId = "1004", content = "회고 내용 5555555555", createdAt = now),
                Retrospective(studentId = student3.id!!, problemId = "1005", content = "회고 내용 6666666666", createdAt = now)
            )
        )

        // when
        val result = retrospectiveRepository.findTopStudentsByRetrospectiveCount(
            period = RankingPeriod.TOTAL,
            pageable = PageRequest.of(0, 2)
        )

        // then
        assertThat(result.totalElements).isEqualTo(3)
        assertThat(result.content).hasSize(2)
        assertThat(result.content[0].studentId).isEqualTo(student2.id)
        assertThat(result.content[0].retrospectiveCount).isEqualTo(3)
        assertThat(result.content[1].studentId).isEqualTo(student1.id)
        assertThat(result.content[1].retrospectiveCount).isEqualTo(2)
    }

    @Test
    @DisplayName("DAILY 기간은 최근 1일 이내 회고만 집계한다")
    fun `DAILY 기간 집계`() {
        // given
        val student1 = studentRepository.save(createStudent(id = "student-1", nickname = "user1", rating = 1000))
        val student2 = studentRepository.save(createStudent(id = "student-2", nickname = "user2", rating = 2000))

        val now = LocalDateTime.now()
        retrospectiveRepository.saveAll(
            listOf(
                Retrospective(studentId = student1.id!!, problemId = "1000", content = "회고 내용 1111111111", createdAt = now.minusHours(2)),
                Retrospective(studentId = student1.id!!, problemId = "1001", content = "회고 내용 2222222222", createdAt = now.minusDays(2)),
                Retrospective(studentId = student2.id!!, problemId = "1002", content = "회고 내용 3333333333", createdAt = now.minusHours(3))
            )
        )

        // when
        val result = retrospectiveRepository.findTopStudentsByRetrospectiveCount(
            period = RankingPeriod.DAILY,
            pageable = PageRequest.of(0, 10)
        )

        // then
        assertThat(result.totalElements).isEqualTo(2)
        assertThat(result.content.first { it.studentId == student1.id }.retrospectiveCount).isEqualTo(1)
        assertThat(result.content.first { it.studentId == student2.id }.retrospectiveCount).isEqualTo(1)
    }

    private fun createStudent(id: String, nickname: String, rating: Int): Student {
        return Student(
            id = id,
            nickname = Nickname(nickname),
            provider = Provider.BOJ,
            providerId = nickname,
            bojId = BojId(nickname),
            password = "encoded-password",
            rating = rating,
            currentTier = Tier.fromRating(rating),
            role = Role.USER,
            solutions = Solutions()
        )
    }
}

