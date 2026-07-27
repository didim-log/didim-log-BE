package com.didimlog.global.config.mongo

import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
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
        ensureRetrospectiveStudentIdIndex()
        ensureStudentAdminRatingIndex()
    }

    private fun ensureRetrospectiveStudentIdIndex() {
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

    private fun ensureStudentAdminRatingIndex() {
        val indexOperations = mongoTemplate.indexOps(Student::class.java)
        val existingAdminRatingIndex = indexOperations.indexInfo.firstOrNull { index ->
            index.indexFields.size == 2 &&
                index.indexFields[0].key == "rating" &&
                index.indexFields[0].direction == Sort.Direction.DESC &&
                index.indexFields[1].key == "_id" &&
                index.indexFields[1].direction == Sort.Direction.ASC &&
                !index.isUnique &&
                !index.isSparse &&
                index.partialFilterExpression == null &&
                index.collation.isEmpty
        }
        if (existingAdminRatingIndex != null) {
            check(!existingAdminRatingIndex.isHidden) {
                "관리자 회원 정렬 인덱스가 숨김 상태입니다: ${existingAdminRatingIndex.name}"
            }
            return
        }

        indexOperations
            .ensureIndex(
                Index()
                    .on("rating", Sort.Direction.DESC)
                    .on("_id", Sort.Direction.ASC)
                    .named(STUDENT_ADMIN_RATING_INDEX_NAME)
            )
    }

    companion object {
        const val RETROSPECTIVE_STUDENT_ID_INDEX_NAME = "studentId"
        const val STUDENT_ADMIN_RATING_INDEX_NAME = "admin_rating_desc_id_asc"
    }
}
