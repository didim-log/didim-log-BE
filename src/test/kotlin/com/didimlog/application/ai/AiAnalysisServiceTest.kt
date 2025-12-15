package com.didimlog.application.ai

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AiAnalysisService 테스트")
class AiAnalysisServiceTest {

    private val llmClient: LlmClient = mockk()
    private val promptFactory = AiPromptFactory()
    private val service = AiAnalysisService(llmClient, promptFactory)

    @Test
    @DisplayName("sectionType에 따라 시스템 프롬프트를 선택해 LLM에 전달한다")
    fun `select prompt by sectionType`() {
        every { llmClient.generateMarkdown(any(), any()) } returns "result"

        val result = service.analyze(
            code = "print(1)",
            problemId = "1000",
            sectionType = AiSectionType.REFACTORING
        )

        assertThat(result).isEqualTo("result")
        verify(exactly = 1) {
            llmClient.generateMarkdown(
                match { it.contains("리팩토링") },
                match { it.contains("problemId: 1000") && it.contains("print(1)") }
            )
        }
    }
}

