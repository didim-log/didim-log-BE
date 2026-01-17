package com.didimlog.domain.enums

/**
 * 템플릿 카테고리를 나타내는 Enum
 * 성공/실패 상황에 따라 다른 기본 템플릿을 설정할 수 있도록 구분한다.
 *
 * @property displayName 한글 표시명
 */
enum class TemplateCategory(val displayName: String) {
    /**
     * 성공용 템플릿 - 문제 해결 성공 시 사용하는 템플릿
     */
    SUCCESS("성공"),

    /**
     * 실패용 템플릿 - 문제 해결 실패 시 사용하는 템플릿
     */
    FAIL("실패")
}
