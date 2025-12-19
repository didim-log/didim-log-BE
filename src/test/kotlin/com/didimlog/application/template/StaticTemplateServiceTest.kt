package com.didimlog.application.template

import com.didimlog.application.ProblemService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("StaticTemplateService 테스트")
class StaticTemplateServiceTest {

    private val problemService: ProblemService = mockk()
    private val service = StaticTemplateService(problemService)

    @Test
    @DisplayName("성공 회고 정적 템플릿을 생성한다")
    fun `성공 회고 템플릿 생성`() {
        // given
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000",
            descriptionHtml = "<p>두 정수 A와 B를 입력받은 다음, A+B를 출력하는 프로그램을 작성하시오.</p>"
        )

        every { problemService.getProblemDetail(1000L) } returns problem

        // when
        val result = service.generateRetrospectiveTemplate(
            problemId = "1000",
            code = "print(1 + 2)",
            isSuccess = true
        )

        // then
        assertThat(result).contains("# [회고]")
        assertThat(result).contains("구현")
        assertThat(result).contains("print(1 + 2)")
        assertThat(result).contains("AI 서비스 점검 중")
        assertThat(result).contains("문제 분석 및 접근")
        assertThat(result).contains("제출한 코드")
        assertThat(result).contains("개선할 점 / 배운 점")
    }

    @Test
    @DisplayName("실패 회고 정적 템플릿을 생성한다")
    fun `실패 회고 템플릿 생성`() {
        // given
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.DP,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000",
            descriptionHtml = "<p>두 정수 A와 B를 입력받은 다음, A+B를 출력하는 프로그램을 작성하시오.</p>"
        )

        every { problemService.getProblemDetail(1000L) } returns problem

        // when
        val result = service.generateRetrospectiveTemplate(
            problemId = "1000",
            code = "def solve(): pass",
            isSuccess = false,
            errorMessage = "IndexError: list index out of range"
        )

        // then
        assertThat(result).contains("# [오답 노트]")
        assertThat(result).contains("다이나믹 프로그래밍")
        assertThat(result).contains("def solve(): pass")
        assertThat(result).contains("IndexError: list index out of range")
        assertThat(result).contains("AI 서비스 점검 중")
        assertThat(result).contains("발생한 에러")
        assertThat(result).contains("문제 코드")
        assertThat(result).contains("원인 분석")
        assertThat(result).contains("해결 방안")
    }

    @Test
    @DisplayName("에러 메시지가 null일 때 기본 메시지를 사용한다")
    fun `에러 메시지 null 처리`() {
        // given
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.STRING,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000",
            descriptionHtml = "<p>두 정수 A와 B를 입력받은 다음, A+B를 출력하는 프로그램을 작성하시오.</p>"
        )

        every { problemService.getProblemDetail(1000L) } returns problem

        // when
        val result = service.generateRetrospectiveTemplate(
            problemId = "1000",
            code = "code",
            isSuccess = false,
            errorMessage = null
        )

        // then
        assertThat(result).contains("에러 로그를 확인할 수 없습니다.")
    }

    @Test
    @DisplayName("code가 비어있으면 예외가 발생한다")
    fun `code 빈 값 검증`() {
        // when & then
        val exception = assertThrows<BusinessException> {
            service.generateRetrospectiveTemplate(
                problemId = "1000",
                code = "",
                isSuccess = true
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("code는 비어 있을 수 없습니다")
    }

    @Test
    @DisplayName("problemId가 비어있으면 예외가 발생한다")
    fun `problemId 빈 값 검증`() {
        // when & then
        val exception = assertThrows<BusinessException> {
            service.generateRetrospectiveTemplate(
                problemId = "",
                code = "print(1)",
                isSuccess = true
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INVALID_INPUT)
        assertThat(exception.message).contains("problemId는 비어 있을 수 없습니다")
    }
}

