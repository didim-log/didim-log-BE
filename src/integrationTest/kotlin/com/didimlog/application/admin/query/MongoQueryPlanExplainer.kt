package com.didimlog.application.admin.query

import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate

class MongoQueryPlanExplainer(
    private val mongoTemplate: MongoTemplate
) {

    fun collectIndexes(entityType: Class<*>): List<MongoIndexBaseline> {
        return mongoTemplate.indexOps(entityType).indexInfo
            .map { index ->
                MongoIndexBaseline(
                    name = index.name,
                    unique = index.isUnique || index.name == "_id_",
                    sparse = index.isSparse,
                    fields = index.indexFields.map { field ->
                        MongoIndexFieldBaseline(
                            key = field.key,
                            direction = field.direction?.name
                        )
                    }
                )
            }
            .sortedBy { it.name }
    }

    fun explainFind(
        query: String,
        command: ObservedMongoReadCommand
    ): MongoQueryExecutionBaseline {
        val findCommand = Document("find", command.collection)
            .append("filter", command.filter ?: Document())
        command.sort?.let { findCommand.append("sort", it) }
        command.skip?.let { findCommand.append("skip", it) }
        command.limit?.let { findCommand.append("limit", it) }
        val explain = mongoTemplate.executeCommand(
            Document("explain", findCommand)
                .append("verbosity", "executionStats")
        )

        return executionBaseline(
            collection = command.collection,
            query = query,
            queryPlanner = explain.requiredDocument("queryPlanner"),
            executionStats = explain.requiredDocument("executionStats")
        )
    }

    fun explainAggregation(
        collection: String,
        query: String,
        pipeline: List<Document>
    ): MongoQueryExecutionBaseline {
        val explain = mongoTemplate.executeCommand(
            Document(
                "explain",
                Document("aggregate", collection)
                    .append("pipeline", pipeline)
                    .append("cursor", Document())
            ).append("verbosity", "executionStats")
        )
        val cursor = findAggregationCursor(explain)
        val queryPlanner = cursor?.requiredDocument("queryPlanner")
            ?: requireNotNull(findNestedDocument(explain, "queryPlanner")) {
                "Mongo aggregate explain에 queryPlanner가 없습니다. response=$explain"
            }
        val executionStats = cursor?.requiredDocument("executionStats")
            ?: requireNotNull(findNestedDocument(explain, "executionStats")) {
                "Mongo aggregate explain에 executionStats가 없습니다. response=$explain"
            }

        return executionBaseline(
            collection = collection,
            query = query,
            queryPlanner = queryPlanner,
            executionStats = executionStats
        )
    }

    private fun executionBaseline(
        collection: String,
        query: String,
        queryPlanner: Document,
        executionStats: Document
    ): MongoQueryExecutionBaseline {
        val winningPlan = queryPlanner.requiredDocument("winningPlan")
        val accessPlan = requireNotNull(findAccessPlan(winningPlan)) {
            "winning access plan을 찾을 수 없습니다. winningPlan=$winningPlan"
        }

        return MongoQueryExecutionBaseline(
            collection = collection,
            query = query,
            winningPlanStage = accessPlan.requiredString("stage"),
            selectedIndexName = accessPlan.getString("indexName"),
            selectedIndexKeyPattern = accessPlan.indexKeyPattern(),
            hasBlockingSort = containsStage(winningPlan, "SORT"),
            nReturned = executionStats.requiredLong("nReturned"),
            totalDocsExamined = executionStats.requiredLong("totalDocsExamined"),
            totalKeysExamined = executionStats.requiredLong("totalKeysExamined")
        )
    }

    private fun findAggregationCursor(explain: Document): Document? {
        return (explain["stages"] as? Iterable<*>)
            ?.asSequence()
            ?.mapNotNull { stage ->
                (stage as? Document)?.get("\$cursor") as? Document
            }
            ?.firstOrNull()
    }

    private fun findNestedDocument(value: Any?, key: String): Document? {
        return when (value) {
            is Document -> {
                (value[key] as? Document)
                    ?: value.values.asSequence()
                        .mapNotNull { nested -> findNestedDocument(nested, key) }
                        .firstOrNull()
            }
            is Iterable<*> -> value.asSequence()
                .mapNotNull { nested -> findNestedDocument(nested, key) }
                .firstOrNull()
            else -> null
        }
    }

    private fun findAccessPlan(value: Any?): Document? {
        return when (value) {
            is Document -> {
                (value["stage"] as? String)
                    ?.takeIf { stage -> stage in ACCESS_STAGES }
                    ?.let { value }
                    ?: value.values.asSequence().mapNotNull(::findAccessPlan).firstOrNull()
            }
            is Iterable<*> -> value.asSequence().mapNotNull(::findAccessPlan).firstOrNull()
            else -> null
        }
    }

    private fun containsStage(value: Any?, targetStage: String): Boolean {
        return when (value) {
            is Document -> {
                value["stage"] == targetStage ||
                    value.values.any { nested -> containsStage(nested, targetStage) }
            }
            is Iterable<*> -> value.any { nested -> containsStage(nested, targetStage) }
            else -> false
        }
    }

    companion object {
        private val ACCESS_STAGES = setOf("COLLSCAN", "IXSCAN", "COUNT_SCAN")
    }
}

private fun Document.requiredDocument(key: String): Document {
    return requireNotNull(this[key] as? Document) {
        "Mongo 응답에 $key 문서가 없습니다. response=$this"
    }
}

private fun Document.requiredLong(key: String): Long {
    return requireNotNull(this[key] as? Number) {
        "Mongo 응답에 $key 숫자가 없습니다. document=$this"
    }.toLong()
}

private fun Document.requiredString(key: String): String {
    return requireNotNull(getString(key)) {
        "Mongo 응답에 $key 문자열이 없습니다. document=$this"
    }
}

private fun Document.indexKeyPattern(): Map<String, Int>? {
    val keyPattern = this["keyPattern"] as? Document ?: return null
    return keyPattern.entries.associate { (key, value) ->
        key to requireNotNull(value as? Number) {
            "Mongo index keyPattern 값이 숫자가 아닙니다. key=$key, value=$value"
        }.toInt()
    }
}
