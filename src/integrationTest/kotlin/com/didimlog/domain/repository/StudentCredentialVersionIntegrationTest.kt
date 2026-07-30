package com.didimlog.domain.repository

import com.didimlog.domain.Student
import com.didimlog.domain.enums.PrimaryLanguage
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.TemplateCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.SolvedAcTierLevel
import com.didimlog.global.config.mongo.MongoIndexInitializer
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@DataMongoTest
@DisplayName("학생 자격 증명 버전 저장소 통합 테스트")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StudentCredentialVersionIntegrationTest {

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
    }

    @AfterAll
    fun tearDownDatabase() {
        mongoTemplate.db.drop()
    }

    @Test
    fun `버전 필드가 없는 기존 학생은 0으로 읽고 첫 비밀번호 변경을 허용한다`() {
        val student = saveStudent(id = "legacy", credentialVersion = 0)
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").`is`(student.id)),
            Update().unset("credentialVersion"),
            Student::class.java
        )

        val legacyStudent = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(legacyStudent.credentialVersion).isZero()

        val updated = studentRepository.updateProfileFieldsById(
            studentId = requireNotNull(student.id),
            nickname = Nickname("updated-user"),
            encodedPassword = "new-password",
            primaryLanguage = PrimaryLanguage.KOTLIN,
            expectedCredentialVersion = 0
        )

        assertThat(updated).isNotNull
        assertThat(updated?.nickname).isEqualTo(Nickname("updated-user"))
        assertThat(updated?.password).isEqualTo("new-password")
        assertThat(updated?.primaryLanguage).isEqualTo(PrimaryLanguage.KOTLIN)
        assertThat(updated?.credentialVersion).isEqualTo(1)
        assertThat(updated?.documentVersion)
            .isEqualTo(requireNotNull(legacyStudent.documentVersion) + 1)
        assertThat(updated?.rating).isEqualTo(student.rating)
    }

    @Test
    fun `비밀번호 부분 갱신은 예상 버전이 다르면 문서를 변경하지 않는다`() {
        val student = saveStudent(id = "stale", credentialVersion = 2)

        val updated = studentRepository.updateProfileFieldsById(
            studentId = requireNotNull(student.id),
            nickname = Nickname("stale-update"),
            encodedPassword = "new-password",
            primaryLanguage = PrimaryLanguage.JAVA,
            expectedCredentialVersion = 1
        )

        assertThat(updated).isNull()
        val persisted = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(persisted.nickname).isEqualTo(student.nickname)
        assertThat(persisted.password).isEqualTo(student.password)
        assertThat(persisted.primaryLanguage).isEqualTo(student.primaryLanguage)
        assertThat(persisted.credentialVersion).isEqualTo(2)
        assertThat(persisted.documentVersion).isEqualTo(0)
    }

    @Test
    fun `비밀번호 단일 갱신은 성공한 버전에서만 한 번 증가한다`() {
        val student = saveStudent(id = "password_only", credentialVersion = 4)

        assertThat(
            studentRepository.updatePasswordById(
                studentId = requireNotNull(student.id),
                encodedPassword = "first-password",
                expectedCredentialVersion = 4,
                expectedBojId = requireNotNull(student.bojId)
            )
        ).isTrue()
        assertThat(
            studentRepository.updatePasswordById(
                studentId = requireNotNull(student.id),
                encodedPassword = "stale-password",
                expectedCredentialVersion = 4,
                expectedBojId = requireNotNull(student.bojId)
            )
        ).isFalse()

        val persisted = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(persisted.password).isEqualTo("first-password")
        assertThat(persisted.credentialVersion).isEqualTo(5)
        assertThat(persisted.documentVersion).isEqualTo(1)
    }

    @Test
    fun `BOJ ID가 바뀌면 이전 계정 정보로 비밀번호를 갱신하지 않는다`() {
        val student = saveStudent(id = "password_boj", credentialVersion = 4)
        val originalBojId = requireNotNull(student.bojId)
        val changedStudent = studentRepository.save(
            student.copy(bojId = BojId("replacement_password_boj"))
        )

        val updated = studentRepository.updatePasswordById(
            studentId = requireNotNull(student.id),
            encodedPassword = "stale-password",
            expectedCredentialVersion = student.credentialVersion,
            expectedBojId = originalBojId
        )

        assertThat(updated).isFalse()
        val persisted = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(persisted.bojId).isEqualTo(BojId("replacement_password_boj"))
        assertThat(persisted.password).isEqualTo(changedStudent.password)
        assertThat(persisted.credentialVersion).isEqualTo(changedStudent.credentialVersion)
        assertThat(persisted.documentVersion).isEqualTo(changedStudent.documentVersion)
    }

    @Test
    fun `부분 비밀번호 갱신은 이전 전체 학생 스냅샷의 저장을 거절한다`() {
        val staleSnapshot = saveStudent(id = "stale_save", credentialVersion = 0)

        val updated = studentRepository.updateProfileFieldsById(
            studentId = requireNotNull(staleSnapshot.id),
            nickname = null,
            encodedPassword = "new-password",
            primaryLanguage = null,
            expectedCredentialVersion = 0
        )

        assertThat(updated?.password).isEqualTo("new-password")
        assertThat(updated?.credentialVersion).isEqualTo(1)
        assertThat(updated?.documentVersion).isEqualTo(1)
        assertThatThrownBy {
            studentRepository.save(staleSnapshot.copy(nickname = Nickname("stale-user")))
        }.isInstanceOf(OptimisticLockingFailureException::class.java)

        val persisted = studentRepository.findById(requireNotNull(staleSnapshot.id)).orElseThrow()
        assertThat(persisted.password).isEqualTo("new-password")
        assertThat(persisted.credentialVersion).isEqualTo(1)
        assertThat(persisted.documentVersion).isEqualTo(1)
        assertThat(persisted.nickname).isEqualTo(staleSnapshot.nickname)
    }

    @Test
    fun `누락된 문서 버전을 초기화하면 기존 학생을 정상 저장할 수 있다`() {
        val student = saveStudent(id = "legacy_save", credentialVersion = 0)
        val missingVersionQuery = Query.query(
            Criteria.where("_id").`is`(student.id)
                .and("documentVersion").exists(false)
        )
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").`is`(student.id)),
            Update().unset("documentVersion"),
            Student::class.java
        )
        assertThat(mongoTemplate.count(missingVersionQuery, Student::class.java)).isEqualTo(1)

        MongoIndexInitializer(mongoTemplate).ensureIndexes()

        assertThat(mongoTemplate.count(missingVersionQuery, Student::class.java)).isZero()
        val backfilled = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(backfilled.documentVersion).isEqualTo(0)

        val saved = studentRepository.save(backfilled.copy(nickname = Nickname("legacy-user")))

        assertThat(saved.nickname).isEqualTo(Nickname("legacy-user"))
        assertThat(saved.documentVersion).isEqualTo(1)
    }

    @Test
    fun `solved ac 부분 갱신도 문서 버전을 증가시킨다`() {
        val student = saveStudent(id = "profile_sync", credentialVersion = 0)
        val tierLevel = SolvedAcTierLevel.fromRating(2200)

        val updated = studentRepository.updateSolvedAcProfileById(
            studentId = requireNotNull(student.id),
            expectedBojId = requireNotNull(student.bojId),
            rating = 2200,
            solvedAcTierLevel = tierLevel,
            currentTier = Tier.PLATINUM
        )

        assertThat(updated?.rating).isEqualTo(2200)
        assertThat(updated?.documentVersion).isEqualTo(1)
    }

    @Test
    fun `BOJ ID가 바뀌면 이전 계정의 solved ac 응답을 저장하지 않는다`() {
        val student = saveStudent(id = "boj_changed", credentialVersion = 0)
        val oldBojId = requireNotNull(student.bojId)
        val changedStudent = studentRepository.save(
            student.copy(bojId = BojId("replacement_boj"))
        )

        val updated = studentRepository.updateSolvedAcProfileById(
            studentId = requireNotNull(student.id),
            expectedBojId = oldBojId,
            rating = 2200,
            solvedAcTierLevel = SolvedAcTierLevel.fromRating(2200),
            currentTier = Tier.PLATINUM
        )

        assertThat(updated).isNull()
        val persisted = studentRepository.findById(requireNotNull(student.id)).orElseThrow()
        assertThat(persisted.bojId).isEqualTo(BojId("replacement_boj"))
        assertThat(persisted.rating).isEqualTo(changedStudent.rating)
        assertThat(persisted.solvedAcTierLevel).isEqualTo(changedStudent.solvedAcTierLevel)
        assertThat(persisted.currentTier).isEqualTo(changedStudent.currentTier)
        assertThat(persisted.documentVersion).isEqualTo(changedStudent.documentVersion)
    }

    @Test
    fun `기본 템플릿 부분 갱신은 다른 학생 필드를 보존하고 삭제된 학생을 다시 만들지 않는다`() {
        val student = saveStudent(id = "default_template", credentialVersion = 2)
        val studentId = requireNotNull(student.id)

        val updated = studentRepository.updateDefaultTemplateById(
            studentId = studentId,
            category = TemplateCategory.SUCCESS,
            templateId = "template-success"
        )

        assertThat(updated?.defaultSuccessTemplateId).isEqualTo("template-success")
        assertThat(updated?.nickname).isEqualTo(student.nickname)
        assertThat(updated?.rating).isEqualTo(student.rating)
        assertThat(updated?.credentialVersion).isEqualTo(student.credentialVersion)
        assertThat(updated?.documentVersion).isEqualTo(requireNotNull(student.documentVersion) + 1)
        assertThatThrownBy {
            studentRepository.save(student.copy(nickname = Nickname("staleuser")))
        }.isInstanceOf(OptimisticLockingFailureException::class.java)

        studentRepository.deleteById(studentId)

        assertThat(
            studentRepository.updateDefaultTemplateById(
                studentId = studentId,
                category = TemplateCategory.FAIL,
                templateId = "template-fail"
            )
        ).isNull()
        assertThat(studentRepository.existsById(studentId)).isFalse()
    }

    private fun saveStudent(id: String, credentialVersion: Long): Student {
        return studentRepository.save(
            Student(
                id = id,
                nickname = Nickname("user_${id.take(6)}"),
                provider = Provider.BOJ,
                providerId = "${id}_provider",
                bojId = BojId("${id}_boj"),
                password = "old-password",
                credentialVersion = credentialVersion,
                rating = 1234,
                currentTier = Tier.SILVER,
                role = Role.USER,
                primaryLanguage = PrimaryLanguage.PYTHON
            )
        )
    }

    companion object {
        private val testDatabaseName =
            "didimlog-credential-version-${UUID.randomUUID().toString().replace("-", "")}"

        @JvmStatic
        @DynamicPropertySource
        fun mongoProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") {
                val port = System.getenv("TEST_MONGO_PORT") ?: "27017"
                "mongodb://localhost:$port/$testDatabaseName"
            }
        }
    }
}
