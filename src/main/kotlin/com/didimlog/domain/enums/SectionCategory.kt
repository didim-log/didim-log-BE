package com.didimlog.domain.enums

/**
 * 섹션 프리셋 카테고리를 나타내는 Enum
 * 회고 섹션이 어떤 상황에서 사용되는지 구분한다.
 *
 * @property displayName 한글 표시명
 */
enum class SectionCategory(val displayName: String) {
    /**
     * 성공용 섹션 - 문제 해결 성공 시 사용하는 섹션 (최적화, 개선 중심)
     */
    SUCCESS("성공"),

    /**
     * 실패용 섹션 - 문제 해결 실패 시 사용하는 섹션 (디버깅, 학습 중심)
     */
    FAIL("실패"),

    /**
     * 공통 섹션 - 성공/실패 관계없이 사용할 수 있는 섹션 (문서화 중심)
     */
    COMMON("공통")
}
