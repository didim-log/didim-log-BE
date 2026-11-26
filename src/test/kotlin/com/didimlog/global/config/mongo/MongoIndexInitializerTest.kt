package com.didimlog.global.config.mongo

import com.didimlog.feedback.domain.Feedback
import com.didimlog.domain.Log
import com.didimlog.domain.PasswordResetCode
import com.didimlog.retrospective.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.template.domain.Template
import com.mongodb.client.result.UpdateResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.IndexDefinition
import org.springframework.data.mongodb.core.index.IndexInfo
import org.springframework.data.mongodb.core.index.IndexOperations
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update

@DisplayName("MongoIndexInitializer 테스트")
class MongoIndexInitializerTest {

    private val mongoTemplate: MongoTemplate = mockk()
    private val initializer = MongoIndexInitializer(mongoTemplate)

    private val existing = mutableMapOf<Class<*>, MutableList<IndexInfo>>()
    private val created = mutableMapOf<Class<*>, MutableList<IndexDefinition>>()
    private val failingClasses = mutableSetOf<Class<*>>()

    private val backfillQuery = slot<Query>()
    private val backfillUpdate = slot<Update>()

    private val entityClasses = listOf(
        Retrospective::class.java,
        Student::class.java,
        Log::class.java,
        Feedback::class.java,
        Template::class.java,
        PasswordResetCode::class.java
    )

    @BeforeEach
    fun setUp() {
        every {
            mongoTemplate.updateMulti(capture(backfillQuery), capture(backfillUpdate), eq(Student::class.java))
        } returns UpdateResult.acknowledged(0L, 0L, null)
        entityClasses.forEach { entityClass ->
            val operations: IndexOperations = mockk()
            every { operations.indexInfo } answers { existing[entityClass].orEmpty().toList() }
            every { operations.ensureIndex(any()) } answers {
                if (entityClass in failingClasses) {
                    throw IllegalArgumentException("index option conflict")
                }
                val definition = firstArg<IndexDefinition>()
                created.getOrPut(entityClass) { mutableListOf() } += definition
                definition.indexOptions.getString("name")
            }
            every { mongoTemplate.indexOps(entityClass) } returns operations
        }
    }

    private fun index(
        name: String,
        keys: Document,
        unique: Boolean = false,
        hidden: Boolean = false,
        partial: Document? = null,
        expireAfterSeconds: Long? = null,
        sparse: Boolean = false
    ): IndexInfo {
        val source = Document("key", keys).append("name", name)
        if (unique) source["unique"] = true
        if (hidden) source["hidden"] = true
        if (sparse) source["sparse"] = true
        partial?.let { source["partialFilterExpression"] = it }
        expireAfterSeconds?.let { source["expireAfterSeconds"] = it }
        return IndexInfo.indexInfoOf(source)
    }

    private fun seedAllCompatibleIndexes() {
        existing[Retrospective::class.java] = mutableListOf(
            index("legacy_student_problem", Document("studentId", 1).append("problemId", 1), unique = true),
            index("legacy_student_created", Document("studentId", 1).append("createdAt", -1)),
            index("legacy_student", Document("studentId", 1))
        )
        existing[Student::class.java] = mutableListOf(
            index("legacy_provider", Document("provider", 1).append("providerId", 1), unique = true),
            index("legacy_nickname", Document("nickname", 1), unique = true),
            index("legacy_boj", Document("bojId", 1), unique = true, partial = Document("bojId", Document("\$type", 2))),
            index(
                "legacy_email",
                Document("email", 1),
                unique = true,
                partial = Document("email", Document("\$type", "string"))
            ),
            index("legacy_rating", Document("rating", -1).append("_id", 1))
        )
        existing[Log::class.java] = mutableListOf(
            index("legacy_log_boj", Document("bojId", 1).append("createdAt", -1)),
            index("legacy_log_student", Document("studentId", 1).append("createdAt", -1))
        )
        existing[Feedback::class.java] = mutableListOf(index("legacy_writer", Document("writerId", 1)))
        existing[Template::class.java] = mutableListOf(index("legacy_template_student", Document("studentId", 1)))
        existing[PasswordResetCode::class.java] = mutableListOf(
            index("legacy_reset_code", Document("resetCode", 1), unique = true),
            index("legacy_reset_student", Document("studentId", 1), unique = true),
            index("legacy_reset_ttl", Document("expiresAt", 1), expireAfterSeconds = 0L)
        )
    }

    private fun createdNames(entityClass: Class<*>): List<String> =
        created[entityClass].orEmpty().map { it.indexOptions.getString("name") }

