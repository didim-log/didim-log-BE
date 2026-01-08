package com.didimlog.infra.solvedac

import com.didimlog.domain.enums.ProblemCategory

/**
 * Solved.ac API 응답의 태그를 ProblemCategory Enum으로 변환하는 유틸리티
 * 한글 태그를 영문 표준명으로 매핑하여 데이터 일관성을 유지한다.
 */
object ProblemCategoryMapper {

    /**
     * Solved.ac API의 태그 배열에서 한글 태그를 추출하여 영문 표준명으로 변환한다.
     * 한국어 displayName이 있으면 우선 사용하고, 없으면 key를 사용한다.
     * 변환된 영문 표준명 리스트를 반환한다.
     *
     * @param tags Solved.ac API에서 받은 태그 리스트
     * @return 영문 표준명으로 변환된 태그 리스트 (중복 제거됨)
     */
    fun extractTagsToEnglish(tags: List<SolvedAcTag>): List<String> {
        return tags.mapNotNull { tag ->
            val koreanName = tag.displayNames
                .firstOrNull { it.language == "ko" }
                ?.name

            val tagName = koreanName ?: tag.key
            val category = ProblemCategory.fromKorean(tagName)
            category.englishName
        }.distinct() // 중복 제거
    }

    /**
     * 태그 리스트에서 첫 번째 태그를 카테고리로 결정한다.
     * 태그가 없으면 IMPLEMENTATION을 기본값으로 사용한다.
     *
     * @param tags 영문 표준명으로 변환된 태그 리스트
     * @return 첫 번째 태그에 해당하는 ProblemCategory (태그가 없으면 IMPLEMENTATION)
     */
    fun determineCategory(tags: List<String>): ProblemCategory {
        if (tags.isEmpty()) {
            return ProblemCategory.IMPLEMENTATION
        }

        // tags는 이미 영문 표준명으로 변환되어 있음
        // 첫 번째 태그의 영문명을 찾아서 ProblemCategory로 변환
        val firstTagEnglish = tags.first()
        return ProblemCategory.entries.find { it.englishName == firstTagEnglish }
            ?: ProblemCategory.UNKNOWN
    }
}













