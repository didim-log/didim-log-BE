package com.didimlog.domain.enums

/**
 * 회고 템플릿 타입을 나타내는 Enum
 *
 * @property displayName 한글 표시명
 */
enum class TemplateType(val displayName: String) {
    /**
     * 요약 템플릿 - 핵심만 빠르게 작성할 수 있는 간단한 템플릿
     */
    SIMPLE("요약"),

    /**
     * 상세 템플릿 - 5단계 구조를 포함한 상세한 템플릿
     */
    DETAIL("상세")
}
