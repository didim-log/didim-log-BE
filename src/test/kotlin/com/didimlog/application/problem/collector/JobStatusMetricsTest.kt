package com.didimlog.application.problem.collector

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("문제 수집 Job 상태 계산 테스트")
class JobStatusMetricsTest {

    @Test
    fun `metadata progress 는 total 이 0일 때 completed 면 100 running 이면 0`() {
        val completed = MetadataCollectJobStatus(
            jobId = "m-1",
            status = JobStatus.COMPLETED,
            totalCount = 0,
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            startProblemId = 1,
            endProblemId = 1,
            startedAt = 1L
        )
        val running = completed.copy(status = JobStatus.RUNNING)

        assertThat(completed.progressPercentage).isEqualTo(100)
        assertThat(running.progressPercentage).isEqualTo(0)
    }

    @Test
    fun `metadata progress 는 processed 범위를 total 에 맞춰 보정한다`() {
        val status = MetadataCollectJobStatus(
            jobId = "m-2",
            status = JobStatus.RUNNING,
            totalCount = 10,
            processedCount = 999,
            successCount = 0,
            failCount = 0,
            startProblemId = 1,
            endProblemId = 10,
            startedAt = 1L
        )

        assertThat(status.progressPercentage).isEqualTo(100)
    }

    @Test
    fun `metadata estimatedRemainingSeconds 는 running 에서만 계산된다`() {
        val running = MetadataCollectJobStatus(
            jobId = "m-3",
            status = JobStatus.RUNNING,
            totalCount = 20,
            processedCount = 5,
            successCount = 5,
            failCount = 0,
            startProblemId = 1,
            endProblemId = 20,
            startedAt = 1L
        )
        val done = running.copy(status = JobStatus.COMPLETED)

        assertThat(running.estimatedRemainingSeconds).isEqualTo(15L)
        assertThat(done.estimatedRemainingSeconds).isNull()
    }

    @Test
    fun `details estimatedRemainingSeconds 는 문제당 3초 기준으로 계산된다`() {
        val status = DetailsCollectJobStatus(
            jobId = "d-1",
            status = JobStatus.RUNNING,
            totalCount = 12,
            processedCount = 2,
            successCount = 2,
            failCount = 0,
            startedAt = 1L
        )

        assertThat(status.progressPercentage).isEqualTo(16)
        assertThat(status.estimatedRemainingSeconds).isEqualTo(30L)
    }

    @Test
    fun `language update 도 progress 와 remaining 계산 규칙을 따른다`() {
        val status = LanguageUpdateJobStatus(
            jobId = "l-1",
            status = JobStatus.RUNNING,
            totalCount = 4,
            processedCount = 1,
            successCount = 1,
            failCount = 0,
            startedAt = 1L
        )

        assertThat(status.progressPercentage).isEqualTo(25)
        assertThat(status.estimatedRemainingSeconds).isEqualTo(9L)
    }
}
