package com.didimlog.domain.repository

import com.didimlog.domain.Student
import com.didimlog.domain.Solutions
import com.didimlog.domain.enums.PrimaryLanguage
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.SolvedAcTierLevel
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.regex.Pattern
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository

@Repository
class StudentRepositoryImpl(
    private val mongoTemplate: MongoTemplate
) : StudentRepositoryCustom {

    override fun updatePasswordById(
        studentId: String,
        encodedPassword: String,
        expectedCredentialVersion: Long,
        expectedBojId: BojId
    ): Boolean {
        val query = credentialVersionQuery(studentId, expectedCredentialVersion)
            .addCriteria(Criteria.where("bojId").`is`(expectedBojId.value))
        val update = Update()
            .set("password", encodedPassword)
            .inc("credentialVersion", 1)
            .inc("documentVersion", 1)
        return mongoTemplate.updateFirst(query, update, Student::class.java).matchedCount == 1L
    }

    override fun updateProfileFieldsById(
        studentId: String,
        nickname: Nickname?,
        encodedPassword: String?,
        primaryLanguage: PrimaryLanguage?,
        expectedCredentialVersion: Long
    ): Student? {
        require(nickname != null || encodedPassword != null || primaryLanguage != null) {
            "갱신할 프로필 필드가 없습니다."
        }

        val query = credentialVersionQuery(studentId, expectedCredentialVersion)
        val update = Update()
        nickname?.let { update.set("nickname", it.value) }
        encodedPassword?.let {
            update.set("password", it)
            update.inc("credentialVersion", 1)
        }
        primaryLanguage?.let { update.set("primaryLanguage", it.name) }
        update.inc("documentVersion", 1)

        return mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true).upsert(false),
            Student::class.java
        )
    }

    private fun credentialVersionQuery(
        studentId: String,
        expectedCredentialVersion: Long
    ): Query {
        require(expectedCredentialVersion >= 0) { "자격 증명 버전은 0 이상이어야 합니다." }

        val query = Query.query(Criteria.where("_id").`is`(studentId))
        val versionCriteria = if (expectedCredentialVersion == 0L) {
            Criteria().orOperator(
                Criteria.where("credentialVersion").`is`(0L),
                Criteria.where("credentialVersion").exists(false)
            )
        } else {
            Criteria.where("credentialVersion").`is`(expectedCredentialVersion)
        }
        query.addCriteria(versionCriteria)
        return query
    }

    override fun updateSolvedAcProfileById(
        studentId: String,
        expectedBojId: BojId,
        rating: Int,
        solvedAcTierLevel: SolvedAcTierLevel,
        currentTier: Tier
    ): Student? {
        val query = Query.query(
            Criteria.where("_id").`is`(studentId)
                .and("bojId").`is`(expectedBojId.value)
        )
        val update = Update()
            .set("rating", rating)
            .set("solvedAcTierLevel", solvedAcTierLevel.value)
            .set("currentTier", currentTier.name)
            .inc("documentVersion", 1)
        return mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true).upsert(false),
            Student::class.java
        )
    }

    override fun updateStudyProgressById(
        studentId: String,
        expectedDocumentVersion: Long,
        solutions: Solutions,
        consecutiveSolveDays: Int,
        lastSolvedAt: LocalDate
    ): Student? {
        require(expectedDocumentVersion >= 0) { "문서 버전은 0 이상이어야 합니다." }

        val query = Query.query(
            Criteria.where("_id").`is`(studentId)
                .and("documentVersion").`is`(expectedDocumentVersion)
        )
        val update = Update()
            .set("solutions", solutions)
            .set("consecutiveSolveDays", consecutiveSolveDays)
            .set("lastSolvedAt", lastSolvedAt)
            .inc("documentVersion", 1)
        return mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true).upsert(false),
            Student::class.java
        )
    }

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
