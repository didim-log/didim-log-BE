package com.didimlog.application.template

import com.didimlog.application.ProblemService
import com.didimlog.application.ai.AiKeywordService
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * 정적 템플릿 생성 서비스
 * AI 서비스가 비활성화되었을 때 사용하는 기본 템플릿을 생성한다.
 * RETROSPECTIVE_STANDARDS.md의 표준 양식을 준수하며, 사용자 작성 영역만 포함한다.
 * AI가 활성화된 경우 키워드를 주입하여 템플릿을 완성한다.
 */
@Service
class StaticTemplateService(
    private val problemService: ProblemService,
    @Autowired(required = false) private val aiKeywordService: AiKeywordService?,
    @Value("\${app.ai.enabled:false}") private val aiEnabled: Boolean
) {
    companion object {
        private const val DEFAULT_ERROR_MESSAGE = "에러 로그를 확인할 수 없습니다."
        private const val DEFAULT_CODE_LANGUAGE = "text"
    }

    /**
     * 정적 회고 템플릿을 생성한다.
     * RETROSPECTIVE_STANDARDS.md에 정의된 구조를 준수하며, AI가 활성화된 경우 키워드를 주입한다.
     *
     * @param problemId 문제 ID
     * @param code 사용자 코드
     * @param isSuccess 풀이 성공 여부
     * @param errorMessage 에러 메시지 (실패 시, nullable)
     * @return 생성된 마크다운 문자열 (AI 키워드가 주입된 상태)
     */
    fun generateRetrospectiveTemplate(
        problemId: String,
        code: String,
        isSuccess: Boolean,
        errorMessage: String? = null
    ): String {
        if (code.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "code는 비어 있을 수 없습니다.")
        }
        if (problemId.isBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "problemId는 비어 있을 수 없습니다.")
        }

        val problem = problemService.getProblemDetail(problemId.toLong())
        val codeLanguage = detectCodeLanguage(code).uppercase()

        val template = createTemplate(problem.id.value, problem.title, codeLanguage, code, isSuccess, errorMessage)

        return injectAiKeywords(template, problemId, code, isSuccess)
    }

    private fun createTemplate(
        problemId: String,
        problemTitle: String,
        codeLanguage: String,
        code: String,
        isSuccess: Boolean,
        errorMessage: String?
    ): String {
        if (isSuccess) {
            return generateSuccessTemplate(problemId, problemTitle, codeLanguage, code)
        }
        val message = errorMessage ?: DEFAULT_ERROR_MESSAGE
        return generateFailureTemplate(problemId, problemTitle, codeLanguage, code, message)
    }

    /**
     * 템플릿에 AI 키워드를 주입한다.
     * AI가 활성화되어 있고 호출에 성공한 경우 키워드를 주입하고, 그렇지 않으면 기본 문구로 대체한다.
     *
     * @param template 기본 템플릿 (플레이스홀더 포함)
     * @param problemId 문제 ID
     * @param code 사용자 코드
     * @param isSuccess 풀이 성공 여부
     * @return 키워드가 주입된 템플릿
     */
    private fun injectAiKeywords(
        template: String,
        problemId: String,
        code: String,
        isSuccess: Boolean
    ): String {
        if (!aiEnabled || aiKeywordService == null) {
            val defaultPlaceholder = getDefaultKeywordsPlaceholder()
            return template.replace("{AI_KEYWORDS_PLACEHOLDER}", defaultPlaceholder)
        }

        val keywordsSection = try {
            val keywords = aiKeywordService.extractKeywords(problemId, code, isSuccess)
            formatKeywords(keywords)
        } catch (e: Exception) {
            // AI 호출 실패 시 기본 문구로 대체 (에러를 터뜨리지 않음)
            getDefaultKeywordsPlaceholder()
        }

        return template.replace("{AI_KEYWORDS_PLACEHOLDER}", keywordsSection)
    }

    /**
     * AI가 추출한 키워드를 마크다운 형식으로 포맷팅한다.
     *
     * @param keywords 쉼표로 구분된 키워드 문자열 (예: "DFS, 백트래킹, 재귀")
     * @return 마크다운 형식의 키워드 리스트
     */
    private fun formatKeywords(keywords: String): String {
        if (keywords.isBlank()) {
            return getDefaultKeywordsPlaceholder()
        }

        val keywordList = keywords.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(3) // 최대 3개만 사용

        if (keywordList.isEmpty()) {
            return getDefaultKeywordsPlaceholder()
        }

        return keywordList.joinToString("\n") { "- $it" }
    }

    /**
     * AI가 비활성화되었거나 호출에 실패한 경우 사용할 기본 문구를 반환한다.
     *
     * @return 기본 키워드 플레이스홀더
     */
    private fun getDefaultKeywordsPlaceholder(): String {
        return "*(AI 서비스가 비활성화되어 직접 키워드를 입력해보세요)*"
    }

    /**
     * 성공 회고 정적 템플릿을 생성한다.
     * RETROSPECTIVE_STANDARDS.md의 "성공 회고" 구조를 준수한다.
     * [User 작성 영역]만 포함: 1. 접근 방법, 2. 복잡도 분석, 제출한 코드
     * AI 키워드는 플레이스홀더로 포함되며, 이후 주입된다.
     */
    private fun generateSuccessTemplate(problemId: String, problemTitle: String, codeLanguage: String, code: String): String {
        val title = "[백준/BOJ] ${problemId}번 $problemTitle ($codeLanguage)"
        return """
            # 🏆 $title 해결 회고

            ## 🔑 추천 학습 키워드 (AI Generated)
            {AI_KEYWORDS_PLACEHOLDER}

            ## 1. 접근 방법 (Approach)

            - 문제를 해결하기 위해 어떤 알고리즘이나 자료구조를 선택했나요?
            - 풀이의 핵심 로직을 한 줄로 요약해 보세요.

            ## 2. 복잡도 분석 (Complexity)

            - 시간 복잡도: O(?)
            - 공간 복잡도: O(?)

            ## 제출한 코드

            ```${codeLanguage.lowercase()}
            $code
            ```
            """.trimIndent()
    }

    /**
     * 실패 회고 정적 템플릿을 생성한다.
     * RETROSPECTIVE_STANDARDS.md의 "실패 회고" 구조를 준수한다.
     * [User 작성 영역]만 포함: 1. 실패 현상, 2. 나의 접근, 제출한 코드, 에러 로그
     * AI 키워드는 플레이스홀더로 포함되며, 이후 주입된다.
     */
    private fun generateFailureTemplate(problemId: String, problemTitle: String, codeLanguage: String, code: String, errorMessage: String): String {
        val title = "[백준/BOJ] ${problemId}번 $problemTitle ($codeLanguage)"
        return """
            # 💥 $title 오답 노트

            ## 🔑 추천 학습 키워드 (AI Generated)
            {AI_KEYWORDS_PLACEHOLDER}

            ## 1. 실패 현상 (Symptom)

            - 어떤 종류의 에러가 발생했나요? (시간 초과, 메모리 초과, 틀렸습니다, 런타임 에러)
            - 테스트 케이스 중 통과하지 못한 예시가 있나요?

            ## 2. 나의 접근 (My Attempt)

            - 어떤 로직으로 풀려고 시도했나요?

            ## 제출한 코드

            ```${codeLanguage.lowercase()}
            $code
            ```

            ## 에러 로그

            ```text
            $errorMessage
            ```
            """.trimIndent()
    }

    /**
     * 코드에서 프로그래밍 언어를 감지한다.
     * 간단한 휴리스틱을 사용하여 언어를 추론한다.
     *
     * @param code 사용자 코드
     * @return 감지된 언어 (기본값: "text")
     */
    private fun detectCodeLanguage(code: String): String {
        val normalizedCode = code.trim()
        if (normalizedCode.isEmpty()) {
            return DEFAULT_CODE_LANGUAGE
        }

        if (normalizedCode.contains("def ") || (normalizedCode.contains("import ") && normalizedCode.contains("print("))) {
            return "python"
        }
        if (normalizedCode.contains("fun ") || normalizedCode.contains("val ") || (normalizedCode.contains("class ") && normalizedCode.contains(":"))) {
            return "kotlin"
        }
        if (normalizedCode.contains("public class") ||
            normalizedCode.contains("public static") ||
            normalizedCode.contains("System.out.println")
        ) {
            return "java"
        }
        if (normalizedCode.contains("#include") || normalizedCode.contains("int main")) {
            return "cpp"
        }
        if (normalizedCode.contains("function ") || normalizedCode.contains("const ") || normalizedCode.contains("let ")) {
            return "javascript"
        }
        if (normalizedCode.contains("package ") && normalizedCode.contains("func ")) {
            return "go"
        }
        if (normalizedCode.contains("fn ") && normalizedCode.contains("let ")) {
            return "rust"
        }
        if (normalizedCode.contains("using ") && normalizedCode.contains("namespace ")) {
            return "csharp"
        }
        return DEFAULT_CODE_LANGUAGE
    }

}



