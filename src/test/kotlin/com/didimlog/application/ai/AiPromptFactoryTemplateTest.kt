package com.didimlog.application.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AiPromptFactory 템플릿 기반 프롬프트 생성 테스트")
class AiPromptFactoryTemplateTest {

    private val promptFactory = AiPromptFactory()

    @Test
    @DisplayName("createSystemPrompt는 성공 시 성공용 템플릿을 생성한다")
    fun `성공용 시스템 프롬프트 생성`() {
        // when
        val result = promptFactory.createSystemPrompt(isSuccess = true)

        // then
        assertThat(result).contains("# Role")
        assertThat(result).contains("디딤로그 AI")
        assertThat(result).contains("분석 (성공)")
        assertThat(result).contains("효율성")
        assertThat(result).contains("리팩토링 팁")
        assertThat(result).doesNotContain("분석 (실패)")
    }

    @Test
    @DisplayName("createSystemPrompt는 실패 시 실패용 템플릿을 생성한다")
    fun `실패용 시스템 프롬프트 생성`() {
        // when
        val result = promptFactory.createSystemPrompt(isSuccess = false)

        // then
        assertThat(result).contains("# Role")
        assertThat(result).contains("디딤로그 AI")
        assertThat(result).contains("분석 (실패)")
        assertThat(result).contains("실패 원인")
        assertThat(result).contains("학습해야 할 핵심 키워드")
        assertThat(result).doesNotContain("분석 (성공)")
    }

    @Test
    @DisplayName("createUserPrompt는 문제 정보와 사용자 코드를 포함한 프롬프트를 생성한다")
    fun `사용자 프롬프트 생성`() {
        // given
        val problemId = "1000"
        val problemTitle = "A+B"
        val problemDescription = "<p>두 정수를 더하세요</p>"
        val code = "print(1 + 2)"
        val isSuccess = true

        // when
        val result = promptFactory.createUserPrompt(
            problemId = problemId,
            problemTitle = problemTitle,
            problemDescription = problemDescription,
            code = code,
            isSuccess = isSuccess
        )

        // then
        assertThat(result).contains("문제 번호: $problemId")
        assertThat(result).contains("문제 제목: $problemTitle")
        assertThat(result).contains(code)
        assertThat(result).contains("풀이 결과: true")
        assertThat(result).contains("# Input Data")
        assertThat(result).contains("# Instruction")
    }

    @Test
    @DisplayName("createUserPrompt는 HTML 설명이 null이면 기본 메시지를 사용한다")
    fun `HTML 설명 null 처리`() {
        // given
        val problemId = "1000"
        val problemTitle = "A+B"
        val code = "print(1)"

        // when
        val result = promptFactory.createUserPrompt(
            problemId = problemId,
            problemTitle = problemTitle,
            problemDescription = null,
            code = code,
            isSuccess = false
        )

        // then
        assertThat(result).contains("문제 설명을 불러올 수 없습니다")
        assertThat(result).contains("풀이 결과: false")
    }
}

