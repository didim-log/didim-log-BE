package com.didimlog.application

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("StudentSignupService 테스트")
class StudentSignupServiceTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val studentRepository: StudentRepository = mockk()

    private val studentSignupService = StudentSignupService(solvedAcClient, studentRepository)

    @Test
    @DisplayName("registerWithSolvedAc는 Solved_ac 티어 정보를 기반으로 Student를 생성한다")
    fun `Solved_ac 티어로 초기 Student 생성`() {
        // given
        every { studentRepository.existsByBojId(BojId("tester123")) } returns false

        every { solvedAcClient.fetchUser(BojId("tester123")) } returns SolvedAcUserResponse(
            handle = "tester123",
            tier = 7
        )

        val studentSlot = slot<Student>()
        every { studentRepository.save(capture(studentSlot)) } answers { studentSlot.captured }

        // when
        val saved = studentSignupService.registerWithSolvedAc(
            nickname = "tester",
            bojId = "tester123"
        )

        // then
        assertThat(saved.bojId.value).isEqualTo("tester123")
        assertThat(saved.nickname.value).isEqualTo("tester")
        assertThat(saved.tier()).isIn(Tier.SILVER, Tier.GOLD, Tier.PLATINUM)

        verify(exactly = 1) { studentRepository.save(any<Student>()) }
    }

    @Test
    @DisplayName("이미 존재하는 BOJ ID로 가입을 시도하면 예외가 발생한다")
    fun `중복 BOJ ID 가입 시 예외`() {
        // given
        every { studentRepository.existsByBojId(BojId("dup")) } returns true

        // expect
        assertThrows<IllegalStateException> {
            studentSignupService.registerWithSolvedAc(
                nickname = "tester",
                bojId = "dup"
            )
        }
    }
}



