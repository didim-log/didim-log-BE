package com.didimlog.domain.repository

import com.didimlog.domain.Problem
import org.bson.Document
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
        return findProblems(Query(effectiveLevelCriteria(min, max)))
    }

    override fun findRecommendationCandidates(
        min: Int,
        max: Int,
        targetCategories: List<String>,
        expandedTags: List<String>
    ): List<Problem> {
        val primaryCategoryCriteria = Criteria.where("category").`in`(targetCategories)
        val categoryCriteria = if (expandedTags.isEmpty()) {
            primaryCategoryCriteria
        } else {
            Criteria().orOperator(
                primaryCategoryCriteria,
                Criteria.where("tags").`in`(expandedTags)
            )
        }

        val query = Query(Criteria().andOperator(effectiveLevelCriteria(min, max), categoryCriteria))
        return findProblems(query)
    }

    private fun effectiveLevelCriteria(min: Int, max: Int): Criteria {
        val currentLevelCriteria = Criteria.where("level").gte(min).lte(max)
        val legacyLevelCriteria = Criteria().andOperator(
            Criteria.where("level").`is`(null),
            Criteria.where("difficultyLevel").gte(min).lte(max)
        )
        return Criteria().orOperator(currentLevelCriteria, legacyLevelCriteria)
    }

    private fun findProblems(query: Query): List<Problem> {
        return mongoTemplate.find(query, Document::class.java, mongoTemplate.getCollectionName(Problem::class.java))
            .map { document ->
                if (document["level"] == null) {
                    document["level"] = document["difficultyLevel"]
                }
                mongoTemplate.converter.read(Problem::class.java, document)
            }
    }
}
