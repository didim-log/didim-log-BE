package com.didimlog.global.config.mongo

import com.didimlog.domain.Retrospective
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.stereotype.Component

/**
 * 자동 인덱스 생성을 전역 활성화하지 않고 필요한 MongoDB 인덱스만 보장한다.
 */
@Component
class MongoIndexInitializer(
    private val mongoTemplate: MongoTemplate
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        ensureIndexes()
    }

    fun ensureIndexes() {
        val indexOperations = mongoTemplate.indexOps(Retrospective::class.java)
        val hasStudentIdIndex = indexOperations.indexInfo.any { index ->
            val field = index.indexFields.singleOrNull()
            field?.key == "studentId" && field.direction == Sort.Direction.ASC
        }
        if (hasStudentIdIndex) {
            return
        }

        indexOperations
            .ensureIndex(
                Index()
                    .on("studentId", Sort.Direction.ASC)
                    .named(RETROSPECTIVE_STUDENT_ID_INDEX_NAME)
            )
    }

    companion object {
        const val RETROSPECTIVE_STUDENT_ID_INDEX_NAME = "studentId"
    }
}
