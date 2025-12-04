package com.didimlog.domain.repository

import com.didimlog.application.retrospective.RetrospectiveSearchCondition
import com.didimlog.domain.Retrospective
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

/**
 * 회고 Repository 커스텀 인터페이스
 * 동적 쿼리 메서드를 정의한다.
 */
interface RetrospectiveRepositoryCustom {

    /**
     * 검색 조건에 따라 회고를 검색한다.
     *
     * @param condition 검색 조건
     * @param pageable 페이징 정보
     * @return 검색 결과 페이지
     */
    fun search(condition: RetrospectiveSearchCondition, pageable: Pageable): Page<Retrospective>
}

