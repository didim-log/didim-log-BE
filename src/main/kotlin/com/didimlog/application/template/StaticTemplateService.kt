package com.didimlog.application.template

import com.didimlog.application.ProblemService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
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
     * @return 생성된 마크다운 문자열
     */
    fun generateRetrospectiveTemplate(
        problemId: String,
        code: String,
        isSuccess: Boolean,
        errorMessage: String? = null,
        solveTime: String? = null
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

        return createTemplate(problem, codeLanguage, markdownLanguage, code, isSuccess, errorMessage, solveTime)
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
        solveTime: String?
    ): String {
        if (isSuccess) {
            return generateSuccessTemplate(problem, codeLanguage, markdownLanguage, code, solveTime)
        }
        val message = errorMessage ?: DEFAULT_ERROR_MESSAGE
        return generateFailureTemplate(problem, codeLanguage, markdownLanguage, code, message, solveTime)
    }

    /**
     * 성공 회고 정적 템플릿을 생성한다.
     * RETROSPECTIVE_STANDARDS.md의 "성공 회고" 구조를 준수한다.
     * - 1~5 모두 사용자가 작성하는 목차를 제공한다.
     */
    private fun generateSuccessTemplate(problem: Problem, codeLanguage: String, markdownLanguage: String, code: String, solveTime: String?): String {
        val title = "[백준/BOJ] ${problem.id.value}번 ${problem.title} ($codeLanguage)"
        val keywords = buildProblemKeywords(problem)
        return buildString {
            appendLine("# 🏆 $title 해결 회고")
            appendLine()
            appendLine("## 🔑 학습 키워드")
            appendLine()
            appendLine(formatKeywords(keywords))
            appendLine()
            appendLine("## 1. 접근 방법 (Approach)")
            appendLine()
            appendLine("- 문제를 해결하기 위해 어떤 알고리즘이나 자료구조를 선택했나요?")
            appendLine("- 풀이의 핵심 로직을 한 줄로 요약해 보세요.")
            appendLine()
            appendLine("## 2. 복잡도 분석 (Complexity)")
            appendLine()
            appendLine("- 시간 복잡도: O(?)")
            appendLine("- 공간 복잡도: O(?)")
            if (solveTime != null && solveTime.isNotBlank()) {
                appendLine("- 풀이 소요 시간: $solveTime")
            }
            appendLine()
            appendLine("## 3. 리팩토링 포인트 (Refactoring)")
            appendLine()
            appendLine("- 개선할 수 있는 변수/함수명, 중복 제거, 로직 단순화 포인트를 적어보세요.")
            appendLine()
            appendLine("## 4. 다른 풀이와 비교 (Comparison)")
            appendLine()
            appendLine("- 다른 사람의 풀이(또는 표준 풀이)와 비교해서 내 풀이의 장단점을 정리해보세요.")
            appendLine()
            appendLine("## 5. 다음 액션 (Next)")
            appendLine()
            appendLine("- 다음에 같은 유형을 만나면 어떤 점을 더 잘할지 한 줄로 적어보세요.")
            appendLine()
            appendLine("## 제출한 코드")
            appendLine()
            appendLine("```$markdownLanguage")
            appendLine(code)
            appendLine("```")
            appendLine()
            appendLine("---")
            appendLine("Generated by DidimLog")
        }
    }

    /**
     * 실패 회고 정적 템플릿을 생성한다.
     * RETROSPECTIVE_STANDARDS.md의 "실패 회고" 구조를 준수한다.
     * - 1~5 모두 사용자가 작성하는 목차를 제공한다.
     */
    private fun generateFailureTemplate(problem: Problem, codeLanguage: String, markdownLanguage: String, code: String, errorMessage: String, solveTime: String?): String {
        val title = "[백준/BOJ] ${problem.id.value}번 ${problem.title} ($codeLanguage)"
        val keywords = buildProblemKeywords(problem)
        return buildString {
            appendLine("# 💥 $title 오답 노트")
            appendLine()
            appendLine("## 🔑 학습 키워드")
            appendLine()
            appendLine(formatKeywords(keywords))
            appendLine()
            appendLine("## 1. 실패 현상 (Symptom)")
            appendLine()
            appendLine("- 어떤 종류의 에러가 발생했나요? (시간 초과, 메모리 초과, 틀렸습니다, 런타임 에러)")
            appendLine("- 테스트 케이스 중 통과하지 못한 예시가 있나요?")
            appendLine()
            appendLine("## 2. 나의 접근 (My Attempt)")
            appendLine()
            appendLine("- 어떤 로직으로 풀려고 시도했나요?")
            appendLine()
            appendLine("## 3. 원인 추정 (Root Cause)")
            appendLine()
            appendLine("- 왜 실패했다고 생각하나요? (논리/구현/복잡도/입출력 등)")
            if (solveTime != null && solveTime.isNotBlank()) {
                appendLine("- 풀이 소요 시간: $solveTime")
            }
            appendLine()
            appendLine("## 4. 반례/재현 케이스 (Counter Example)")
            appendLine()
            appendLine("- 내 코드를 깨뜨리는 입력을 적어보세요.")
            appendLine()
            appendLine("## 5. 다음 시도 계획 (Next)")
            appendLine()
            appendLine("- 다음 시도에서 바꿀 점을 체크리스트로 적어보세요.")
            appendLine()
            appendLine("## 제출한 코드")
            appendLine()
            appendLine("```$markdownLanguage")
            appendLine(code)
            appendLine("```")
            appendLine()
            appendLine("## 에러 로그")
            appendLine()
            appendLine("```text")
            appendLine(errorMessage)
            appendLine("```")
            appendLine()
            appendLine("---")
            appendLine("Generated by DidimLog")
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



