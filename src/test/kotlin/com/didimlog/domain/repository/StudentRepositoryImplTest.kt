package com.didimlog.domain.repository

import com.didimlog.study.domain.Solutions
import com.didimlog.domain.Student
import com.didimlog.domain.enums.PrimaryLanguage
import com.didimlog.template.domain.TemplateCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.SolvedAcTierLevel
import com.mongodb.client.result.UpdateResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.regex.Pattern
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update

@DisplayName("StudentRepositoryImpl 테스트")
class StudentRepositoryImplTest {

    private val mongoTemplate: MongoTemplate = mockk()
    private val repository = StudentRepositoryImpl(mongoTemplate)

    private val querySlot = slot<Query>()
    private val updateSlot = slot<Update>()
    private val optionsSlot = slot<FindAndModifyOptions>()

    private fun stubFindAndModify(result: Student?) {
        every {
            mongoTemplate.findAndModify(
                capture(querySlot),
                capture(updateSlot),
                capture(optionsSlot),
                eq(Student::class.java)
            )
        } returns result
    }

    @Test
    @DisplayName("비밀번호 변경은 ID, 자격 증명 버전, BOJ ID가 모두 일치할 때만 버전을 올린다")
    fun `비밀번호 변경 조건과 버전 증가`() {
        every {
            mongoTemplate.updateFirst(capture(querySlot), capture(updateSlot), eq(Student::class.java))
        } returns UpdateResult.acknowledged(1L, 1L, null)

        val updated = repository.updatePasswordById("student-1", "encoded", 3L, BojId("boj_user"))

        assertThat(updated).isTrue()
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("_id", "student-1")
                .append("credentialVersion", 3L)
                .append("bojId", "boj_user")
        )
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document("\$set", Document("password", "encoded"))
                .append("\$inc", Document("credentialVersion", 1).append("documentVersion", 1))
        )
    }

    @Test
    @DisplayName("비밀번호 변경 대상이 매칭되지 않으면 false를 반환한다")
    fun `비밀번호 변경 대상 없음`() {
        every {
            mongoTemplate.updateFirst(any<Query>(), any<Update>(), eq(Student::class.java))
        } returns UpdateResult.acknowledged(0L, 0L, null)

        val updated = repository.updatePasswordById("student-1", "encoded", 1L, BojId("boj_user"))

        assertThat(updated).isFalse()
    }

    @Test
    @DisplayName("자격 증명 버전이 0이면 버전 필드가 없는 레거시 문서도 매칭한다")
    fun `자격 증명 버전 0 레거시 매칭`() {
        every {
            mongoTemplate.updateFirst(capture(querySlot), any<Update>(), eq(Student::class.java))
        } returns UpdateResult.acknowledged(1L, 1L, null)

        repository.updatePasswordById("student-1", "encoded", 0L, BojId("boj_user"))

        assertThat(querySlot.captured.queryObject["\$or"]).isEqualTo(
            listOf(
                Document("credentialVersion", 0L),
                Document("credentialVersion", Document("\$exists", false))
            )
        )
        assertThat(querySlot.captured.queryObject).doesNotContainKey("credentialVersion")
    }

    @Test
    @DisplayName("음수 자격 증명 버전은 저장소 호출 전에 거부한다")
    fun `음수 자격 증명 버전 거부`() {
        assertThatThrownBy {
            repository.updatePasswordById("student-1", "encoded", -1L, BojId("boj_user"))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("자격 증명 버전은 0 이상이어야 합니다.")

        verify(exactly = 0) { mongoTemplate.updateFirst(any<Query>(), any<Update>(), any<Class<*>>()) }
    }

    @Test
    @DisplayName("프로필 수정 시 비밀번호가 포함되면 자격 증명 버전도 함께 증가한다")
    fun `프로필 전체 필드 수정`() {
        val student: Student = mockk()
        stubFindAndModify(student)

        val result = repository.updateProfileFieldsById(
            studentId = "student-1",
            nickname = Nickname("new-nick"),
            encodedPassword = "encoded",
            primaryLanguage = PrimaryLanguage.KOTLIN,
            expectedCredentialVersion = 2L
        )

        assertThat(result).isSameAs(student)
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("_id", "student-1").append("credentialVersion", 2L)
        )
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document(
                "\$set",
                Document("nickname", "new-nick")
                    .append("password", "encoded")
                    .append("primaryLanguage", "KOTLIN")
            ).append("\$inc", Document("credentialVersion", 1).append("documentVersion", 1))
        )
        assertThat(optionsSlot.captured.isReturnNew).isTrue()
        assertThat(optionsSlot.captured.isUpsert).isFalse()
    }

    @Test
    @DisplayName("비밀번호 없이 닉네임만 수정하면 자격 증명 버전은 유지된다")
    fun `닉네임만 수정`() {
        stubFindAndModify(null)

        val result = repository.updateProfileFieldsById(
            studentId = "student-1",
            nickname = Nickname("new-nick"),
            encodedPassword = null,
            primaryLanguage = null,
            expectedCredentialVersion = 5L
        )

        assertThat(result).isNull()
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document("\$set", Document("nickname", "new-nick"))
                .append("\$inc", Document("documentVersion", 1))
        )
    }

    @Test
    @DisplayName("갱신할 프로필 필드가 없으면 예외가 발생한다")
    fun `프로필 갱신 필드 없음`() {
        assertThatThrownBy {
            repository.updateProfileFieldsById("student-1", null, null, null, 0L)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("갱신할 프로필 필드가 없습니다.")
    }

    @Test
    @DisplayName("Solved.ac 프로필 갱신은 BOJ ID가 일치하는 문서에만 적용된다")
    fun `Solved ac 프로필 갱신`() {
        stubFindAndModify(null)

        repository.updateSolvedAcProfileById(
            studentId = "student-1",
            expectedBojId = BojId("boj_user"),
            rating = 1500,
            solvedAcTierLevel = SolvedAcTierLevel(15),
            currentTier = Tier.GOLD
        )

        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("_id", "student-1").append("bojId", "boj_user")
        )
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document(
                "\$set",
                Document("rating", 1500)
                    .append("solvedAcTierLevel", 15)
                    .append("currentTier", "GOLD")
            ).append("\$inc", Document("documentVersion", 1))
        )
        assertThat(optionsSlot.captured.isUpsert).isFalse()
    }

    @Test
    @DisplayName("학습 진행 갱신은 문서 버전이 일치할 때만 적용된다")
    fun `학습 진행 갱신`() {
        stubFindAndModify(null)
        val solutions = Solutions()
        val lastSolvedAt = LocalDate.of(2026, 1, 2)

        repository.updateStudyProgressById("student-1", 7L, solutions, 3, lastSolvedAt)

        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("_id", "student-1").append("documentVersion", 7L)
        )
        val set = updateSlot.captured.updateObject["\$set"] as Document
        assertThat(set["solutions"]).isSameAs(solutions)
        assertThat(set["consecutiveSolveDays"]).isEqualTo(3)
        assertThat(set["lastSolvedAt"]).isEqualTo(lastSolvedAt)
        assertThat(updateSlot.captured.updateObject["\$inc"]).isEqualTo(Document("documentVersion", 1))
    }

    @Test
    @DisplayName("음수 문서 버전으로 학습 진행을 갱신하면 예외가 발생한다")
    fun `음수 문서 버전 거부`() {
        assertThatThrownBy {
            repository.updateStudyProgressById("student-1", -1L, Solutions(), 0, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("문서 버전은 0 이상이어야 합니다.")
    }

    @Test
    @DisplayName("기본 템플릿 지정은 카테고리별 필드에 기록된다")
    fun `기본 템플릿 지정`() {
        stubFindAndModify(null)

        repository.updateDefaultTemplateById("student-1", TemplateCategory.FAIL, "template-1")

        assertThat(querySlot.captured.queryObject).isEqualTo(Document("_id", "student-1"))
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document("\$set", Document("defaultFailTemplateId", "template-1"))
                .append("\$inc", Document("documentVersion", 1))
        )
    }

    @Test
    @DisplayName("빈 템플릿 ID로 기본 템플릿을 지정하면 예외가 발생한다")
    fun `빈 템플릿 ID 거부`() {
        assertThatThrownBy {
            repository.updateDefaultTemplateById("student-1", TemplateCategory.SUCCESS, " ")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("템플릿 ID는 필수입니다.")
    }

    @Test
    @DisplayName("기본 템플릿 참조 해제는 모든 카테고리가 기대한 템플릿을 가리킬 때만 적용된다")
    fun `기본 템플릿 참조 해제`() {
        stubFindAndModify(null)

        repository.clearDefaultTemplateReferences(
            "student-1",
            "template-1",
            linkedSetOf(TemplateCategory.SUCCESS, TemplateCategory.FAIL)
        )

        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("_id", "student-1")
                .append("defaultSuccessTemplateId", "template-1")
                .append("defaultFailTemplateId", "template-1")
        )
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document("\$inc", Document("documentVersion", 1))
                .append(
                    "\$unset",
                    Document("defaultSuccessTemplateId", 1).append("defaultFailTemplateId", 1)
                )
        )
    }

    @Test
    @DisplayName("해제할 카테고리가 없으면 예외가 발생한다")
    fun `기본 템플릿 참조 해제 카테고리 없음`() {
        assertThatThrownBy {
            repository.clearDefaultTemplateReferences("student-1", "template-1", emptySet())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("해제할 기본 템플릿 카테고리가 없습니다.")
    }

    @Test
    @DisplayName("관리자 회원 검색 결과가 없으면 목록 조회 없이 빈 페이지를 반환한다")
    fun `관리자 회원 검색 결과 없음`() {
        every { mongoTemplate.count(capture(querySlot), eq(Student::class.java)) } returns 0L

        val page = repository.searchAdminUsers(PageRequest.of(0, 10), null, null, null)

        assertThat(page.content).isEmpty()
        assertThat(page.totalElements).isZero()
        assertThat(querySlot.captured.queryObject).isEmpty()
        verify(exactly = 0) { mongoTemplate.find(any<Query>(), eq(Student::class.java)) }
    }

    @Test
    @DisplayName("요청 페이지가 전체 개수를 넘어서면 목록 조회 없이 빈 페이지를 반환한다")
    fun `관리자 회원 검색 페이지 범위 초과`() {
        every { mongoTemplate.count(any<Query>(), eq(Student::class.java)) } returns 5L

        val page = repository.searchAdminUsers(PageRequest.of(1, 5), null, null, null)

        assertThat(page.content).isEmpty()
        assertThat(page.totalElements).isEqualTo(5L)
        verify(exactly = 0) { mongoTemplate.find(any<Query>(), eq(Student::class.java)) }
    }

    @Test
    @DisplayName("검색어는 정규식 메타문자를 리터럴로 취급하고 대소문자를 구분하지 않는다")
    fun `관리자 회원 검색어 리터럴 매칭`() {
        val student: Student = mockk()
        val findSlot = slot<Query>()
        every { mongoTemplate.count(any<Query>(), eq(Student::class.java)) } returns 3L
        every { mongoTemplate.find(capture(findSlot), eq(Student::class.java)) } returns listOf(student)

        val page = repository.searchAdminUsers(
            PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")),
            "a.b*",
            null,
            null
        )

        assertThat(page.content).containsExactly(student)
        assertThat(page.totalElements).isEqualTo(3L)

        val query = findSlot.captured
        @Suppress("UNCHECKED_CAST")
        val orClauses = query.queryObject["\$or"] as List<Document>
        assertThat(orClauses.map { it.keys.single() }).containsExactly("nickname", "bojId", "email")
        orClauses.forEach { clause ->
            val pattern = clause.values.single() as Pattern
            assertThat(pattern.pattern()).isEqualTo(Pattern.quote("a.b*"))
            assertThat(pattern.flags() and Pattern.CASE_INSENSITIVE).isNotZero()
            assertThat(pattern.matcher("xxA.B*yy").find()).isTrue()
            assertThat(pattern.matcher("aXbb").find()).isFalse()
        }
        assertThat(query.skip).isEqualTo(0L)
        assertThat(query.limit).isEqualTo(2)
        assertThat(query.sortObject).isEqualTo(Document("createdAt", -1).append("_id", 1))
    }

    @Test
    @DisplayName("ID 정렬이 이미 있으면 보조 정렬을 추가하지 않는다")
    fun `관리자 회원 검색 ID 정렬 유지`() {
        val findSlot = slot<Query>()
        every { mongoTemplate.count(any<Query>(), eq(Student::class.java)) } returns 30L
        every { mongoTemplate.find(capture(findSlot), eq(Student::class.java)) } returns emptyList()

        repository.searchAdminUsers(PageRequest.of(2, 10, Sort.by(Sort.Direction.DESC, "id")), " ", null, null)

        assertThat(findSlot.captured.queryObject).isEmpty()
        assertThat(findSlot.captured.skip).isEqualTo(20L)
        assertThat(findSlot.captured.sortObject).isEqualTo(Document("id", -1))
    }

    @Test
    @DisplayName("현재 시각을 포함하는 가입일 범위는 createdAt이 없는 레거시 문서도 포함한다")
    fun `가입일 범위 레거시 포함`() {
        every { mongoTemplate.count(capture(querySlot), eq(Student::class.java)) } returns 0L
        val from = LocalDateTime.now().minusDays(1)

        repository.searchAdminUsers(PageRequest.of(0, 10), null, from, null)

        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document(
                "\$or",
                listOf(
                    Document("createdAt", Document("\$gte", from)),
                    Document("createdAt", null)
                )
            )
        )
    }

    @Test
    @DisplayName("과거 가입일 범위는 레거시 문서를 제외하고 검색어 조건과 AND로 결합된다")
    fun `가입일 범위와 검색어 결합`() {
        every { mongoTemplate.count(capture(querySlot), eq(Student::class.java)) } returns 0L
        val from = LocalDateTime.of(2024, 1, 1, 0, 0)
        val to = LocalDateTime.of(2024, 12, 31, 23, 59)

        repository.searchAdminUsers(PageRequest.of(0, 10), "kim", from, to)

        @Suppress("UNCHECKED_CAST")
        val andClauses = querySlot.captured.queryObject["\$and"] as List<Document>
        assertThat(andClauses).hasSize(2)
        assertThat(andClauses[0]).containsKey("\$or")
        assertThat(andClauses[1]).isEqualTo(
            Document("createdAt", Document("\$gte", from).append("\$lte", to))
        )
    }
}
