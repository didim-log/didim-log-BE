package com.didimlog.global.config.mongo

import com.didimlog.domain.PasswordResetCode
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import java.time.Duration
import org.bson.Document
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.index.IndexInfo
import org.springframework.data.mongodb.core.index.IndexOperations
import org.springframework.data.mongodb.core.index.PartialIndexFilter
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
        ensureRetrospectiveStudentProblemUniqueIndex()
        ensureRetrospectiveStudentIdIndex()
        ensureStudentProviderIdentityUniqueIndex()
        ensureStudentNicknameUniqueIndex()
        ensureStudentBojIdUniqueIndex()
        ensureStudentEmailUniqueIndex()
        ensureStudentAdminRatingIndex()
        ensurePasswordResetCodeUniqueIndex()
        ensurePasswordResetCodeTtlIndex()
    }

    private fun ensureRetrospectiveStudentProblemUniqueIndex() {
        ensureIndex(
            indexOperations = mongoTemplate.indexOps(Retrospective::class.java),
            description = "학생별 문제 회고 유일성",
            definition = Index()
                .on("studentId", Sort.Direction.ASC)
                .on("problemId", Sort.Direction.ASC)
                .unique()
                .named(RETROSPECTIVE_STUDENT_PROBLEM_UNIQUE_INDEX_NAME)
        ) { index ->
            index.hasFields(
                "studentId" to Sort.Direction.ASC,
                "problemId" to Sort.Direction.ASC
            ) && index.isPlainIndex(unique = true)
        }
    }

    private fun ensureRetrospectiveStudentIdIndex() {
        val indexOperations = mongoTemplate.indexOps(Retrospective::class.java)
        val studentIdIndexes = indexOperations.indexInfo.filter { index ->
            index.hasFields("studentId" to Sort.Direction.ASC)
        }
        val usableStudentIdIndex = studentIdIndexes.firstOrNull { index ->
            index.isPlainIndex(unique = false)
        }
        if (usableStudentIdIndex != null) {
            check(!usableStudentIdIndex.isHidden) {
                "회고 학생 조회 인덱스가 숨김 상태입니다: ${usableStudentIdIndex.name}"
            }
            return
        }
        check(studentIdIndexes.isEmpty()) {
            "회고 학생 조회 인덱스 옵션이 올바르지 않습니다: ${studentIdIndexes.joinToString { it.name }}"
        }

        indexOperations
            .ensureIndex(
                Index()
                    .on("studentId", Sort.Direction.ASC)
                    .named(RETROSPECTIVE_STUDENT_ID_INDEX_NAME)
            )
    }

    private fun ensureStudentProviderIdentityUniqueIndex() {
        ensureIndex(
            indexOperations = mongoTemplate.indexOps(Student::class.java),
            description = "로그인 제공자 사용자 식별자 유일성",
            definition = Index()
                .on("provider", Sort.Direction.ASC)
                .on("providerId", Sort.Direction.ASC)
                .unique()
                .named(STUDENT_PROVIDER_IDENTITY_UNIQUE_INDEX_NAME)
        ) { index ->
            index.hasFields(
                "provider" to Sort.Direction.ASC,
                "providerId" to Sort.Direction.ASC
            ) && index.isPlainIndex(unique = true)
        }
    }

    private fun ensureStudentNicknameUniqueIndex() {
        ensureIndex(
            indexOperations = mongoTemplate.indexOps(Student::class.java),
            description = "학생 닉네임 유일성",
            definition = Index()
                .on("nickname", Sort.Direction.ASC)
                .unique()
                .named(STUDENT_NICKNAME_UNIQUE_INDEX_NAME)
        ) { index ->
            index.hasFields("nickname" to Sort.Direction.ASC) &&
                index.isPlainIndex(unique = true)
        }
    }

    private fun ensureStudentBojIdUniqueIndex() {
        val partialFilter = stringFieldFilter("bojId")
        ensureIndex(
            indexOperations = mongoTemplate.indexOps(Student::class.java),
            description = "BOJ ID 유일성",
            definition = Index()
                .on("bojId", Sort.Direction.ASC)
                .unique()
                .partial(PartialIndexFilter.of(partialFilter))
                .named(STUDENT_BOJ_ID_UNIQUE_INDEX_NAME)
        ) { index ->
                index.hasFields("bojId" to Sort.Direction.ASC) &&
                index.isUnique &&
                !index.isSparse &&
                index.hasStringFieldFilter("bojId") &&
                index.collation.isEmpty &&
                index.expireAfter.isEmpty
        }
    }

    private fun ensureStudentEmailUniqueIndex() {
        val partialFilter = stringFieldFilter("email")
        ensureIndex(
            indexOperations = mongoTemplate.indexOps(Student::class.java),
            description = "학생 이메일 유일성",
            definition = Index()
                .on("email", Sort.Direction.ASC)
                .unique()
                .partial(PartialIndexFilter.of(partialFilter))
                .named(STUDENT_EMAIL_UNIQUE_INDEX_NAME)
        ) { index ->
                index.hasFields("email" to Sort.Direction.ASC) &&
                index.isUnique &&
                !index.isSparse &&
                index.hasStringFieldFilter("email") &&
                index.collation.isEmpty &&
                index.expireAfter.isEmpty
        }
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

    private fun ensurePasswordResetCodeUniqueIndex() {
        ensureIndex(
            indexOperations = mongoTemplate.indexOps(PasswordResetCode::class.java),
            description = "비밀번호 재설정 코드 유일성",
            definition = Index()
                .on("resetCode", Sort.Direction.ASC)
                .unique()
                .named(PASSWORD_RESET_CODE_UNIQUE_INDEX_NAME)
        ) { index ->
            index.hasFields("resetCode" to Sort.Direction.ASC) &&
                index.isPlainIndex(unique = true)
        }
    }

    private fun ensurePasswordResetCodeTtlIndex() {
        ensureIndex(
            indexOperations = mongoTemplate.indexOps(PasswordResetCode::class.java),
            description = "비밀번호 재설정 코드 만료",
            definition = Index()
                .on("expiresAt", Sort.Direction.ASC)
                .expire(Duration.ZERO)
                .named(PASSWORD_RESET_CODE_TTL_INDEX_NAME)
        ) { index ->
            index.hasFields("expiresAt" to Sort.Direction.ASC) &&
                !index.isUnique &&
                !index.isSparse &&
                index.partialFilterExpression == null &&
                index.collation.isEmpty &&
                index.expireAfter.orElse(null) == Duration.ZERO
        }
    }

    private fun ensureIndex(
        indexOperations: IndexOperations,
        description: String,
        definition: Index,
        matches: (IndexInfo) -> Boolean
    ) {
        val existingIndex = indexOperations.indexInfo.firstOrNull(matches)
        if (existingIndex != null) {
            check(!existingIndex.isHidden) {
                "$description 인덱스가 숨김 상태입니다: ${existingIndex.name}"
            }
            return
        }

        try {
            indexOperations.ensureIndex(definition)
        } catch (exception: RuntimeException) {
            throw IllegalStateException(
                "$description 인덱스 생성에 실패했습니다. 하위 예외에서 원인을 확인해주세요.",
                exception
            )
        }
    }

    private fun IndexInfo.hasFields(vararg expected: Pair<String, Sort.Direction>): Boolean {
        return indexFields.size == expected.size &&
            indexFields.zip(expected).all { (actual, expectedField) ->
                actual.key == expectedField.first && actual.direction == expectedField.second
            }
    }

    private fun IndexInfo.isPlainIndex(unique: Boolean): Boolean {
        return isUnique == unique &&
            !isSparse &&
            partialFilterExpression == null &&
            collation.isEmpty &&
            expireAfter.isEmpty
    }

    private fun IndexInfo.hasStringFieldFilter(field: String): Boolean {
        val actual = partialFilterExpression ?: return false
        val filter = Document.parse(actual)
        if (filter.size != 1) {
            return false
        }
        val condition = filter[field] as? Document ?: return false
        if (condition.size != 1) {
            return false
        }
        val type = condition["\$type"]
        return type == "string" || (type as? Number)?.toInt() == BSON_STRING_TYPE
    }

    private fun stringFieldFilter(field: String): Document {
        return Document(field, Document("\$type", "string"))
    }

    companion object {
        const val RETROSPECTIVE_STUDENT_PROBLEM_UNIQUE_INDEX_NAME = "uniq_student_problem"
        const val RETROSPECTIVE_STUDENT_ID_INDEX_NAME = "studentId"
        const val STUDENT_PROVIDER_IDENTITY_UNIQUE_INDEX_NAME = "uniq_student_provider_identity"
        const val STUDENT_NICKNAME_UNIQUE_INDEX_NAME = "uniq_student_nickname"
        const val STUDENT_BOJ_ID_UNIQUE_INDEX_NAME = "uniq_student_boj_id"
        const val STUDENT_EMAIL_UNIQUE_INDEX_NAME = "uniq_student_email"
        const val STUDENT_ADMIN_RATING_INDEX_NAME = "admin_rating_desc_id_asc"
        const val PASSWORD_RESET_CODE_UNIQUE_INDEX_NAME = "uniq_password_reset_code"
        const val PASSWORD_RESET_CODE_TTL_INDEX_NAME = "ttl_password_reset_expires_at"
        private const val BSON_STRING_TYPE = 2
    }
}
