package com.didimlog.application.template

import com.didimlog.application.ProblemService
import com.didimlog.application.ai.AiKeywordService
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
    private val aiKeywordService: AiKeywordService? = null
    private val service = StaticTemplateService(problemService, aiKeywordService, false)

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
        assertThat(result).contains("## 🔑 추천 학습 키워드 (AI Generated)")
        assertThat(result).contains("## 1. 접근 방법 (Approach)")
        assertThat(result).contains("## 2. 복잡도 분석 (Complexity)")
        assertThat(result).contains("## 제출한 코드")
        assertThat(result).contains("def solve(a, b):")
        assertThat(result).contains("```python")
        // AI가 비활성화된 경우 기본 플레이스홀더가 포함되어야 함
        assertThat(result).contains("*(AI 서비스가 비활성화되어 직접 키워드를 입력해보세요)*")
        // AI 섹션이 포함되지 않아야 함
        assertThat(result).doesNotContain("## 3. 리팩토링 제안")
        assertThat(result).doesNotContain("## 4. 모범 답안 비교")
        assertThat(result).doesNotContain("## 5. 심화 학습 키워드")
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
        // AI 키워드 섹션이 포함되어야 함
        assertThat(result).contains("## 🔑 추천 학습 키워드 (AI Generated)")
        assertThat(result).contains("*(AI 서비스가 비활성화되어 직접 키워드를 입력해보세요)*")
        // AI 섹션이 포함되지 않아야 함
        assertThat(result).doesNotContain("## 3. 원인 분석")
        assertThat(result).doesNotContain("## 4. 반례 제안")
        assertThat(result).doesNotContain("## 5. 해결 가이드")
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

    @Test
    @DisplayName("AI가 활성화되고 키워드 추출에 성공하면 키워드가 주입된다")
    fun `AI 키워드 주입 성공`() {
        // given
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000"
        )

        val mockAiKeywordService: AiKeywordService = mockk()
        every { mockAiKeywordService.extractKeywords("1000", "def solve(): pass", true) } returns "DFS, 백트래킹, 재귀"

        every { problemService.getProblemDetail(1000L) } returns problem

        val serviceWithAi = StaticTemplateService(problemService, mockAiKeywordService, true)

        // when
        val result = serviceWithAi.generateRetrospectiveTemplate(
            problemId = "1000",
            code = "def solve(): pass",
            isSuccess = true
        )

        // then
        assertThat(result).contains("## 🔑 추천 학습 키워드 (AI Generated)")
        assertThat(result).contains("- DFS")
        assertThat(result).contains("- 백트래킹")
        assertThat(result).contains("- 재귀")
        assertThat(result).doesNotContain("{AI_KEYWORDS_PLACEHOLDER}")
        assertThat(result).doesNotContain("*(AI 서비스가 비활성화되어 직접 키워드를 입력해보세요)*")
    }

    @Test
    @DisplayName("AI가 활성화되었지만 호출에 실패하면 기본 문구로 대체된다")
    fun `AI 키워드 추출 실패 시 기본 문구`() {
        // given
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000"
        )

        val mockAiKeywordService: AiKeywordService = mockk()
        every { mockAiKeywordService.extractKeywords(any(), any(), any()) } throws RuntimeException("AI 호출 실패")

        every { problemService.getProblemDetail(1000L) } returns problem

        val serviceWithAi = StaticTemplateService(problemService, mockAiKeywordService, true)

        // when
        val result = serviceWithAi.generateRetrospectiveTemplate(
            problemId = "1000",
            code = "def solve(): pass",
            isSuccess = true
        )

        // then
        assertThat(result).contains("## 🔑 추천 학습 키워드 (AI Generated)")
        assertThat(result).contains("*(AI 서비스가 비활성화되어 직접 키워드를 입력해보세요)*")
        assertThat(result).doesNotContain("{AI_KEYWORDS_PLACEHOLDER}")
        // 에러가 발생하지 않고 정상적으로 템플릿이 반환되어야 함
    }

    @Test
    @DisplayName("키워드가 3개 이상인 경우 최대 3개만 사용한다")
    fun `키워드 3개 제한`() {
        // given
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000"
        )

        val mockAiKeywordService: AiKeywordService = mockk()
        every { mockAiKeywordService.extractKeywords("1000", "code", true) } returns "DFS, 백트래킹, 재귀, 동적 프로그래밍, 그래프"

        every { problemService.getProblemDetail(1000L) } returns problem

        val serviceWithAi = StaticTemplateService(problemService, mockAiKeywordService, true)

        // when
        val result = serviceWithAi.generateRetrospectiveTemplate(
            problemId = "1000",
            code = "code",
            isSuccess = true
        )

        // then
        assertThat(result).contains("- DFS")
        assertThat(result).contains("- 백트래킹")
        assertThat(result).contains("- 재귀")
        assertThat(result).doesNotContain("- 동적 프로그래밍")
        assertThat(result).doesNotContain("- 그래프")
    }
}



