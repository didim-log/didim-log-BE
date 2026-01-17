package com.didimlog.domain.enums

/**
 * 템플릿 소유권 타입을 나타내는 Enum
 *
 * @property displayName 한글 표시명
 */
enum class TemplateOwnershipType(val displayName: String) {
    /**
     * 시스템 템플릿 - 관리자가 제공하는 기본 템플릿 (수정 불가)
     */
    SYSTEM("시스템"),

    /**
     * 커스텀 템플릿 - 사용자가 직접 생성한 템플릿 (수정 가능)
     */
    CUSTOM("커스텀")
}