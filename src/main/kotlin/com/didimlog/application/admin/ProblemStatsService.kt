package com.didimlog.application.admin

import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
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
        val query = Query()
        query.fields()
            .include("_id")
            .include("descriptionHtml")
            .include("language")

        val docs = mongoTemplate.find(query, Document::class.java, "problems")
        if (docs.isEmpty()) {
            return ProblemStats(
                totalCount = 0L,
                minProblemId = null,
                maxProblemId = null,
                minNullDescriptionHtmlProblemId = null,
                minNullLanguageProblemId = null
            )
        }

        var minProblemId: Int? = null
        var maxProblemId: Int? = null
        var minNullDescriptionHtmlProblemId: Int? = null
        var minNullLanguageProblemId: Int? = null

        for (doc in docs) {
            val numericProblemId = extractNumericProblemId(doc) ?: continue

            minProblemId = min(minProblemId, numericProblemId)
            maxProblemId = max(maxProblemId, numericProblemId)

            val descriptionHtml = doc.get("descriptionHtml")?.toString()
            if (descriptionHtml.isNullOrBlank()) {
                minNullDescriptionHtmlProblemId = min(minNullDescriptionHtmlProblemId, numericProblemId)
            }

            val language = doc.get("language")?.toString()
            if (language.isNullOrBlank() || language.equals("other", ignoreCase = true)) {
                minNullLanguageProblemId = min(minNullLanguageProblemId, numericProblemId)
            }
        }

        return ProblemStats(
            totalCount = docs.size.toLong(),
            minProblemId = minProblemId,
            maxProblemId = maxProblemId,
            minNullDescriptionHtmlProblemId = minNullDescriptionHtmlProblemId,
            minNullLanguageProblemId = minNullLanguageProblemId
        )
    }

    private fun extractNumericProblemId(doc: Document): Int? {
        val rawId = doc["_id"]?.toString() ?: return null
        return rawId.toIntOrNull()
    }

    private fun min(current: Int?, candidate: Int): Int {
        return if (current == null || candidate < current) candidate else current
    }

    private fun max(current: Int?, candidate: Int): Int {
        return if (current == null || candidate > current) candidate else current
    }

    data class ProblemStats(
        val totalCount: Long,
        val minProblemId: Int?,
        val maxProblemId: Int?,
        val minNullDescriptionHtmlProblemId: Int?,
        val minNullLanguageProblemId: Int?
    )
}
