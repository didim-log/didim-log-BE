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
            code = "def solve(a, b):\n    return a + b",
            isSuccess = true
        )

        // then
        assertThat(result).contains("# 🏆 [백준/BOJ] 1000번 A+B (PYTHON) 해결 회고")
        assertThat(result).contains("## 🔑 학습 키워드")
        assertThat(result).contains("- 구현")
        assertThat(result).contains("## 1. 접근 방법 (Approach)")
        assertThat(result).contains("## 2. 복잡도 분석 (Complexity)")
        assertThat(result).contains("## 제출한 코드")
        assertThat(result).contains("def solve(a, b):")
        assertThat(result).contains("```python")
        assertThat(result).contains("## 3. 리팩토링 포인트 (Refactoring)")
        assertThat(result).contains("## 4. 다른 풀이와 비교 (Comparison)")
        assertThat(result).contains("## 5. 다음 액션 (Next)")
    }

    @Test
    @DisplayName("풀이 시간이 포함된 성공 회고 템플릿을 생성한다")
    fun `풀이 시간 포함 성공 회고 템플릿 생성`() {
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
            code = "def solve(a, b):\n    return a + b",
            isSuccess = true,
            solveTime = "15m 30s"
        )

        // then
        assertThat(result).contains("⏱️ **풀이 소요 시간:** 15m 30s")
        assertThat(result).contains("# 🏆 [백준/BOJ] 1000번 A+B (PYTHON) 해결 회고")
    }

    @Test
    @DisplayName("풀이 시간이 포함된 실패 회고 템플릿을 생성한다")
    fun `풀이 시간 포함 실패 회고 템플릿 생성`() {
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
            errorMessage = "IndexError: list index out of range",
            solveTime = "20m 15s"
        )

        // then
        assertThat(result).contains("⏱️ **풀이 소요 시간:** 20m 15s")
        assertThat(result).contains("# 💥 [백준/BOJ] 1000번 A+B (PYTHON) 오답 노트")
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
        assertThat(result).contains("# 💥 [백준/BOJ] 1000번 A+B (PYTHON) 오답 노트")
        assertThat(result).contains("## 1. 실패 현상 (Symptom)")
        assertThat(result).contains("## 2. 나의 접근 (My Attempt)")
        assertThat(result).contains("## 제출한 코드")
        assertThat(result).contains("def solve(): pass")
        assertThat(result).contains("```python")
        assertThat(result).contains("## 에러 로그")
        assertThat(result).contains("IndexError: list index out of range")
        assertThat(result).contains("## 🔑 학습 키워드")
        assertThat(result).contains("- 다이나믹 프로그래밍")
        assertThat(result).contains("## 3. 원인 추정 (Root Cause)")
        assertThat(result).contains("## 4. 반례/재현 케이스 (Counter Example)")
        assertThat(result).contains("## 5. 다음 시도 계획 (Next)")
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
        assertThat(result).contains("## 에러 로그")
    }

    @Test
    @DisplayName("코드 언어를 올바르게 감지한다")
    fun `코드 언어 감지`() {
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

        // when - Python 코드
        val pythonResult = service.generateRetrospectiveTemplate(
            problemId = "1000",
            code = "def solve():\n    pass",
            isSuccess = true
        )

        // then
        assertThat(pythonResult).contains("```python")

        // when - Java 코드
        val javaResult = service.generateRetrospectiveTemplate(
            problemId = "1000",
            code = "public class Solution {\n    public static void main(String[] args) {}\n}",
            isSuccess = true
        )

        // then
        assertThat(javaResult).contains("```java")

        // when - Kotlin 코드
        val kotlinResult = service.generateRetrospectiveTemplate(
            problemId = "1000",
            code = "fun solve(): Int = 0",
            isSuccess = true
        )

        // then
        assertThat(kotlinResult).contains("```kotlin")
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



