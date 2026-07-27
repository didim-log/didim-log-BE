package com.didimlog.domain.repository

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update

@DisplayName("관리자 회원 검색 저장소 통합 테스트")
@DataMongoTest
class StudentAdminQueryIntegrationTest {

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @BeforeEach
    fun setUp() {
        studentRepository.deleteAll()
    }

    @Test
    fun `닉네임 BOJ ID 이메일을 대소문자 구분 없이 리터럴 부분 검색한다`() {
        studentRepository.saveAll(
            listOf(
                createStudent(
                    id = "nickname-literal",
                    nickname = "Dot.User",
                    bojId = "plain_boj",
                    email = "plain@example.com"
                ),
                createStudent(
                    id = "nickname-regex-decoy",
                    nickname = "dotXuser",
                    bojId = "decoy_boj",
                    email = "decoy@example.com"
                ),
                createStudent(
                    id = "boj-case-match",
                    nickname = "BojUser",
                    bojId = "BojCase_42",
                    email = "boj@example.com"
                ),
                createStudent(
                    id = "email-literal",
                    nickname = "MailUser",
                    bojId = "mail_boj",
                    email = "owner+Tag@example.com"
                ),
                createStudent(
                    id = "email-regex-decoy",
                    nickname = "MailDecoy",
                    bojId = "other_boj",
                    email = "ownerXTag@example.com"
                )
            )
        )

        assertThat(search(search = ".u").content.mapNotNull(Student::id))
            .containsExactly("nickname-literal")
        assertThat(search(search = "jCaSe_4").content.mapNotNull(Student::id))
            .containsExactly("boj-case-match")
        assertThat(search(search = "+tAg").content.mapNotNull(Student::id))
            .containsExactly("email-literal")
    }

    @Test
    fun `공백 검색어는 무시하고 공백이 포함된 검색어는 자르지 않는다`() {
        studentRepository.saveAll(
            listOf(
                createStudent(id = "alpha", nickname = "AlphaUser", rating = 2000),
                createStudent(id = "beta", nickname = "BetaUser", rating = 1000)
            )
        )

        assertThat(search(search = "   ").content.mapNotNull(Student::id))
            .containsExactly("alpha", "beta")
        assertThat(search(search = "alpha").content.mapNotNull(Student::id))
            .containsExactly("alpha")
        assertThat(search(search = " alpha ").content)
            .isEmpty()
    }

    @Test
    fun `가입일은 시작과 초 단위 종료 경계를 포함하고 종료 시각의 소수 초는 제외한다`() {
        val start = LocalDateTime.of(2026, 4, 1, 0, 0)
        val end = LocalDateTime.of(2026, 4, 30, 23, 59, 59)
        studentRepository.saveAll(
            listOf(
                createStudent(
                    id = "before-start",
                    nickname = "BeforeUser",
                    createdAt = start.minusNanos(1)
                ),
                createStudent(
                    id = "at-start",
                    nickname = "StartUser",
                    createdAt = start
                ),
                createStudent(
                    id = "at-end",
                    nickname = "EndUser",
                    createdAt = end
                ),
                createStudent(
                    id = "fractional-after-end",
                    nickname = "AfterUser",
                    createdAt = end.plusNanos(500_000_000)
                )
            )
        )

        val result = search(createdAtFrom = start, createdAtTo = end)

        assertThat(result.content.mapNotNull(Student::id))
            .containsExactlyInAnyOrder("at-start", "at-end")
        assertThat(result.totalElements).isEqualTo(2)
    }

    @Test
    fun `검색어와 가입일 조건을 AND로 결합한다`() {
        val start = LocalDateTime.of(2026, 5, 1, 0, 0)
        val end = LocalDateTime.of(2026, 5, 31, 23, 59, 59)
        studentRepository.saveAll(
            listOf(
                createStudent(
                    id = "match-in-range",
                    nickname = "MatchUser",
                    createdAt = start.plusDays(1)
                ),
                createStudent(
                    id = "match-outside-range",
                    nickname = "MatchOld",
                    createdAt = start.minusDays(1)
                ),
                createStudent(
                    id = "non-match-in-range",
                    nickname = "OtherUser",
                    createdAt = start.plusDays(2)
                )
            )
        )

        val result = search(
            search = "MATCH",
            createdAtFrom = start,
            createdAtTo = end
        )

        assertThat(result.content.mapNotNull(Student::id))
            .containsExactly("match-in-range")
        assertThat(result.totalElements).isEqualTo(1)
    }

