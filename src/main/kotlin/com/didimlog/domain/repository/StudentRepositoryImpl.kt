package com.didimlog.domain.repository

import com.didimlog.domain.Student
import java.time.LocalDateTime
import java.util.regex.Pattern
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository

@Repository
class StudentRepositoryImpl(
    private val mongoTemplate: MongoTemplate
) : StudentRepositoryCustom {

    override fun searchAdminUsers(
        pageable: Pageable,
        search: String?,
        createdAtFrom: LocalDateTime?,
        createdAtTo: LocalDateTime?
    ): Page<Student> {
        val criteria = buildCriteria(search, createdAtFrom, createdAtTo)
        val total = mongoTemplate.count(newQuery(criteria), Student::class.java)
        if (total == 0L || pageable.offset >= total) {
            return PageImpl(emptyList(), pageable, total)
        }

        val query = newQuery(criteria)
            .with(stableSort(pageable.sort))
            .skip(pageable.offset)
            .limit(pageable.pageSize)
        val content = mongoTemplate.find(query, Student::class.java)

        return PageImpl(content, pageable, total)
    }

    private fun buildCriteria(
        search: String?,
        createdAtFrom: LocalDateTime?,
        createdAtTo: LocalDateTime?
    ): Criteria? {
        val criteria = mutableListOf<Criteria>()

        if (!search.isNullOrBlank()) {
            val literalPattern = Pattern.compile(
                Pattern.quote(search),
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            )
            criteria += Criteria().orOperator(
                Criteria.where("nickname").regex(literalPattern),
                Criteria.where("bojId").regex(literalPattern),
                Criteria.where("email").regex(literalPattern)
            )
        }

        createdAtCriteria(createdAtFrom, createdAtTo)?.let(criteria::add)

        return when (criteria.size) {
            0 -> null
            1 -> criteria.single()
            else -> Criteria().andOperator(*criteria.toTypedArray())
        }
    }

    private fun createdAtCriteria(
        createdAtFrom: LocalDateTime?,
        createdAtTo: LocalDateTime?
    ): Criteria? {
        if (createdAtFrom == null && createdAtTo == null) {
            return null
        }

        var persistedCreatedAt = Criteria.where("createdAt")
        if (createdAtFrom != null) {
            persistedCreatedAt = persistedCreatedAt.gte(createdAtFrom)
        }
        if (createdAtTo != null) {
            persistedCreatedAt = persistedCreatedAt.lte(createdAtTo)
        }

        val legacyFallbackCreatedAt = LocalDateTime.now()
        val legacyDocumentMatches =
            (createdAtFrom == null || !legacyFallbackCreatedAt.isBefore(createdAtFrom)) &&
                (createdAtTo == null || !legacyFallbackCreatedAt.isAfter(createdAtTo))

        return if (legacyDocumentMatches) {
            Criteria().orOperator(
                persistedCreatedAt,
                Criteria.where("createdAt").isNull()
            )
        } else {
            persistedCreatedAt
        }
    }

    private fun newQuery(criteria: Criteria?): Query {
        return criteria?.let(::Query) ?: Query()
    }

    private fun stableSort(requestedSort: Sort): Sort {
        val hasIdSort = requestedSort.any { order ->
            order.property == "id" || order.property == "_id"
        }
        return if (hasIdSort) {
            requestedSort
        } else {
            requestedSort.and(Sort.by(Sort.Direction.ASC, "_id"))
        }
    }
}
