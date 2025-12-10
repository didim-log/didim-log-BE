package com.didimlog.domain.repository

import com.didimlog.application.retrospective.RetrospectiveSearchCondition
import com.didimlog.domain.Retrospective
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository

/**
 * 회고 Repository 구현체
 * MongoDB의 Criteria를 사용하여 동적 쿼리를 구현한다.
 */
@Repository
class RetrospectiveRepositoryImpl(
    private val mongoTemplate: MongoTemplate
) : RetrospectiveRepositoryCustom {

    override fun search(condition: RetrospectiveSearchCondition, pageable: Pageable): Page<Retrospective> {
        val criteria = buildCriteria(condition)
        val query = Query(criteria)

        // 정렬 적용
        applySort(query, pageable.sort)

        // 전체 개수 조회
        val total = mongoTemplate.count(query, Retrospective::class.java)

        // 페이징 적용
        query.with(pageable)

        // 데이터 조회
        val content = mongoTemplate.find(query, Retrospective::class.java)

        return PageImpl(content, pageable, total)
    }

    private fun buildCriteria(condition: RetrospectiveSearchCondition): Criteria {
        val criteriaList = mutableListOf<Criteria>()

        // 학생 ID 필터
        if (condition.studentId != null) {
            criteriaList.add(Criteria("studentId").`is`(condition.studentId))
        }

        // 키워드 검색 (내용 또는 문제 ID)
        if (!condition.keyword.isNullOrBlank()) {
            criteriaList.add(
                Criteria().orOperator(
                    Criteria("content").regex(condition.keyword, "i"),
                    Criteria("problemId").regex(condition.keyword, "i")
                )
            )
        }

        // 카테고리 필터
        if (condition.category != null) {
            criteriaList.add(Criteria("mainCategory").`is`(condition.category))
        }

        // 북마크 필터
        if (condition.isBookmarked == true) {
            criteriaList.add(Criteria("isBookmarked").`is`(true))
        }

        if (criteriaList.isEmpty()) {
            return Criteria()
        }
        return Criteria().andOperator(*criteriaList.toTypedArray())
    }

    private fun applySort(query: Query, sort: Sort) {
        if (sort.isSorted) {
            query.with(sort)
            return
        }
        // 기본 정렬: createdAt DESC
        query.with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
    }
}
