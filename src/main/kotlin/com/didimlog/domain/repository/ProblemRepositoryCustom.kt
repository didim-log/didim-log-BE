package com.didimlog.domain.repository

import com.didimlog.domain.Problem

/**
 * Problem Repository 커스텀 인터페이스
 * 확장된 태그 리스트로 검색하는 메서드를 정의한다.
 */
interface ProblemRepositoryCustom {

    /**
     * 레벨 범위와 확장된 태그 리스트로 문제를 검색한다.
     * 문제의 tags 리스트 중 하나라도 확장된 태그 목록에 포함되면 검색된다.
     *
     * @param min 최소 레벨
     * @param max 최대 레벨
     * @param expandedTags 확장된 태그 리스트 (상위 카테고리 + 하위 태그들)
     * @return 검색된 문제 목록
     */
    fun findByLevelBetweenAndTagsIn(min: Int, max: Int, expandedTags: List<String>): List<Problem>
}

