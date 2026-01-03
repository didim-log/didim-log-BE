package com.didimlog.application.ai

import com.didimlog.application.ProblemService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AiKeywordService 테스트")
class AiKeywordServiceTest {

    private val llmClient: LlmClient = mockk()
    private val problemService: ProblemService = mockk()
    private val service = AiKeywordService(llmClient, problemService)

    @Test
    @DisplayName("성공 케이스에서 키워드를 추출한다")
    fun `성공 케이스 키워드 추출`() {
        // given
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000"
        )

        every { problemService.getProblemDetail(1000L) } returns problem
        every { llmClient.extractKeywords(any(), any()) } returns "DFS, 백트래킹, 재귀"

        // when
        val result = service.extractKeywords("1000", "def solve(): pass", true)

        // then
        assertThat(result).isEqualTo("DFS, 백트래킹, 재귀")
    }

    @Test
    @DisplayName("실패 케이스에서 키워드를 추출한다")
    fun `실패 케이스 키워드 추출`() {
        // given
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000"
        )

        every { problemService.getProblemDetail(1000L) } returns problem
        every { llmClient.extractKeywords(any(), any()) } returns "시간 복잡도, 배열 인덱싱, 경계 조건"

        // when
        val result = service.extractKeywords("1000", "def solve(): pass", false)

        // then
        assertThat(result).isEqualTo("시간 복잡도, 배열 인덱싱, 경계 조건")
    }

    @Test
    @DisplayName("키워드 추출 실패 시 예외를 전파한다")
    fun `키워드 추출 실패`() {
        // given
        val problem = Problem(
            id = ProblemId("1000"),
            title = "A+B",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/1000"
        )

        every { problemService.getProblemDetail(1000L) } returns problem
        every { llmClient.extractKeywords(any(), any()) } throws RuntimeException("AI 호출 실패")

        // when & then
        org.junit.jupiter.api.assertThrows<RuntimeException> {
            service.extractKeywords("1000", "code", true)
        }
    }
}





