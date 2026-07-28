package com.didimlog.global.config.mongo

import com.didimlog.domain.PasswordResetCode
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import java.time.Duration
import java.time.LocalDateTime
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.IndexInfo
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.index.PartialIndexFilter
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@DisplayName("Mongo 인덱스 정합성 통합 테스트")
@DataMongoTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoIndexInitializerIntegrationTest {

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    private lateinit var mongoIndexInitializer: MongoIndexInitializer

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
        mongoIndexInitializer = MongoIndexInitializer(mongoTemplate)
    }

    @AfterAll
    fun tearDownDatabase() {
        mongoTemplate.db.drop()
    }

    @Test
    fun `필요한 유일 인덱스와 TTL 인덱스를 명시적으로 생성한다`() {
        mongoIndexInitializer.ensureIndexes()

        assertPlainUniqueIndex(
            entityType = Retrospective::class.java,
            name = MongoIndexInitializer.RETROSPECTIVE_STUDENT_PROBLEM_UNIQUE_INDEX_NAME,
            "studentId" to Sort.Direction.ASC,
            "problemId" to Sort.Direction.ASC
        )
        assertPlainUniqueIndex(
            entityType = Student::class.java,
            name = MongoIndexInitializer.STUDENT_PROVIDER_IDENTITY_UNIQUE_INDEX_NAME,
            "provider" to Sort.Direction.ASC,
            "providerId" to Sort.Direction.ASC
        )
        assertPlainUniqueIndex(
            entityType = Student::class.java,
            name = MongoIndexInitializer.STUDENT_NICKNAME_UNIQUE_INDEX_NAME,
            "nickname" to Sort.Direction.ASC
        )
        assertPartialUniqueStringIndex(
            entityType = Student::class.java,
            name = MongoIndexInitializer.STUDENT_BOJ_ID_UNIQUE_INDEX_NAME,
            field = "bojId"
        )
        assertPartialUniqueStringIndex(
            entityType = Student::class.java,
            name = MongoIndexInitializer.STUDENT_EMAIL_UNIQUE_INDEX_NAME,
            field = "email"
        )
        assertPlainUniqueIndex(
            entityType = PasswordResetCode::class.java,
            name = MongoIndexInitializer.PASSWORD_RESET_CODE_UNIQUE_INDEX_NAME,
            "resetCode" to Sort.Direction.ASC
        )

        val ttlIndex = requireIndex(
            PasswordResetCode::class.java,
            MongoIndexInitializer.PASSWORD_RESET_CODE_TTL_INDEX_NAME
        )
        assertFields(ttlIndex, "expiresAt" to Sort.Direction.ASC)
        assertThat(ttlIndex.isUnique).isFalse()
        assertThat(ttlIndex.isSparse).isFalse()
        assertThat(ttlIndex.partialFilterExpression).isNull()
        assertThat(ttlIndex.collation).isEmpty
        assertThat(ttlIndex.expireAfter).contains(Duration.ZERO)
        assertThat(ttlIndex.isHidden).isFalse()

        mongoTemplate.insert(
            PasswordResetCode(
                id = "reset-code-date",
                resetCode = "RESET001",
                studentId = "student-a",
                expiresAt = LocalDateTime.now().plusMinutes(10)
            )
        )
        val storedResetCode = requireNotNull(
            mongoTemplate.getCollection("password_reset_codes")
                .find(Document("_id", "reset-code-date"))
                .first()
        )
        assertThat(storedResetCode["expiresAt"]).isInstanceOf(Date::class.java)
    }

    @Test
    fun `email과 BOJ ID가 없는 학생은 여러 명 저장할 수 있다`() {
        mongoIndexInitializer.ensureIndexes()

        mongoTemplate.insert(
            createStudent(
                id = "nullable-student-a",
                nickname = "nullA",
                provider = Provider.GOOGLE,
                providerId = "google-null-a"
            )
        )
        mongoTemplate.insert(
            createStudent(
                id = "nullable-student-b",
                nickname = "nullB",
                provider = Provider.GITHUB,
                providerId = "github-null-b"
            )
        )

        assertThat(mongoTemplate.count(Query(), Student::class.java)).isEqualTo(2)
    }

    @Test
    fun `같은 provider와 providerId는 중복 저장할 수 없다`() {
        mongoIndexInitializer.ensureIndexes()
        mongoTemplate.insert(baseStudent())

        assertDuplicateKey(MongoIndexInitializer.STUDENT_PROVIDER_IDENTITY_UNIQUE_INDEX_NAME) {
            mongoTemplate.insert(
                createStudent(
                    id = "student-b",
                    nickname = "beta",
                    provider = Provider.GOOGLE,
                    providerId = "provider-a",
                    bojId = "boj_beta",
                    email = "beta@example.com"
                )
            )
        }
    }

    @Test
    fun `같은 닉네임은 중복 저장할 수 없다`() {
        mongoIndexInitializer.ensureIndexes()
        mongoTemplate.insert(baseStudent())

        assertDuplicateKey(MongoIndexInitializer.STUDENT_NICKNAME_UNIQUE_INDEX_NAME) {
            mongoTemplate.insert(
                createStudent(
                    id = "student-b",
                    nickname = "alpha",
                    provider = Provider.GITHUB,
                    providerId = "provider-b",
                    bojId = "boj_beta",
                    email = "beta@example.com"
                )
            )
        }
    }

    @Test
    fun `같은 BOJ ID는 중복 저장할 수 없다`() {
        mongoIndexInitializer.ensureIndexes()
        mongoTemplate.insert(baseStudent())

        assertDuplicateKey(MongoIndexInitializer.STUDENT_BOJ_ID_UNIQUE_INDEX_NAME) {
            mongoTemplate.insert(
                createStudent(
                    id = "student-b",
                    nickname = "beta",
                    provider = Provider.GITHUB,
                    providerId = "provider-b",
                    bojId = "boj_alpha",
                    email = "beta@example.com"
                )
            )
        }
    }

    @Test
    fun `같은 이메일은 중복 저장할 수 없다`() {
        mongoIndexInitializer.ensureIndexes()
        mongoTemplate.insert(baseStudent())

        assertDuplicateKey(MongoIndexInitializer.STUDENT_EMAIL_UNIQUE_INDEX_NAME) {
            mongoTemplate.insert(
                createStudent(
                    id = "student-b",
                    nickname = "beta",
                    provider = Provider.GITHUB,
                    providerId = "provider-b",
                    bojId = "boj_beta",
                    email = "alpha@example.com"
                )
            )
        }
    }

    @Test
    fun `동시에 같은 학생과 문제의 회고를 insert하면 한 건만 저장된다`() {
        mongoIndexInitializer.ensureIndexes()
        val startSignal = CountDownLatch(1)
        val readySignal = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        val retrospectives = listOf(
            createRetrospective("retrospective-a", "첫 번째 동시 회고 내용입니다."),
            createRetrospective("retrospective-b", "두 번째 동시 회고 내용입니다.")
        )

        try {
            val futures = retrospectives.map { retrospective ->
                executor.submit<Throwable?> {
                    readySignal.countDown()
                    startSignal.await()
                    runCatching {
                        mongoTemplate.insert(retrospective)
                    }.exceptionOrNull()
                }
            }

            assertThat(readySignal.await(5, TimeUnit.SECONDS)).isTrue()
            startSignal.countDown()

            val failures = futures.map { future ->
                future.get(10, TimeUnit.SECONDS)
            }.filterNotNull()

            assertThat(failures).hasSize(1)
            assertThat(failures.single())
                .isInstanceOf(DuplicateKeyException::class.java)
                .hasMessageContaining(MongoIndexInitializer.RETROSPECTIVE_STUDENT_PROBLEM_UNIQUE_INDEX_NAME)

            val stored = mongoTemplate.find(
                Query.query(
                    Criteria.where("studentId").`is`("student-a")
                        .and("problemId").`is`("1000")
                ),
                Retrospective::class.java
            )
            assertThat(stored).hasSize(1)
            assertThat(stored.single().id).isIn("retrospective-a", "retrospective-b")
        } finally {
            startSignal.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `중복 데이터가 있으면 문서를 삭제하지 않고 인덱스 초기화에 실패한다`() {
        mongoTemplate.insert(createRetrospective("retrospective-a", "첫 번째 기존 회고 내용입니다."))
        mongoTemplate.insert(createRetrospective("retrospective-b", "두 번째 기존 회고 내용입니다."))

        assertThatThrownBy(mongoIndexInitializer::ensureIndexes)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("학생별 문제 회고 유일성")

        assertThat(
            mongoTemplate.count(
                Query.query(
                    Criteria.where("studentId").`is`("student-a")
                        .and("problemId").`is`("1000")
                ),
                Retrospective::class.java
            )
        ).isEqualTo(2)
        assertThat(
            mongoTemplate.indexOps(Retrospective::class.java).indexInfo
                .mapNotNull(IndexInfo::getName)
        ).doesNotContain(MongoIndexInitializer.RETROSPECTIVE_STUDENT_PROBLEM_UNIQUE_INDEX_NAME)
    }

    @Test
    fun `BSON 숫자 type을 사용한 기존 부분 인덱스를 재사용한다`() {
        mongoIndexInitializer.ensureIndexes()
        val indexOperations = mongoTemplate.indexOps(Student::class.java)
        indexOperations.dropIndex(MongoIndexInitializer.STUDENT_BOJ_ID_UNIQUE_INDEX_NAME)
        indexOperations.ensureIndex(
            Index()
                .on("bojId", Sort.Direction.ASC)
                .unique()
                .partial(
                    PartialIndexFilter.of(
                        Document("bojId", Document("\$type", 2))
                    )
                )
                .named(LEGACY_NUMERIC_TYPE_BOJ_INDEX_NAME)
        )

        mongoIndexInitializer.ensureIndexes()

        assertThat(indexOperations.indexInfo.map(IndexInfo::getName))
            .contains(LEGACY_NUMERIC_TYPE_BOJ_INDEX_NAME)
            .doesNotContain(MongoIndexInitializer.STUDENT_BOJ_ID_UNIQUE_INDEX_NAME)
    }

    private fun assertPlainUniqueIndex(
        entityType: Class<*>,
        name: String,
        vararg fields: Pair<String, Sort.Direction>
    ) {
        val index = requireIndex(entityType, name)
        assertFields(index, *fields)
        assertThat(index.isUnique).isTrue()
        assertThat(index.isSparse).isFalse()
        assertThat(index.partialFilterExpression).isNull()
        assertThat(index.collation).isEmpty
        assertThat(index.expireAfter).isEmpty
        assertThat(index.isHidden).isFalse()
    }

    private fun assertPartialUniqueStringIndex(
        entityType: Class<*>,
        name: String,
        field: String
    ) {
        val index = requireIndex(entityType, name)
        assertFields(index, field to Sort.Direction.ASC)
        assertThat(index.isUnique).isTrue()
        assertThat(index.isSparse).isFalse()
        assertThat(index.collation).isEmpty
        assertThat(index.expireAfter).isEmpty
        assertThat(index.isHidden).isFalse()
        assertThat(Document.parse(requireNotNull(index.partialFilterExpression)))
            .isEqualTo(Document(field, Document("\$type", "string")))
    }

    private fun requireIndex(entityType: Class<*>, name: String): IndexInfo {
        return requireNotNull(
            mongoTemplate.indexOps(entityType).indexInfo.singleOrNull { index ->
                index.name == name
            }
        ) {
            "$name 인덱스를 찾을 수 없습니다."
        }
    }

    private fun assertFields(
        index: IndexInfo,
        vararg expected: Pair<String, Sort.Direction>
    ) {
        assertThat(
            index.indexFields.map { field ->
                field.key to requireNotNull(field.direction)
            }
        ).containsExactly(*expected)
    }

    private fun assertDuplicateKey(expectedIndexName: String, action: () -> Unit) {
        assertThatThrownBy(action)
            .isInstanceOf(DuplicateKeyException::class.java)
            .hasMessageContaining(expectedIndexName)
    }

    private fun baseStudent(): Student {
        return createStudent(
            id = "student-a",
            nickname = "alpha",
            provider = Provider.GOOGLE,
            providerId = "provider-a",
            bojId = "boj_alpha",
            email = "alpha@example.com"
        )
    }

    private fun createStudent(
        id: String,
        nickname: String,
        provider: Provider,
        providerId: String,
        bojId: String? = null,
        email: String? = null
    ): Student {
        return Student(
            id = id,
            nickname = Nickname(nickname),
            provider = provider,
            providerId = providerId,
            email = email,
            bojId = bojId?.let(::BojId),
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
    }

    private fun createRetrospective(id: String, content: String): Retrospective {
        return Retrospective(
            id = id,
            studentId = "student-a",
            problemId = "1000",
            content = content
        )
    }

    companion object {
        private const val LEGACY_NUMERIC_TYPE_BOJ_INDEX_NAME = "legacy_uniq_student_boj_id"
        private val testDatabaseName = "didimlog-index-contract-${UUID.randomUUID().toString().replace("-", "")}"

        @JvmStatic
        @DynamicPropertySource
        fun mongoProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") {
                "mongodb://localhost:27017/$testDatabaseName"
            }
        }
    }
}
