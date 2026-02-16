package com.didimlog.application.admin

import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자 문제 통계 서비스
 */
@Service
class ProblemStatsService(
    private val mongoTemplate: MongoTemplate
) {

    @Transactional(readOnly = true)
    fun getProblemStats(): ProblemStats {
        val totalCount = mongoTemplate.getCollection("problems").countDocuments()
        if (totalCount == 0L) {
            return ProblemStats(
                totalCount = 0L,
                minProblemId = null,
                maxProblemId = null,
                minNullDescriptionHtmlProblemId = null,
                minNullLanguageProblemId = null
            )
        }

        val pipeline = listOf(
            Document(
                "\$match",
                Document("_id", Document("\$regex", "^[0-9]+$"))
            ),
            Document(
                "\$project",
                Document("numericProblemId", Document("\$toInt", "\$_id"))
                    .append("descriptionHtml", "\$descriptionHtml")
                    .append("languageNormalized", Document("\$toLower", Document("\$ifNull", listOf("\$language", ""))))
            ),
            Document(
                "\$group",
                Document("_id", null)
                    .append("minProblemId", Document("\$min", "\$numericProblemId"))
                    .append("maxProblemId", Document("\$max", "\$numericProblemId"))
                    .append(
                        "minNullDescriptionHtmlProblemId",
                        Document(
                            "\$min",
                            Document(
                                "\$cond",
                                listOf(
                                    Document("\$eq", listOf(Document("\$ifNull", listOf("\$descriptionHtml", "")), "")),
                                    "\$numericProblemId",
                                    null
                                )
                            )
                        )
                    )
                    .append(
                        "minNullLanguageProblemId",
                        Document(
                            "\$min",
                            Document(
                                "\$cond",
                                listOf(
                                    Document("\$in", listOf("\$languageNormalized", listOf("", "other"))),
                                    "\$numericProblemId",
                                    null
                                )
                            )
                        )
                    )
            )
        )

        val aggregateResult = mongoTemplate.getCollection("problems")
            .aggregate(pipeline)
            .first()

        return ProblemStats(
            totalCount = totalCount,
            minProblemId = aggregateResult?.getInteger("minProblemId"),
            maxProblemId = aggregateResult?.getInteger("maxProblemId"),
            minNullDescriptionHtmlProblemId = aggregateResult?.getInteger("minNullDescriptionHtmlProblemId"),
            minNullLanguageProblemId = aggregateResult?.getInteger("minNullLanguageProblemId")
        )
    }

    data class ProblemStats(
        val totalCount: Long,
        val minProblemId: Int?,
        val maxProblemId: Int?,
        val minNullDescriptionHtmlProblemId: Int?,
        val minNullLanguageProblemId: Int?
    )
}
