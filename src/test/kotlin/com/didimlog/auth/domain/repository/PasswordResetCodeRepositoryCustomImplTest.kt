package com.didimlog.auth.domain.repository

import com.didimlog.auth.domain.PasswordResetCode
import com.mongodb.client.result.DeleteResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update

@DisplayName("PasswordResetCodeRepositoryCustomImpl 테스트")
class PasswordResetCodeRepositoryCustomImplTest {

    private val mongoTemplate: MongoTemplate = mockk()
    private val repository = PasswordResetCodeRepositoryCustomImpl(mongoTemplate)

    private val createdAt = LocalDateTime.of(2026, 5, 1, 9, 0)
    private val expiresAt = createdAt.plusMinutes(30)

    private val querySlot = slot<Query>()

    @Test
    @DisplayName("코드 발급은 학생당 하나의 문서를 upsert로 교체한다")
    fun `코드 발급 upsert`() {
        val updateSlot = slot<Update>()
        val optionsSlot = slot<FindAndModifyOptions>()
        val issued = PasswordResetCode(
            resetCode = "RESET001",
            studentId = "student-1",
            credentialVersion = 2,
            bojId = "boj_user",
            expiresAt = expiresAt,
            createdAt = createdAt
        )
        every {
            mongoTemplate.findAndModify(
                capture(querySlot),
                capture(updateSlot),
                capture(optionsSlot),
                eq(PasswordResetCode::class.java)
            )
        } returns issued

        val result = repository.issueForStudent("student-1", "RESET001", expiresAt, createdAt, 2L, "boj_user")

        assertThat(result).isSameAs(issued)
        assertThat(querySlot.captured.queryObject).isEqualTo(Document("studentId", "student-1"))
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document(
                "\$set",
                Document("resetCode", "RESET001")
                    .append("credentialVersion", 2L)
                    .append("bojId", "boj_user")
                    .append("expiresAt", expiresAt)
                    .append("createdAt", createdAt)
            )
        )
        assertThat(optionsSlot.captured.isUpsert).isTrue()
        assertThat(optionsSlot.captured.isReturnNew).isTrue()
    }

    @Test
    @DisplayName("upsert 결과가 없으면 IllegalStateException이 발생한다")
    fun `코드 발급 결과 없음`() {
        every {
            mongoTemplate.findAndModify(
                any<Query>(),
                any<Update>(),
                any<FindAndModifyOptions>(),
                eq(PasswordResetCode::class.java)
            )
        } returns null

        assertThatThrownBy {
            repository.issueForStudent("student-1", "RESET001", expiresAt, createdAt, 0L, "boj_user")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("studentId=student-1")
    }

    @Test
    @DisplayName("코드 소비는 코드와 학생 ID가 모두 일치하는 문서를 원자적으로 제거한다")
    fun `코드 소비`() {
        every {
            mongoTemplate.findAndRemove(capture(querySlot), eq(PasswordResetCode::class.java))
        } returns null

        val consumed = repository.consumeByResetCode("RESET001", "student-1")

        assertThat(consumed).isNull()
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("resetCode", "RESET001").append("studentId", "student-1")
        )
    }

    @Test
    @DisplayName("발급 코드 삭제는 정확히 한 건이 지워졌을 때만 true를 반환한다")
    fun `발급 코드 조건부 삭제`() {
        every {
            mongoTemplate.remove(capture(querySlot), eq(PasswordResetCode::class.java))
        } returnsMany listOf(DeleteResult.acknowledged(1L), DeleteResult.acknowledged(0L))

        assertThat(repository.deleteIssuedCode("student-1", "RESET001")).isTrue()
        assertThat(repository.deleteIssuedCode("student-1", "RESET001")).isFalse()
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("studentId", "student-1").append("resetCode", "RESET001")
        )
    }
}
