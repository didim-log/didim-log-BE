package com.didimlog.application

import com.didimlog.domain.Problem
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcProblemResponse
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ProblemService 테스트")
class ProblemServiceTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val problemRepository: ProblemRepository = mockk(relaxed = true)
    private val studentRepository: StudentRepository = mockk(relaxed = true)

    private val problemService = ProblemService(solvedAcClient, problemRepository, studentRepository)

    @Test
    @DisplayName("syncProblem은 Solved_ac 문제 정보를 조회하여 Problem을 upsert한다")
    fun `syncProblem으로 문제 정보를 동기화`() {
        // given
        val problemId = 1000
        val response = SolvedAcProblemResponse(
            problemId = problemId,
            titleKo = "A+B",
            level = 3
        )
        every { solvedAcClient.fetchProblem(problemId) } returns response

        val savedProblemSlot: CapturingSlot<Problem> = slot()
        every { problemRepository.save(capture(savedProblemSlot)) } answers { savedProblemSlot.captured }

        // when
        problemService.syncProblem(problemId)

        // then
        val savedProblem = savedProblemSlot.captured
        assertThat(savedProblem.id.value).isEqualTo(problemId.toString())
        assertThat(savedProblem.title).isEqualTo("A+B")
        assertThat(savedProblem.url).isEqualTo("https://www.acmicpc.net/problem/$problemId")
        assertThat(savedProblem.difficulty).isIn(Tier.BRONZE, Tier.SILVER, Tier.GOLD, Tier.PLATINUM)

        verify(exactly = 1) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("syncUserTier는 Solved_ac 사용자 티어 정보를 조회하여 Student의 티어를 갱신한다")
    fun `syncUserTier로 사용자 티어 동기화`() {
        // given
        val bojId = BojId("tester123")
        val student = Student(
            nickname = Nickname("tester"),
            bojId = bojId,
            currentTier = Tier.BRONZE
        )
        every { studentRepository.findByBojId(bojId) } returns Optional.of(student)

        val userResponse = SolvedAcUserResponse(
            handle = bojId.value,
            tier = 15
        )
        every { solvedAcClient.fetchUser(bojId) } returns userResponse

        // when
        problemService.syncUserTier(bojId.value)

        // then
        assertThat(student.tier()).isIn(Tier.GOLD, Tier.PLATINUM)
        verify(exactly = 1) { studentRepository.save(student) }
    }

    @Test
    @DisplayName("syncUserTier는 Student가 없으면 아무 일도 하지 않는다")
    fun `syncUserTier는 학생이 없으면 조용히 종료`() {
        // given
        val bojId = BojId("unknown")
        every { studentRepository.findByBojId(bojId) } returns Optional.empty()

        // when
        problemService.syncUserTier(bojId.value)

        // then
        verify(exactly = 0) { solvedAcClient.fetchUser(any()) }
        verify(exactly = 0) { studentRepository.save(any()) }
    }

    @Test
    @DisplayName("syncUserTier는 Solved_ac 티어가 현재 티어와 같으면 저장하지 않는다")
    fun `동일 티어면 save 호출 생략`() {
        // given
        val bojId = BojId("same-tier")
        val student = Student(
            nickname = Nickname("tester"),
            bojId = bojId,
            currentTier = Tier.SILVER
        )
        every { studentRepository.findByBojId(bojId) } returns Optional.of(student)

        val userResponse = SolvedAcUserResponse(
            handle = bojId.value,
            tier = 7
        )
        every { solvedAcClient.fetchUser(bojId) } returns userResponse

        // when
        problemService.syncUserTier(bojId.value)

        // then
        verify(exactly = 0) { studentRepository.save(any<Student>()) }
    }
}


