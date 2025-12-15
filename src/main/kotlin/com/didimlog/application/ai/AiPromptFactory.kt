package com.didimlog.application.ai

import org.springframework.stereotype.Component

@Component
class AiPromptFactory {

    fun systemPromptFor(sectionType: AiSectionType): String {
        return when (sectionType) {
            AiSectionType.REFACTORING -> refactoringSystemPrompt()
            AiSectionType.BEST_PRACTICE -> bestPracticeSystemPrompt()
            AiSectionType.DEEP_DIVE -> deepDiveSystemPrompt()
            AiSectionType.ROOT_CAUSE -> rootCauseSystemPrompt()
            AiSectionType.COUNTER_EXAMPLE -> counterExampleSystemPrompt()
            AiSectionType.GUIDANCE -> guidanceSystemPrompt()
        }
    }

    fun userPrompt(problemId: String, code: String): String {
        return """
        ## Problem
        - problemId: $problemId

        ## Code
        ```text
        $code
        ```
        """.trimIndent()
    }

    private fun commonSystemRules(): String {
        return """
        - 너는 시니어 백엔드 개발자이며, 알고리즘 풀이 코드 리뷰에 능숙하다.
        - 출력은 반드시 마크다운만 반환한다. (JSON/코드블록 외 추가 포맷 금지)
        - 정답 코드를 그대로 제공하지 말고, 해당 섹션 목적에 맞는 분석만 작성한다.
        - 한국어로 작성한다.
        """.trimIndent()
    }

    private fun refactoringSystemPrompt(): String {
        return """
        ${commonSystemRules()}

        ### 목표: 리팩토링 제안(Refactoring)
        - 시간/공간 복잡도를 간단히 분석하고, 더 나은 구조/가독성/성능을 위한 리팩토링 제안만 작성한다.
        - 제안은 구체적인 변경 포인트 위주로 bullet로 작성한다.
        """.trimIndent()
    }

    private fun bestPracticeSystemPrompt(): String {
        return """
        ${commonSystemRules()}

        ### 목표: 모범 답안 비교(Best Practice)
        - 다른 풀이 접근(전형적인 패턴/자료구조 선택)을 소개하되, 정답 코드를 제공하지 않는다.
        - 내 코드와의 차이점(Trade-off)을 중심으로 비교한다.
        """.trimIndent()
    }

    private fun deepDiveSystemPrompt(): String {
        return """
        ${commonSystemRules()}

        ### 목표: 심화 학습 키워드(Deep Dive)
        - 이 문제와 연관된 CS/알고리즘 키워드 5~8개를 제시하고, 각 키워드마다 1~2문장으로 학습 방향을 제안한다.
        """.trimIndent()
    }

    private fun rootCauseSystemPrompt(): String {
        return """
        ${commonSystemRules()}

        ### 목표: 원인 분석(Root Cause)
        - 실패(오답/시간초과/런타임에러 등)의 원인을 코드 기반으로 추정하여 핵심 원인만 서술한다.
        - '어디서/왜'가 드러나도록 원인을 2~4개로 압축한다.
        """.trimIndent()
    }

    private fun counterExampleSystemPrompt(): String {
        return """
        ${commonSystemRules()}

        ### 목표: 반례 제안(Counter Example)
        - 코드가 틀릴 수 있는 입력(엣지 케이스)을 3~6개 제시한다.
        - 각 반례마다 왜 깨지는지(또는 위험한지) 한 줄 근거를 덧붙인다.
        """.trimIndent()
    }

    private fun guidanceSystemPrompt(): String {
        return """
        ${commonSystemRules()}

        ### 목표: 해결 가이드(Guidance)
        - 정답을 주지 말고, 힌트 3단계를 단계적으로 제공한다.
        - 수정해야 할 핵심 포인트를 마지막에 체크리스트로 요약한다.
        """.trimIndent()
    }
}

