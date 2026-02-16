package com.didimlog.application.storage

import com.didimlog.domain.Retrospective
import com.didimlog.domain.repository.RetrospectiveRepository
import com.mongodb.client.result.DeleteResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import java.time.LocalDateTime

@DisplayName("StorageManagementService 테스트")
class StorageManagementServiceTest {

    private val retrospectiveRepository: RetrospectiveRepository = mockk()
    private val mongoTemplate: MongoTemplate = mockk()
    private val storageManagementService = StorageManagementService(retrospectiveRepository, mongoTemplate)

    @Test
    fun `저장 공간 통계 조회 성공`() {
        every { retrospectiveRepository.count() } returns 12L
        every { mongoTemplate.findOne(any<Query>(), Retrospective::class.java) } returns Retrospective(
            studentId = "student-1",
            problemId = "1000",
            content = "충분히 긴 회고 내용입니다.",
            createdAt = LocalDateTime.of(2024, 1, 1, 0, 0)
        )

        val stats = storageManagementService.getStats()

        assertThat(stats.totalCount).isEqualTo(12L)
        assertThat(stats.estimatedSizeKb).isEqualTo(24L)
        assertThat(stats.oldestRecordDate.toString()).isEqualTo("2024-01-01")
    }

    @Test
    fun `오래된 회고 삭제는 30일 미만 입력을 거부한다`() {
        assertThatThrownBy {
            storageManagementService.deleteOldRetrospectives(29)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("최소 30일")
    }

    @Test
    fun `오래된 회고 삭제 성공`() {
        every { mongoTemplate.remove(any<Query>(), Retrospective::class.java) } returns DeleteResult.acknowledged(7L)

        val deleted = storageManagementService.deleteOldRetrospectives(90)

        assertThat(deleted).isEqualTo(7L)
        verify(exactly = 1) { mongoTemplate.remove(any<Query>(), Retrospective::class.java) }
    }
}