    private fun createdDefinition(entityClass: Class<*>, name: String): IndexDefinition =
        created.getValue(entityClass).single { it.indexOptions.getString("name") == name }

    @Test
    @DisplayName("인덱스가 없으면 문서 버전을 보정하고 필요한 인덱스를 모두 생성한다")
    fun `빈 컬렉션 인덱스 생성`() {
        initializer.ensureIndexes()

        assertThat(backfillQuery.captured.queryObject).isEqualTo(
            Document("documentVersion", Document("\$exists", false))
        )
        assertThat(backfillUpdate.captured.updateObject).isEqualTo(
            Document("\$set", Document("documentVersion", 0L))
        )
        assertThat(createdNames(Retrospective::class.java)).containsExactly(
            MongoIndexInitializer.RETROSPECTIVE_STUDENT_PROBLEM_UNIQUE_INDEX_NAME,
            MongoIndexInitializer.RETROSPECTIVE_STUDENT_CREATED_INDEX_NAME,
            MongoIndexInitializer.RETROSPECTIVE_STUDENT_ID_INDEX_NAME
        )
        assertThat(createdNames(Student::class.java)).containsExactly(
            MongoIndexInitializer.STUDENT_PROVIDER_IDENTITY_UNIQUE_INDEX_NAME,
            MongoIndexInitializer.STUDENT_NICKNAME_UNIQUE_INDEX_NAME,
            MongoIndexInitializer.STUDENT_BOJ_ID_UNIQUE_INDEX_NAME,
            MongoIndexInitializer.STUDENT_EMAIL_UNIQUE_INDEX_NAME,
            MongoIndexInitializer.STUDENT_ADMIN_RATING_INDEX_NAME
        )
        assertThat(createdNames(Log::class.java)).containsExactly(
            MongoIndexInitializer.LOG_BOJ_SNAPSHOT_CREATED_INDEX_NAME,
            MongoIndexInitializer.LOG_STUDENT_CREATED_INDEX_NAME
        )
        assertThat(createdNames(Feedback::class.java))
            .containsExactly(MongoIndexInitializer.FEEDBACK_WRITER_ID_INDEX_NAME)
        assertThat(createdNames(Template::class.java))
            .containsExactly(MongoIndexInitializer.TEMPLATE_STUDENT_ID_INDEX_NAME)
        assertThat(createdNames(PasswordResetCode::class.java)).containsExactly(
            MongoIndexInitializer.PASSWORD_RESET_CODE_UNIQUE_INDEX_NAME,
            MongoIndexInitializer.PASSWORD_RESET_STUDENT_ID_UNIQUE_INDEX_NAME,
            MongoIndexInitializer.PASSWORD_RESET_CODE_TTL_INDEX_NAME
        )
    }

    @Test
    @DisplayName("생성되는 인덱스는 유일성, 부분 필터, TTL 옵션을 정확히 가진다")
    fun `생성 인덱스 옵션`() {
        initializer.ensureIndexes()

        val bojId = createdDefinition(Student::class.java, MongoIndexInitializer.STUDENT_BOJ_ID_UNIQUE_INDEX_NAME)
        assertThat(bojId.indexKeys).isEqualTo(Document("bojId", 1))
        assertThat(bojId.indexOptions["unique"]).isEqualTo(true)
        assertThat(bojId.indexOptions["partialFilterExpression"])
            .isEqualTo(Document("bojId", Document("\$type", "string")))

        val rating = createdDefinition(Student::class.java, MongoIndexInitializer.STUDENT_ADMIN_RATING_INDEX_NAME)
        assertThat(rating.indexKeys).isEqualTo(Document("rating", -1).append("_id", 1))
        assertThat(rating.indexOptions).doesNotContainKey("unique")

        val ttl = createdDefinition(PasswordResetCode::class.java, MongoIndexInitializer.PASSWORD_RESET_CODE_TTL_INDEX_NAME)
        assertThat(ttl.indexKeys).isEqualTo(Document("expiresAt", 1))
        assertThat((ttl.indexOptions["expireAfterSeconds"] as Number).toLong()).isZero()

        val retrospective = createdDefinition(
            Retrospective::class.java,
            MongoIndexInitializer.RETROSPECTIVE_STUDENT_PROBLEM_UNIQUE_INDEX_NAME
        )
        assertThat(retrospective.indexKeys).isEqualTo(Document("studentId", 1).append("problemId", 1))
        assertThat(retrospective.indexOptions["unique"]).isEqualTo(true)
    }

