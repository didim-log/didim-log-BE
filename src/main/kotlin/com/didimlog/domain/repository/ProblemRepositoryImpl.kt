package com.didimlog.domain.repository

import com.didimlog.domain.Problem
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository

/**
 * Problem Repository 구현체
 * MongoDB의 Criteria를 사용하여 동적 쿼리를 구현한다.
 */
@Repository
class ProblemRepositoryImpl(
    private val mongoTemplate: MongoTemplate
) : ProblemRepositoryCustom {

    override fun upsertMetadata(problem: Problem) {
        val query = Query.query(Criteria.where("_id").`is`(problem.id.value))
        val update = Update()
            .set("title", problem.title)
            .set("category", problem.category.englishName)
            .set("difficulty", problem.difficulty.name)
            .set("level", problem.level)
            .set("tags", problem.tags)
            .setOnInsert("url", problem.url)
            .setOnInsert("language", problem.language)
            .unset("difficultyLevel")

        mongoTemplate.upsert(query, update, Problem::class.java)
    }

    override fun updateDetails(problemId: String, details: ProblemDetailsUpdate): Problem? {
        val query = Query.query(Criteria.where("_id").`is`(problemId))
        val update = Update()
            .set("descriptionHtml", details.descriptionHtml)
            .set("inputDescriptionHtml", details.inputDescriptionHtml)
            .set("outputDescriptionHtml", details.outputDescriptionHtml)
            .set("sampleInputs", details.sampleInputs)
            .set("sampleOutputs", details.sampleOutputs)
        details.language?.let { language ->
            update.set("language", language)
        }

        return mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true).upsert(false),
            Problem::class.java
        )
    }

    override fun updateLanguage(problemId: String, language: String): Boolean {
        val query = Query.query(Criteria.where("_id").`is`(problemId))
        val update = Update().set("language", language)
        return mongoTemplate.updateFirst(query, update, Problem::class.java).matchedCount > 0L
    }

    override fun findByLevelBetweenFlexible(min: Int, max: Int): List<Problem> {
        val levelCriteria = Criteria.where("level").gte(min).lte(max)
        val legacyLevelCriteria = Criteria.where("difficultyLevel").gte(min).lte(max)
        val criteria = Criteria().orOperator(levelCriteria, legacyLevelCriteria)

        return mongoTemplate.find(Query(criteria), Problem::class.java)
    }

    override fun findByLevelBetweenAndTagsIn(min: Int, max: Int, expandedTags: List<String>): List<Problem> {
        // MongoDB에서 배열 필드에 대해 $in 연산자를 사용하여 검색
        // 문제의 tags 리스트 중 하나라도 expandedTags에 포함되면 검색됨
        // 레거시 스키마(difficultyLevel)도 함께 지원한다.
        val levelCriteria = Criteria.where("level").gte(min).lte(max)
        val legacyLevelCriteria = Criteria.where("difficultyLevel").gte(min).lte(max)
        val criteria = Criteria().orOperator(levelCriteria, legacyLevelCriteria)
            .and("tags")
            .`in`(expandedTags)

        val query = Query(criteria)
        return mongoTemplate.find(query, Problem::class.java)
    }
}
