package com.didimlog.domain.repository

import com.didimlog.application.retrospective.RetrospectiveSearchCondition
import com.didimlog.domain.Retrospective
import com.didimlog.domain.enums.RankingPeriod
import java.time.LocalDateTime
import org.bson.Document
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.Aggregation.group
import org.springframework.data.mongodb.core.aggregation.Aggregation.limit
import org.springframework.data.mongodb.core.aggregation.Aggregation.lookup
import org.springframework.data.mongodb.core.aggregation.Aggregation.match
import org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation
import org.springframework.data.mongodb.core.aggregation.Aggregation.project
import org.springframework.data.mongodb.core.aggregation.Aggregation.skip
import org.springframework.data.mongodb.core.aggregation.Aggregation.sort
import org.springframework.data.mongodb.core.aggregation.Aggregation.unwind
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

    override fun findTopStudentsByRetrospectiveCount(
        period: RankingPeriod,
        pageable: Pageable
    ): Page<StudentRetrospectiveCount> {
        val criteria = buildPeriodCriteria(period)

        val basePipeline = mutableListOf(
            match(criteria),
            group("studentId").count().`as`("retrospectiveCount"),
            project("retrospectiveCount").and("_id").`as`("studentId"),
            lookup("students", "studentId", "_id", "student"),
            unwind("student", true),
            sort(
                Sort.by(
                    Sort.Order.desc("retrospectiveCount"),
                    Sort.Order.desc("student.rating"),
                    Sort.Order.asc("studentId")
                )
            )
        )

        val dataPipeline = basePipeline.toMutableList().apply {
            add(skip(pageable.offset))
            add(limit(pageable.pageSize.toLong()))
            add(project("studentId", "retrospectiveCount"))
        }

        val totalPipeline = listOf(
            match(criteria),
            group("studentId"),
            group().count().`as`("total")
        )

        val aggregation = newAggregation(
            Aggregation.facet(*dataPipeline.toTypedArray()).`as`("data")
                .and(*totalPipeline.toTypedArray()).`as`("total")
        )

        val result = mongoTemplate.aggregate(aggregation, "retrospectives", Document::class.java).uniqueMappedResult
            ?: return PageImpl(emptyList(), pageable, 0)

        val content = (result["data"] as? List<*>)?.mapNotNull { raw ->
            val doc = raw as? Document ?: return@mapNotNull null
            StudentRetrospectiveCount(
                studentId = doc.getString("studentId"),
                retrospectiveCount = (doc.get("retrospectiveCount") as Number).toLong()
            )
        }.orEmpty()

        val total = (result["total"] as? List<*>)?.firstOrNull()?.let { raw ->
            val doc = raw as? Document ?: return@let 0L
            (doc.get("total") as Number).toLong()
        } ?: 0L

        return PageImpl(content, pageable, total)
    }

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

    private fun buildPeriodCriteria(period: RankingPeriod): Criteria {
        if (period == RankingPeriod.TOTAL) {
            return Criteria()
        }

        val now = LocalDateTime.now()
        val from = when (period) {
            RankingPeriod.DAILY -> now.minusDays(1)
            RankingPeriod.WEEKLY -> now.minusDays(7)
            RankingPeriod.MONTHLY -> now.minusMonths(1)
            RankingPeriod.TOTAL -> now
        }
        return Criteria("createdAt").gte(from)
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
