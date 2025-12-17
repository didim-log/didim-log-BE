package com.didimlog.infra.ai

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException

@DisplayName("PromptTemplateLoader 테스트")
class PromptTemplateLoaderTest {

    private val templateLoader = PromptTemplateLoader()

    @Test
    @DisplayName("템플릿 파일을 정상적으로 로드한다")
    fun `템플릿 파일 로드 성공`() {
        // when
        val template = templateLoader.loadTemplate("success-retrospective.md")

        // then
        assertThat(template).isNotBlank()
        assertThat(template).contains("# Role")
        assertThat(template).contains("추천 학습 키워드")
        assertThat(template).contains("시니어 개발자 멘토")
    }

    @Test
    @DisplayName("존재하지 않는 템플릿 파일을 로드하면 FileNotFoundException이 발생한다")
    fun `템플릿 파일 없음 예외`() {
        // when & then
        assertThatThrownBy {
            templateLoader.loadTemplate("non-existent-template.md")
        }.isInstanceOf(FileNotFoundException::class.java)
            .hasMessageContaining("템플릿 파일을 찾을 수 없습니다")
    }

    @Test
    @DisplayName("플레이스홀더를 변수 값으로 정확히 치환한다")
    fun `변수 치환 성공`() {
        // given
        val template = """
            문제 번호: {problemId}
            문제 제목: {problemTitle}
            사용자 코드:
            {userCode}
        """.trimIndent()

        val variables = mapOf(
            "problemId" to "1000",
            "problemTitle" to "A+B",
            "userCode" to "print(1 + 2)"
        )

        // when
        val result = templateLoader.replaceVariables(template, variables)

        // then
        assertThat(result).contains("문제 번호: 1000")
        assertThat(result).contains("문제 제목: A+B")
        assertThat(result).contains("print(1 + 2)")
        assertThat(result).doesNotContain("{problemId}")
        assertThat(result).doesNotContain("{problemTitle}")
        assertThat(result).doesNotContain("{userCode}")
    }

    @Test
    @DisplayName("템플릿은 그대로이고 변수만 바뀌었을 때 결과가 예상대로 나온다")
    fun `템플릿 유지 변수만 변경`() {
        // given
        val template = """
            # 문제 분석
            문제 ID: {problemId}
            코드: {userCode}
        """.trimIndent()

        val variables1 = mapOf(
            "problemId" to "1000",
            "userCode" to "code1"
        )

        val variables2 = mapOf(
            "problemId" to "2000",
            "userCode" to "code2"
        )

        // when
        val result1 = templateLoader.replaceVariables(template, variables1)
        val result2 = templateLoader.replaceVariables(template, variables2)

        // then
        assertThat(result1).contains("# 문제 분석")
        assertThat(result1).contains("문제 ID: 1000")
        assertThat(result1).contains("코드: code1")

        assertThat(result2).contains("# 문제 분석")
        assertThat(result2).contains("문제 ID: 2000")
        assertThat(result2).contains("코드: code2")

        // 템플릿 구조는 동일
        assertThat(result1.lines().size).isEqualTo(result2.lines().size)
    }

    @Test
    @DisplayName("치환되지 않은 플레이스홀더는 그대로 유지된다")
    fun `치환되지 않은 플레이스홀더 유지`() {
        // given
        val template = "문제: {problemId}, 코드: {userCode}, 설명: {description}"
        val variables = mapOf(
            "problemId" to "1000",
            "userCode" to "code1"
        )

        // when
        val result = templateLoader.replaceVariables(template, variables)

        // then
        assertThat(result).contains("문제: 1000")
        assertThat(result).contains("코드: code1")
        assertThat(result).contains("설명: {description}") // 치환되지 않음
    }
}