    @Test
    fun `rating 내림차순과 ID 오름차순으로 동점 페이지를 안정적으로 나눈다`() {
        studentRepository.saveAll(
            listOf(
                createStudent(id = "tie-c", nickname = "TieUserC", rating = 2000),
                createStudent(id = "low", nickname = "LowUser", rating = 1000),
                createStudent(id = "tie-a", nickname = "TieUserA", rating = 2000),
                createStudent(id = "high", nickname = "HighUser", rating = 3000),
                createStudent(id = "tie-b", nickname = "TieUserB", rating = 2000)
            )
        )

        val firstPage = search(page = 0, size = 2)
        val secondPage = search(page = 1, size = 2)

        assertThat(firstPage.content.mapNotNull(Student::id))
            .containsExactly("high", "tie-a")
        assertThat(secondPage.content.mapNotNull(Student::id))
            .containsExactly("tie-b", "tie-c")
        assertThat(firstPage.totalElements).isEqualTo(5)
        assertThat(secondPage.totalElements).isEqualTo(5)
    }

    @Test
    fun `필터 결과의 전체 건수를 유지하고 범위를 벗어난 페이지는 비어 있다`() {
        studentRepository.saveAll(
            listOf(
                createStudent(id = "team-high", nickname = "TeamHigh", rating = 3000),
                createStudent(id = "team-mid", nickname = "TeamMid", rating = 2000),
                createStudent(
                    id = "team-email",
                    nickname = "EmailUser",
                    email = "team@example.com",
                    rating = 1000
                ),
                createStudent(id = "outside", nickname = "Outside", rating = 4000)
            )
        )

        val firstPage = search(page = 0, size = 2, search = "team")
        val outOfRange = search(page = 5, size = 2, search = "team")

        assertThat(firstPage.content.mapNotNull(Student::id))
            .containsExactly("team-high", "team-mid")
        assertThat(firstPage.totalElements).isEqualTo(3)
        assertThat(outOfRange.content).isEmpty()
        assertThat(outOfRange.totalElements).isEqualTo(3)
    }

    @Test
    fun `createdAt이 없는 기존 문서는 조회 시각을 가입일로 사용하는 호환성을 유지한다`() {
        studentRepository.save(
            createStudent(
                id = "legacy",
                nickname = "LegacyUser",
                createdAt = LocalDateTime.of(2020, 1, 1, 0, 0)
            )
        )
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").`is`("legacy")),
            Update().unset("createdAt"),
            Student::class.java
        )
        val now = LocalDateTime.now()

        val currentRange = search(
            createdAtFrom = now.minusDays(1),
            createdAtTo = now.plusDays(1)
        )
        val futureRange = search(
            createdAtFrom = now.plusDays(1),
            createdAtTo = now.plusDays(2)
        )

        assertThat(currentRange.content.mapNotNull(Student::id))
            .containsExactly("legacy")
        assertThat(currentRange.content.single().createdAt)
            .isBetween(now.minusDays(1), now.plusDays(1))
        assertThat(futureRange.content).isEmpty()
    }

    private fun search(
        page: Int = 0,
        size: Int = 10,
        search: String? = null,
        createdAtFrom: LocalDateTime? = null,
        createdAtTo: LocalDateTime? = null
    ): Page<Student> {
        val pageable = PageRequest.of(
            page,
            size,
            Sort.by(Sort.Direction.DESC, "rating")
        )
        return studentRepository.searchAdminUsers(
            pageable = pageable,
            search = search,
            createdAtFrom = createdAtFrom,
            createdAtTo = createdAtTo
        )
    }

    private fun createStudent(
        id: String,
        nickname: String,
        bojId: String? = null,
        email: String? = null,
        rating: Int = 1000,
        createdAt: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0)
    ): Student {
        return Student(
            id = id,
            nickname = Nickname(nickname),
            provider = Provider.BOJ,
            providerId = "provider-$id",
            email = email,
            bojId = bojId?.let(::BojId),
            password = "encoded-password",
            rating = rating,
            currentTier = Tier.fromRating(rating),
            role = Role.USER,
            createdAt = createdAt
        )
    }
}