    @Test
    @DisplayName("이름이 달라도 호환되는 인덱스가 이미 있으면 새로 만들지 않는다")
    fun `호환 인덱스 재사용`() {
        seedAllCompatibleIndexes()

        initializer.ensureIndexes()

        assertThat(created).isEmpty()
    }

    @Test
    @DisplayName("같은 필드라도 옵션이나 부분 필터가 다르면 호환 인덱스로 보지 않는다")
    fun `옵션이 다른 인덱스는 재사용하지 않음`() {
        seedAllCompatibleIndexes()
        existing[Student::class.java] = mutableListOf(
            index("legacy_provider", Document("provider", 1).append("providerId", 1), unique = true),
            index("non_unique_nickname", Document("nickname", 1)),
            index("exists_boj", Document("bojId", 1), unique = true, partial = Document("bojId", Document("\$exists", true))),
            index(
                "multi_condition_email",
                Document("email", 1),
                unique = true,
                partial = Document("email", Document("\$type", "string").append("\$exists", true))
            ),
            index("sparse_rating", Document("rating", -1).append("_id", 1), sparse = true)
        )
        existing[PasswordResetCode::class.java] = mutableListOf(
            index("legacy_reset_code", Document("resetCode", 1), unique = true),
            index("legacy_reset_student", Document("studentId", 1), unique = true),
            index("ttl_one_hour", Document("expiresAt", 1), expireAfterSeconds = 3600L)
        )

        initializer.ensureIndexes()

        assertThat(createdNames(Student::class.java)).containsExactly(
            MongoIndexInitializer.STUDENT_NICKNAME_UNIQUE_INDEX_NAME,
            MongoIndexInitializer.STUDENT_BOJ_ID_UNIQUE_INDEX_NAME,
            MongoIndexInitializer.STUDENT_EMAIL_UNIQUE_INDEX_NAME,
            MongoIndexInitializer.STUDENT_ADMIN_RATING_INDEX_NAME
        )
        assertThat(createdNames(PasswordResetCode::class.java))
            .containsExactly(MongoIndexInitializer.PASSWORD_RESET_CODE_TTL_INDEX_NAME)
        assertThat(created.keys).containsOnly(Student::class.java, PasswordResetCode::class.java)
    }

    @Test
    @DisplayName("호환 인덱스가 숨김 상태면 시작을 중단한다")
    fun `숨김 인덱스 거부`() {
        seedAllCompatibleIndexes()
        existing[Feedback::class.java] = mutableListOf(
            index("hidden_writer", Document("writerId", 1), hidden = true)
        )

        assertThatThrownBy { initializer.ensureIndexes() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("피드백 작성자 조회 인덱스가 숨김 상태입니다: hidden_writer")
    }

    @Test
    @DisplayName("관리자 정렬 인덱스나 회고 학생 인덱스가 숨김 상태면 시작을 중단한다")
    fun `개별 검사 인덱스 숨김 거부`() {
        seedAllCompatibleIndexes()
        existing[Student::class.java]!![4] = index("hidden_rating", Document("rating", -1).append("_id", 1), hidden = true)

        assertThatThrownBy { initializer.ensureIndexes() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("관리자 회원 정렬 인덱스가 숨김 상태입니다: hidden_rating")

        seedAllCompatibleIndexes()
        existing[Retrospective::class.java]!![2] = index("hidden_student", Document("studentId", 1), hidden = true)

        assertThatThrownBy { initializer.ensureIndexes() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("회고 학생 조회 인덱스가 숨김 상태입니다: hidden_student")
    }

    @Test
    @DisplayName("회고 학생 인덱스가 다른 옵션으로만 존재하면 같은 키로 새 인덱스를 만들지 않고 중단한다")
    fun `회고 학생 인덱스 옵션 충돌`() {
        seedAllCompatibleIndexes()
        existing[Retrospective::class.java]!![2] = index("unique_student", Document("studentId", 1), unique = true)

        assertThatThrownBy { initializer.ensureIndexes() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("회고 학생 조회 인덱스 옵션이 올바르지 않습니다: unique_student")
        assertThat(createdNames(Retrospective::class.java)).isEmpty()
    }

    @Test
    @DisplayName("인덱스 생성이 실패하면 설명과 원인 예외를 담아 시작을 중단한다")
    fun `인덱스 생성 실패`() {
        failingClasses += Retrospective::class.java

        assertThatThrownBy { initializer.ensureIndexes() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageStartingWith("학생별 문제 회고 유일성 인덱스 생성에 실패했습니다.")
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
    }
}
