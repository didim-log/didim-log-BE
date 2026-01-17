package com.didimlog.application.template

import com.didimlog.application.ProblemService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.TemplateType
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.util.CodeLanguageDetector
import org.springframework.stereotype.Service

/**
 * 정적 템플릿 생성 서비스
 * 회고 작성에 필요한 정적 마크다운 템플릿을 생성한다.
 *
 * 정책:
 * - DidimLog의 AI는 "로그(Log) 한 줄 평가" 용도로만 사용한다.
 * - 회고(Retrospective) 템플릿은 사용자가 작성할 목차와 본인 코드를 포함한 마크다운만 제공한다.
 * - `DOCS/RETROSPECTIVE_STANDARDS.md`의 표준 목차(성공/실패 1~5)를 따른다.
 */
@Service
class StaticTemplateService(
    private val problemService: ProblemService
) {
    companion object {
        private const val DEFAULT_ERROR_MESSAGE = "에러 로그를 확인할 수 없습니다."
        private const val DEFAULT_CODE_LANGUAGE = "text"
        private const val MAX_KEYWORDS = 5
    }

    /**
     * 정적 회고 템플릿을 생성한다.
     * RETROSPECTIVE_STANDARDS.md에 정의된 구조를 준수한다.
     *
     * @param problemId 문제 ID
     * @param code 사용자 코드
     * @param isSuccess 풀이 성공 여부
     * @param errorMessage 에러 메시지 (실패 시, nullable)
     * @param solveTime 풀이 소요 시간 (선택, nullable)
     * @param templateType 템플릿 타입 (기본값: SIMPLE)
     * @return 생성된 마크다운 문자열
     */
    fun generateRetrospectiveTemplate(
        problemId: String,
        code: String,
        isSuccess: Boolean,
        errorMessage: String? = null,
        solveTime: String? = null,
        templateType: TemplateType = TemplateType.SIMPLE
    ): String {
        if (code.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "code는 비어 있을 수 없습니다.")
        }
        if (problemId.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "problemId는 비어 있을 수 없습니다.")
        }

        val problem = problemService.getProblemDetail(problemId.toLong())
        val codeLanguage = CodeLanguageDetector.detect(code) // 대문자: "PYTHON", "JAVA", etc.
        val markdownLanguage = toMarkdownLanguage(codeLanguage) // 소문자: "python", "java", etc.

        return createTemplate(problem, codeLanguage, markdownLanguage, code, isSuccess, errorMessage, solveTime, templateType)
    }

    /**
     * 언어 코드를 마크다운 코드 블록 형식으로 변환한다.
     * 예: "CSHARP" -> "csharp", "JAVA" -> "java", "CPP" -> "cpp"
     */
    private fun toMarkdownLanguage(language: String): String {
        return when (language) {
            "CSHARP" -> "csharp"
            "JAVASCRIPT" -> "javascript"
            "CPP" -> "cpp"
            else -> language.lowercase()
        }
    }

    private fun createTemplate(
        problem: Problem,
        codeLanguage: String, // 대문자: "PYTHON", "JAVA", etc. (제목용)
        markdownLanguage: String, // 소문자: "python", "java", etc. (코드 블록용)
        code: String,
        isSuccess: Boolean,
        errorMessage: String?,
        solveTime: String?,
        templateType: TemplateType
    ): String {
        val title = "[백준/BOJ] ${problem.id.value}번 ${problem.title} ($codeLanguage)"
        
        if (isSuccess) {
            return when (templateType) {
                TemplateType.SIMPLE -> RetrospectiveTemplates.generateSimpleSuccess(title, markdownLanguage, code, solveTime)
                TemplateType.DETAIL -> {
                    val keywords = formatKeywords(buildProblemKeywords(problem))
                    RetrospectiveTemplates.generateDetailSuccess(title, keywords, markdownLanguage, code, solveTime)
                }
            }
        }
        val message = errorMessage ?: DEFAULT_ERROR_MESSAGE
        return when (templateType) {
            TemplateType.SIMPLE -> RetrospectiveTemplates.generateSimpleFailure(title, markdownLanguage, code, message, solveTime)
            TemplateType.DETAIL -> {
                val keywords = formatKeywords(buildProblemKeywords(problem))
                RetrospectiveTemplates.generateDetailFailure(title, keywords, markdownLanguage, code, message, solveTime)
            }
        }
    }

    private fun buildProblemKeywords(problem: Problem): List<String> {
        val keywords = mutableListOf<String>()

        keywords.add(problem.category.koreanName)

        keywords.addAll(problem.tags.map { mapTagToKeyword(it) })

        return keywords
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_KEYWORDS)
    }

    private fun mapTagToKeyword(tag: String): String {
        val normalized = tag.trim()
        if (normalized.isBlank()) {
            return normalized
        }
        val matched = ProblemCategory.entries.find { it.englishName.equals(normalized, ignoreCase = true) }
        return matched?.koreanName ?: normalized
    }

    private fun formatKeywords(keywords: List<String>): String {
        if (keywords.isEmpty()) {
            return "- (키워드를 추가로 적어보세요)"
        }

        // trimIndent()가 적용되므로 들여쓰기 없이 반환
        return keywords.joinToString("\n") { "- $it" }
    }


}



