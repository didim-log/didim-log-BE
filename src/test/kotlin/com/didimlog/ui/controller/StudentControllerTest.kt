package com.didimlog.ui.controller

import com.didimlog.application.student.StudentService
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.springframework.security.core.Authentication

@DisplayName("StudentController 테스트")
class StudentControllerTest {

    @Test
    @DisplayName("회원 탈퇴 요청 시 204 No Content를 반환한다")
    fun `회원 탈퇴 204`() {
        // given
        val bojId = "testuser"
        val authentication: Authentication = mockk()
        every { authentication.name } returns bojId

        val studentService: StudentService = mockk(relaxed = true)
        val controller = StudentController(studentService)

        every { studentService.withdraw(bojId) } returns Unit

        // when & then
        val response = controller.withdraw(authentication)
        assertThat(response.statusCode.value()).isEqualTo(204)

        verify(exactly = 1) { studentService.withdraw(bojId) }
    }
}



