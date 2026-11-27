package com.didimlog.application.log

import com.didimlog.domain.Log
import com.didimlog.domain.enums.AiReviewStatus
import com.didimlog.domain.valueobject.AiReview
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.mongodb.client.result.UpdateResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update

@DisplayName("MongoLogAiReviewLockRepository 테스트")
class MongoLogAiReviewLockRepositoryTest {

    private val mongoTemplate: MongoTemplate = mockk()
    private val repository = MongoLogAiReviewLockRepository(mongoTemplate)

    private val now = LocalDateTime.of(2026, 3, 1, 10, 0)
    private val expiresAt = now.plusMinutes(5)
    private val lock = AiReviewLock(4L)

    private val querySlot = slot<Query>()
    private val updateSlot = slot<Update>()

    private fun ownedLockQuery(): Document = Document("_id", "log-1")
        .append("aiReview", null)
        .append("aiReviewStatus", AiReviewStatus.IN_PROGRESS)
        .append("aiReviewLockVersion", 4L)
        .append("aiReviewLockExpiresAt", Document("\$gt", now))

    private fun stubUpdateFirst(matched: Long, modified: Long) {
        every {
            mongoTemplate.updateFirst(capture(querySlot), capture(updateSlot), eq(Log::class.java))
        } returns UpdateResult.acknowledged(matched, modified, null)
    }

    @Test
    @DisplayName("잠금 획득은 리뷰가 없고 진행 중이 아니거나 잠금이 만료된 로그에만 적용되고 새 버전을 반환한다")
    fun `잠금 획득 성공`() {
        val optionsSlot = slot<FindAndModifyOptions>()
        val updatedLog = Log(
            id = "log-1",
            title = LogTitle("제목"),
            content = LogContent("내용"),
            code = LogCode("print(1)"),
            aiReviewLockVersion = 7L
        )
        every {
            mongoTemplate.findAndModify(
                capture(querySlot),
                capture(updateSlot),
                capture(optionsSlot),
                eq(Log::class.java)
            )
        } returns updatedLog

        val acquired = repository.tryAcquireLock("log-1", now, expiresAt)

        assertThat(acquired).isEqualTo(AiReviewLock(7L))
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("_id", "log-1")
                .append("aiReview", null)
                .append(
                    "\$or",
                    listOf(
                        Document("aiReviewStatus", Document("\$ne", AiReviewStatus.IN_PROGRESS)),
                        Document(
                            "\$or",
                            listOf(
                                Document("aiReviewLockExpiresAt", null),
                                Document("aiReviewLockExpiresAt", Document("\$lte", now))
                            )
                        )
                    )
                )
        )
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document(
                "\$set",
                Document("aiReviewStatus", AiReviewStatus.IN_PROGRESS)
                    .append("aiReviewLockExpiresAt", expiresAt)
            ).append("\$inc", Document("aiReviewLockVersion", 1))
        )
        assertThat(optionsSlot.captured.isReturnNew).isTrue()
    }

    @Test
    @DisplayName("잠금 조건에 맞는 로그가 없으면 null을 반환한다")
    fun `잠금 획득 실패`() {
        every {
            mongoTemplate.findAndModify(any<Query>(), any<Update>(), any<FindAndModifyOptions>(), eq(Log::class.java))
        } returns null

        assertThat(repository.tryAcquireLock("log-1", now, expiresAt)).isNull()
    }

    @Test
    @DisplayName("잠금 연장은 같은 버전의 만료되지 않은 잠금에만 적용된다")
    fun `잠금 연장`() {
        stubUpdateFirst(matched = 1L, modified = 0L)

        val renewed = repository.renewLock("log-1", lock, now, expiresAt)

        assertThat(renewed).isTrue()
        assertThat(querySlot.captured.queryObject).isEqualTo(ownedLockQuery())
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document("\$set", Document("aiReviewLockExpiresAt", expiresAt))
        )
    }

    @Test
    @DisplayName("잠금을 잃었으면 연장에 실패한다")
    fun `잠금 연장 실패`() {
        stubUpdateFirst(matched = 0L, modified = 0L)

        assertThat(repository.renewLock("log-1", lock, now, expiresAt)).isFalse()
    }

    @Test
    @DisplayName("진행 여부는 리뷰가 없고 만료되지 않은 진행 중 잠금이 있는지로 판단한다")
    fun `진행 중 여부 조회`() {
        every { mongoTemplate.exists(capture(querySlot), eq(Log::class.java)) } returns true

        assertThat(repository.isInProgress("log-1", now)).isTrue()
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("_id", "log-1")
                .append("aiReview", null)
                .append("aiReviewStatus", AiReviewStatus.IN_PROGRESS)
                .append("aiReviewLockExpiresAt", Document("\$gt", now))
        )
    }

    @Test
    @DisplayName("완료 처리는 리뷰와 소요 시간을 기록하고 잠금 만료 시각을 제거한다")
    fun `완료 처리 성공`() {
        stubUpdateFirst(matched = 1L, modified = 1L)

        val completed = repository.markCompleted("log-1", lock, now, "리뷰 본문", 1200L)

        assertThat(completed).isTrue()
        assertThat(querySlot.captured.queryObject).isEqualTo(ownedLockQuery())
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document(
                "\$set",
                Document("aiReview", AiReview("리뷰 본문"))
                    .append("aiReviewStatus", AiReviewStatus.COMPLETED)
                    .append("aiReviewDurationMillis", 1200L)
            ).append("\$unset", Document("aiReviewLockExpiresAt", 1))
        )
    }

    @Test
    @DisplayName("문서가 변경되지 않았으면 완료 처리는 실패로 본다")
    fun `완료 처리 실패`() {
        stubUpdateFirst(matched = 1L, modified = 0L)

        assertThat(repository.markCompleted("log-1", lock, now, "리뷰", 10L)).isFalse()
    }

    @Test
    @DisplayName("실패 처리는 상태를 FAILED로 바꾸고 잠금 만료 시각을 제거한다")
    fun `실패 처리`() {
        stubUpdateFirst(matched = 1L, modified = 1L)

        val failed = repository.markFailed("log-1", lock, now)

        assertThat(failed).isTrue()
        assertThat(querySlot.captured.queryObject).isEqualTo(ownedLockQuery())
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document("\$set", Document("aiReviewStatus", AiReviewStatus.FAILED))
                .append("\$unset", Document("aiReviewLockExpiresAt", 1))
        )
    }

    @Test
    @DisplayName("잠금을 잃은 뒤의 실패 처리는 false를 반환한다")
    fun `실패 처리 잠금 상실`() {
        stubUpdateFirst(matched = 0L, modified = 0L)

        assertThat(repository.markFailed("log-1", lock, now)).isFalse()
    }
}
