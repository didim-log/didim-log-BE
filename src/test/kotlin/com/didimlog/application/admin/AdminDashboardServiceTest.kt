package com.didimlog.application.admin

import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.AggregationResults

@DisplayName("AdminDashboardService 테스트")
class AdminDashboardServiceTest {

    private val studentRepository: StudentRepository = mockk()
    private val retrospectiveRepository: RetrospectiveRepository = mockk()
    private val mongoTemplate: MongoTemplate = mockk()
    private val adminDashboardService = AdminDashboardService(studentRepository, retrospectiveRepository, mongoTemplate)

    @Test
    @DisplayName("대시보드 통계 정보를 DB 집계 기반으로 조회할 수 있다")
    fun `대시보드 통계 조회 성공`() {
        // given
        every { studentRepository.count() } returns 2L
        every { studentRepository.countByCreatedAtBetween(any(), any()) } returns 1L
        every { retrospectiveRepository.countByCreatedAtBetween(any(), any()) } returns 3L

        every { mongoTemplate.count(any(), "logs") } returnsMany listOf(2L, 1L)

        every {
            mongoTemplate.aggregate(any<Aggregation>(), eq("students"), eq(Document::class.java))
        } returns AggregationResults(listOf(Document("count", 5L)), Document())

        every {
            mongoTemplate.aggregate(any<Aggregation>(), eq("logs"), eq(Document::class.java))
        } returns AggregationResults(listOf(Document("avgDuration", 12000.0)), Document())

        // when
        val result = adminDashboardService.getDashboardStats()

        // then
        assertThat(result.totalUsers).isEqualTo(2L)
        assertThat(result.todaySignups).isEqualTo(1L)
        assertThat(result.totalSolvedProblems).isEqualTo(5L)
        assertThat(result.todayRetrospectives).isEqualTo(3L)
        assertThat(result.aiMetrics.totalGeneratedCount).isEqualTo(2L)
        assertThat(result.aiMetrics.averageDurationMillis).isEqualTo(12000L)
        assertThat(result.aiMetrics.timeoutCount).isEqualTo(1L)

        verify(exactly = 1) { studentRepository.count() }
        verify(exactly = 1) { studentRepository.countByCreatedAtBetween(any(), any()) }
        verify(exactly = 1) { retrospectiveRepository.countByCreatedAtBetween(any(), any()) }
    }
}
