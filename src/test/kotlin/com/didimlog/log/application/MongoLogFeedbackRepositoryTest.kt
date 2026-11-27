package com.didimlog.log.application

import com.didimlog.log.domain.Log
import com.didimlog.log.domain.AiFeedbackStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update

@DisplayName("MongoLogFeedbackRepository 테스트")
class MongoLogFeedbackRepositoryTest {

    private val mongoTemplate: MongoTemplate = mockk()
    private val repository = MongoLogFeedbackRepository(mongoTemplate)

    private val querySlot = slot<Query>()
    private val updateSlot = slot<Update>()
    private val optionsSlot = slot<FindAndModifyOptions>()

    private fun stubFindAndModify() {
        every {
            mongoTemplate.findAndModify(
                capture(querySlot),
                capture(updateSlot),
                capture(optionsSlot),
                eq(Log::class.java)
            )
        } returns null
    }

    @Test
    @DisplayName("피드백 갱신은 로그 소유자 조건으로 상태와 사유를 함께 저장한다")
    fun `사유 포함 피드백 저장`() {
        stubFindAndModify()

        val result = repository.updateFeedback("log-1", "student-1", AiFeedbackStatus.DISLIKE, "INACCURATE")

        assertThat(result).isNull()
        assertThat(querySlot.captured.queryObject).isEqualTo(
            Document("_id", "log-1").append("studentId", "student-1")
        )
        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document(
                "\$set",
                Document("aiFeedbackStatus", AiFeedbackStatus.DISLIKE).append("aiFeedbackReason", "INACCURATE")
            )
        )
        assertThat(optionsSlot.captured.isReturnNew).isTrue()
        assertThat(optionsSlot.captured.isUpsert).isFalse()
    }

    @Test
    @DisplayName("사유가 없으면 이전 사유를 제거한다")
    fun `사유 없는 피드백 저장`() {
        stubFindAndModify()

        repository.updateFeedback("log-1", "student-1", AiFeedbackStatus.LIKE, null)

        assertThat(updateSlot.captured.updateObject).isEqualTo(
            Document("\$set", Document("aiFeedbackStatus", AiFeedbackStatus.LIKE))
                .append("\$unset", Document("aiFeedbackReason", 1))
        )
    }
}
