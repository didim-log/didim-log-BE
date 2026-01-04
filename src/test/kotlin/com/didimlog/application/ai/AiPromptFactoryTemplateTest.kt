package com.didimlog.application.ai

import com.didimlog.infra.ai.PromptTemplateLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AiPromptFactory 템플릿 파일 로드 및 Keywords 섹션 검증 테스트")
class AiPromptFactoryTemplateTest {

    private val templateLoader = PromptTemplateLoader()
    private val promptFactory = AiPromptFactory(templateLoader)

    @Test
    @DisplayName("isSuccess가 true일 때 success-retrospective.md 파일이 로드된다")
    fun `성공 시 올바른 템플릿 파일 로드`() {
        // when
        val result = promptFactory.createSystemPrompt(isSuccess = true)

        // then
        // success-retrospective.md의 고유한 내용 확인
        assertThat(result).contains("시니어 개발자 멘토")
        assertThat(result).contains("코드 상세 회고")
        assertThat(result).contains("잘된 점")
        assertThat(result).contains("효율성 분석")
        assertThat(result).contains("개선 가능성")
        
        // failure-retrospective.md의 내용은 포함되지 않아야 함
        assertThat(result).doesNotContain("트러블슈팅 전문가")
        assertThat(result).doesNotContain("실패 분석 회고")
    }

    @Test
    @DisplayName("isSuccess가 false일 때 failure-retrospective.md 파일이 로드된다")
    fun `실패 시 올바른 템플릿 파일 로드`() {
        // when
        val result = promptFactory.createSystemPrompt(isSuccess = false)

        // then
        // failure-retrospective.md의 고유한 내용 확인
        assertThat(result).contains("트러블슈팅 전문가")
        assertThat(result).contains("실패 분석 회고")
        assertThat(result).contains("원인 분석 (Why)")
        assertThat(result).contains("해결 방안 (How)")
        
        // success-retrospective.md의 내용은 포함되지 않아야 함
        assertThat(result).doesNotContain("시니어 개발자 멘토")
        assertThat(result).doesNotContain("코드 상세 회고")
        assertThat(result).doesNotContain("잘된 점")
    }

    @Test
    @DisplayName("성공 프롬프트에 추천 학습 키워드 섹션이 최상단에 포함된다")
    fun `성공 프롬프트 Keywords 섹션 검증`() {
        // when
        val result = promptFactory.createSystemPrompt(isSuccess = true)

        // then
        // Output Format에 Keywords 섹션이 포함되는지 확인
        assertThat(result).contains("🔑 추천 학습 키워드")
        
        // Keywords 섹션이 최상단에 위치하는지 확인 (Output Format 섹션 내에서)
        val outputFormatIndex = result.indexOf("# Output Format")
        val keywordsIndex = result.indexOf("🔑 추천 학습 키워드")
        
        assertThat(outputFormatIndex).isGreaterThan(-1)
        assertThat(keywordsIndex).isGreaterThan(outputFormatIndex)
        
        // Keywords 섹션이 다른 섹션들보다 먼저 나오는지 확인
        val problemDescriptionIndex = result.indexOf("## 📝 문제 설명")
        val codeIndex = result.indexOf("## 💻 나의 풀이")
        
        if (problemDescriptionIndex > -1 && keywordsIndex > -1) {
            assertThat(keywordsIndex).isLessThan(problemDescriptionIndex)
        }
        if (codeIndex > -1 && keywordsIndex > -1) {
            assertThat(keywordsIndex).isLessThan(codeIndex)
        }
    }

    @Test
    @DisplayName("실패 프롬프트에 추천 학습 키워드 섹션이 최상단에 포함된다")
    fun `실패 프롬프트 Keywords 섹션 검증`() {
        // when
        val result = promptFactory.createSystemPrompt(isSuccess = false)

        // then
        // Output Format에 Keywords 섹션이 포함되는지 확인
        assertThat(result).contains("🔑 추천 학습 키워드")
        
        // Keywords 섹션이 최상단에 위치하는지 확인 (Output Format 섹션 내에서)
        val outputFormatIndex = result.indexOf("# Output Format")
        val keywordsIndex = result.indexOf("🔑 추천 학습 키워드")
        
        assertThat(outputFormatIndex).isGreaterThan(-1)
        assertThat(keywordsIndex).isGreaterThan(outputFormatIndex)
        
        // Keywords 섹션이 다른 섹션들보다 먼저 나오는지 확인
        val problemDescriptionIndex = result.indexOf("## 📝 문제 설명")
        val codeIndex = result.indexOf("## 💻 나의 풀이")
        val failureAnalysisIndex = result.indexOf("## ❌ 실패 분석")
        
        if (problemDescriptionIndex > -1 && keywordsIndex > -1) {
            assertThat(keywordsIndex).isLessThan(problemDescriptionIndex)
        }
        if (codeIndex > -1 && keywordsIndex > -1) {
            assertThat(keywordsIndex).isLessThan(codeIndex)
        }
        if (failureAnalysisIndex > -1 && keywordsIndex > -1) {
            assertThat(keywordsIndex).isLessThan(failureAnalysisIndex)
        }
    }

    @Test
    @DisplayName("성공 프롬프트에 키워드 3~4개를 제시하라는 지시사항이 포함된다")
    fun `성공 프롬프트 키워드 개수 지시사항 검증`() {
        // when
        val result = promptFactory.createSystemPrompt(isSuccess = true)

        // then
        assertThat(result).contains("3~4개")
        assertThat(result).contains("더 깊이 공부하면 좋을 키워드")
    }

    @Test
    @DisplayName("실패 프롬프트에 키워드 3~4개를 제시하라는 지시사항이 포함된다")
    fun `실패 프롬프트 키워드 개수 지시사항 검증`() {
        // when
        val result = promptFactory.createSystemPrompt(isSuccess = false)

        // then
        assertThat(result).contains("3~4개")
        assertThat(result).contains("CS 지식이나 프레임워크 동작 원리와 관련된 키워드")
    }
}












