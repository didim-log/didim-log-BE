package com.didimlog.application.problem.collector

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.solvedac.SolvedAcClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.event.command.CommandListener
import io.lettuce.core.event.command.CommandStartedEvent
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

@DataRedisTest(
    properties = [
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=\${TEST_REDIS_PORT:6379}",
        "spring.data.redis.database=12"
    ]
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("문제 수집 작업 목록 Redis 조회 통합 테스트")
class ProblemCollectorJobListQueryIntegrationTest {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var connectionFactory: LettuceConnectionFactory

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val commandRecorder = JobListRedisCommandRecorder()
    private lateinit var nativeClient: AbstractRedisClient
    private lateinit var service: ProblemCollectorService

    @BeforeAll
    fun registerCommandRecorder() {
        nativeClient = connectionFactory.requiredNativeClient
        nativeClient.addListener(commandRecorder)
        connectionFactory.resetConnection()

        redisTemplate.opsForValue().set(WARMUP_KEY, "1")
        redisTemplate.opsForValue().get(WARMUP_KEY)
        redisTemplate.delete(WARMUP_KEY)
        commandRecorder.disableAndReset()
    }

    @AfterAll
    fun unregisterCommandRecorder() {
        commandRecorder.disableAndReset()
        nativeClient.removeListener(commandRecorder)
        connectionFactory.resetConnection()
    }

    @BeforeEach
    fun setUp() {
        commandRecorder.disableAndReset()
        clearJobKeys()
        service = ProblemCollectorService(
            solvedAcClient = mockk<SolvedAcClient>(),
            problemRepository = mockk<ProblemRepository>(),
            bojCrawler = mockk<BojCrawler>(),
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            adminAuditService = mockk<AdminAuditService>(relaxed = true),
            pacer = mockk<ProblemCollectorPacer>(relaxed = true),
            taskExecutor = null
        )
    }

    @AfterEach
    fun cleanUp() {
        commandRecorder.disableAndReset()
        clearJobKeys()
    }

    @ParameterizedTest(name = "indexed jobs={0}")
    @ValueSource(ints = [1, 5, 20])
    fun `작업 상태를 한 번에 읽고 기존 페이지 응답을 유지한다`(jobCount: Int) {
        val jobs = sampleJobs(jobCount)
        jobs.forEach(::persist)
        val expected = expectedPage(jobs, page = 1, size = jobCount)

        val legacy = capture {
            legacyGetJobs(page = 1, size = jobCount)
        }
        val current = capture {
            service.getJobs(
                type = null,
                status = null,
                from = null,
                to = null,
                page = 1,
                size = jobCount
            )
        }

        assertThat(legacy.value).isEqualTo(expected)
        assertThat(current.value).isEqualTo(expected)
        assertThat(current.value).isEqualTo(legacy.value)

        assertThat(legacy.commands.counts())
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "get" to jobCount,
                    "zrevrange" to 1
                )
            )
        assertThat(current.commands.counts())
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "mget" to 1,
                    "zrevrange" to 1
                )
            )
        assertThat(current.commands.requireSingle("mget").argumentCount)
            .isEqualTo(jobCount)
    }

    @Test
    fun `빈 index에서는 상태 일괄 조회를 생략한다`() {
        val result = capture {
            service.getJobs(
                type = null,
                status = null,
                from = null,
                to = null,
                page = 1,
                size = 20
            )
        }

        assertThat(result.value).isEqualTo(
            JobPageResponse<JobStatusUnifiedResponse>(
                content = emptyList(),
                page = 1,
                size = 20,
                totalElements = 0,
                totalPages = 0,
                hasNext = false,
                hasPrevious = false
            )
        )
        assertThat(result.commands.counts())
            .containsExactlyInAnyOrderEntriesOf(mapOf("zrevrange" to 1))
        assertThat(result.commands.count("mget")).isZero()
    }

    @Test
    fun `만료 상태와 손상 JSON을 제외하고 index와 실패 원장을 한 번에 정리한다`() {
        val validJobs = sampleJobs(20)
        seedStaleFixture(validJobs)
        val expected = expectedPage(validJobs, page = 1, size = 20)

        val legacy = capture {
            legacyGetJobs(page = 1, size = 20)
        }

        assertThat(legacy.value).isEqualTo(expected)
        assertThat(legacy.commands.counts())
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "del" to 2,
                    "get" to 22,
                    "zrem" to 2,
                    "zrevrange" to 1
                )
            )
        assertThat(legacy.commands.totalCount()).isEqualTo(27)
        assertStaleEntriesRemoved(validJobs.first().jobId)

        clearJobKeys()
        seedStaleFixture(validJobs)

        val current = capture {
            service.getJobs(
                type = null,
                status = null,
                from = null,
                to = null,
                page = 1,
                size = 20
            )
        }

        assertThat(current.value).isEqualTo(expected)
        assertThat(current.value).isEqualTo(legacy.value)
        assertThat(current.commands.counts())
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "del" to 1,
                    "mget" to 1,
                    "type" to 1,
                    "zrem" to 1,
                    "zrevrange" to 1
                )
            )
        assertThat(current.commands.totalCount()).isEqualTo(5)
        assertThat(current.commands.requireSingle("mget").argumentCount).isEqualTo(22)
        assertThat(current.commands.requireSingle("zrem").argumentCount).isEqualTo(3)
        assertThat(current.commands.requireSingle("del").argumentCount).isEqualTo(2)
        assertStaleEntriesRemoved(validJobs.first().jobId)
    }

    @Test
    fun `상태 키 자료형이 잘못되면 충돌을 반환하고 관련 키를 삭제하지 않는다`() {
        val wrongTypeJobId = "job-wrong-type"
        redisTemplate.opsForSet().add(jobKey(wrongTypeJobId), "not-a-job-status")
        redisTemplate.opsForSet().add(failureKey(wrongTypeJobId), "1000")
        redisTemplate.opsForZSet().add(
            JOB_INDEX_KEY,
            wrongTypeJobId,
            (BASE_QUEUED_AT + 1).toDouble()
        )

        commandRecorder.enableAndReset()
        val result = runCatching {
            service.getJobs(
                type = null,
                status = null,
                from = null,
                to = null,
                page = 1,
                size = 20
            )
        }
        val commands = commandRecorder.disableAndSnapshot()

        val exception = result.exceptionOrNull()
        assertThat(exception).isInstanceOf(BusinessException::class.java)
        assertThat((exception as BusinessException).errorCode)
            .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        assertThat(commands.counts())
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "mget" to 1,
                    "type" to 1,
                    "zrevrange" to 1
                )
            )
        assertThat(commands.count("zrem")).isZero()
        assertThat(commands.count("del")).isZero()

        assertThat(redisTemplate.type(jobKey(wrongTypeJobId))).isEqualTo(DataType.SET)
        assertThat(redisTemplate.hasKey(failureKey(wrongTypeJobId))).isTrue()
        assertThat(redisTemplate.opsForZSet().score(JOB_INDEX_KEY, wrongTypeJobId)).isNotNull
    }

    @Test
    fun `메트릭과 감사 목록 및 대기 작업 단건 조회가 같은 상태 목록을 유지한다`() {
        val now = Instant.now().epochSecond
        val oldestPending = operationalJob(
            jobId = "job-pending-oldest",
            status = JobStatus.PENDING,
            queuedAt = now - 5
        )
        val newestPending = operationalJob(
            jobId = "job-pending-newest",
            status = JobStatus.PENDING,
            queuedAt = now - 4
        )
        val completed = operationalJob(
            jobId = "job-completed",
            status = JobStatus.COMPLETED,
            queuedAt = now - 3,
            startedAt = now - 20,
            completedAt = now - 10,
            successCount = 10
        )
        val failed = operationalJob(
            jobId = "job-failed",
            status = JobStatus.FAILED,
            queuedAt = now - 2,
            startedAt = now - 9,
            completedAt = now - 4,
            successCount = 8,
            failCount = 2,
            errorCode = "REMOTE_FAILURE"
        )
        val cancelled = operationalJob(
            jobId = "job-cancelled",
            status = JobStatus.CANCELLED,
            queuedAt = now - 1,
            startedAt = now - 5,
            completedAt = now - 1
        )
        listOf(oldestPending, newestPending, completed, failed, cancelled).forEach(::persist)

        val pending = requireNotNull(service.getJob(oldestPending.jobId))
        val metrics = service.getJobMetrics(JobMetricsWindow.DAY)
        val audit = service.getJobAudit(
            type = null,
            status = JobStatus.PENDING,
            from = now - 5,
            to = now - 4,
            page = 1,
            size = 1
        )

        assertThat(pending.queuePosition).isEqualTo(1)
        assertThat(metrics.totalJobs).isEqualTo(5)
        assertThat(metrics.completedJobs).isEqualTo(1)
        assertThat(metrics.failedJobs).isEqualTo(1)
        assertThat(metrics.cancelledJobs).isEqualTo(1)
        assertThat(metrics.averageDurationSeconds).isEqualTo(6)
        assertThat(metrics.averageFailureRate).isCloseTo(0.04, offset(0.000001))
        assertThat(metrics.topErrorCodes)
            .containsExactly(JobErrorCodeMetric("REMOTE_FAILURE", 1))

        assertThat(audit.content.map(JobAuditResponse::jobId))
            .containsExactly(newestPending.jobId)
        assertThat(audit.totalElements).isEqualTo(2)
        assertThat(audit.totalPages).isEqualTo(2)
        assertThat(audit.hasNext).isTrue()
        assertThat(audit.hasPrevious).isFalse()
    }

    private fun capture(
        action: () -> JobPageResponse<JobStatusUnifiedResponse>
    ): CapturedJobPage {
        commandRecorder.enableAndReset()
        return try {
            val value = action()
            CapturedJobPage(value, commandRecorder.disableAndSnapshot())
        } catch (throwable: Throwable) {
            commandRecorder.disableAndReset()
            throw throwable
        }
    }

    private fun legacyGetJobs(page: Int, size: Int): JobPageResponse<JobStatusUnifiedResponse> {
        val ids = redisTemplate.opsForZSet().reverseRange(JOB_INDEX_KEY, 0, -1).orEmpty()
        val jobs = mutableListOf<JobStatusUnifiedResponse>()

        ids.forEach { jobId ->
            val job = redisTemplate.opsForValue().get(jobKey(jobId))
                ?.let { json ->
                    runCatching {
                        objectMapper.readValue(json, JobStatusUnifiedResponse::class.java)
                    }.getOrNull()
                }
            if (job == null) {
                redisTemplate.opsForZSet().remove(JOB_INDEX_KEY, jobId)
                redisTemplate.delete(failureKey(jobId))
            } else {
                jobs.add(job)
            }
        }

        val ordered = withExpectedQueuePositions(jobs.sortedByDescending { it.queuedAt })
        val offset = (page - 1) * size
        val content = if (offset >= ordered.size) {
            emptyList()
        } else {
            ordered.subList(offset, min(offset + size, ordered.size))
        }
        return pageResponse(content, page, size, ordered.size.toLong())
    }

    private fun expectedPage(
        jobs: List<JobStatusUnifiedResponse>,
        page: Int,
        size: Int
    ): JobPageResponse<JobStatusUnifiedResponse> {
        val ordered = withExpectedQueuePositions(jobs.sortedByDescending { it.queuedAt })
        val offset = (page - 1) * size
        val content = if (offset >= ordered.size) {
            emptyList()
        } else {
            ordered.subList(offset, min(offset + size, ordered.size))
        }
        return pageResponse(content, page, size, ordered.size.toLong())
    }

    private fun withExpectedQueuePositions(
        jobs: List<JobStatusUnifiedResponse>
    ): List<JobStatusUnifiedResponse> {
        val positions = jobs
            .filter { it.status == JobStatus.PENDING }
            .sortedWith(compareBy<JobStatusUnifiedResponse> { it.queuedAt }.thenBy { it.jobId })
            .mapIndexed { index, job -> job.jobId to index + 1 }
            .toMap()
        return jobs.map { job -> job.copy(queuePosition = positions[job.jobId]) }
    }

    private fun <T> pageResponse(
        content: List<T>,
        page: Int,
        size: Int,
        totalElements: Long
    ): JobPageResponse<T> {
        val totalPages = if (totalElements == 0L) {
            0
        } else {
            ((totalElements + size - 1) / size).toInt()
        }
        return JobPageResponse(
            content = content,
            page = page,
            size = size,
            totalElements = totalElements,
            totalPages = totalPages,
            hasNext = page < totalPages,
            hasPrevious = page > 1
        )
    }

    private fun sampleJobs(count: Int): List<JobStatusUnifiedResponse> {
        return (0 until count).map { index ->
            val status = when (index % 3) {
                0 -> JobStatus.PENDING
                1 -> JobStatus.RUNNING
                else -> JobStatus.COMPLETED
            }
            val queuedAt = BASE_QUEUED_AT + index
            JobStatusUnifiedResponse(
                jobId = "job-%02d".format(index),
                jobType = ProblemJobType.entries[index % ProblemJobType.entries.size],
                status = status,
                queuedAt = queuedAt,
                startedAt = if (status == JobStatus.PENDING) null else queuedAt + 1,
                lastHeartbeatAt = queuedAt + 2,
                completedAt = if (status == JobStatus.COMPLETED) queuedAt + 10 else null,
                totalCount = 10,
                processedCount = when (status) {
                    JobStatus.PENDING -> 0
                    JobStatus.RUNNING -> 4
                    else -> 10
                },
                successCount = when (status) {
                    JobStatus.PENDING -> 0
                    JobStatus.RUNNING -> 3
                    else -> 9
                },
                failCount = if (status == JobStatus.PENDING) 0 else 1,
                progressPercentage = when (status) {
                    JobStatus.PENDING -> 0
                    JobStatus.RUNNING -> 40
                    else -> 100
                },
                estimatedRemainingSeconds = if (status == JobStatus.RUNNING) 6 else null,
                queuePosition = null,
                range = JobRange(index + 1, index + 10),
                lastCheckpointId = if (status == JobStatus.PENDING) null else (index + 4).toString(),
                errorCode = null,
                errorMessage = null,
                createdBy = "admin-$index"
            )
        }
    }

    private fun operationalJob(
        jobId: String,
        status: JobStatus,
        queuedAt: Long,
        startedAt: Long? = null,
        completedAt: Long? = null,
        successCount: Int = 0,
        failCount: Int = 0,
        errorCode: String? = null
    ): JobStatusUnifiedResponse {
        val processedCount = successCount + failCount
        return JobStatusUnifiedResponse(
            jobId = jobId,
            jobType = ProblemJobType.COLLECT_METADATA,
            status = status,
            queuedAt = queuedAt,
            startedAt = startedAt,
            lastHeartbeatAt = queuedAt,
            completedAt = completedAt,
            totalCount = 10,
            processedCount = processedCount,
            successCount = successCount,
            failCount = failCount,
            progressPercentage = processedCount * 10,
            estimatedRemainingSeconds = null,
            queuePosition = null,
            range = JobRange(1, 10),
            lastCheckpointId = null,
            errorCode = errorCode,
            errorMessage = null,
            createdBy = "admin"
        )
    }

    private fun seedStaleFixture(validJobs: List<JobStatusUnifiedResponse>) {
        validJobs.forEach(::persist)
        redisTemplate.opsForSet().add(failureKey(validJobs.first().jobId), "kept")

        redisTemplate.opsForZSet().add(
            JOB_INDEX_KEY,
            MALFORMED_JOB_ID,
            (BASE_QUEUED_AT + 20).toDouble()
        )
        redisTemplate.opsForValue().set(
            jobKey(MALFORMED_JOB_ID),
            "{malformed-json",
            Duration.ofDays(1)
        )
        redisTemplate.opsForSet().add(failureKey(MALFORMED_JOB_ID), "1001")

        redisTemplate.opsForZSet().add(
            JOB_INDEX_KEY,
            MISSING_JOB_ID,
            (BASE_QUEUED_AT + 21).toDouble()
        )
        redisTemplate.opsForSet().add(failureKey(MISSING_JOB_ID), "1002")
    }

    private fun assertStaleEntriesRemoved(validFailureJobId: String) {
        assertThat(redisTemplate.opsForZSet().score(JOB_INDEX_KEY, MISSING_JOB_ID)).isNull()
        assertThat(redisTemplate.opsForZSet().score(JOB_INDEX_KEY, MALFORMED_JOB_ID)).isNull()
        assertThat(redisTemplate.hasKey(failureKey(MISSING_JOB_ID))).isFalse()
        assertThat(redisTemplate.hasKey(failureKey(MALFORMED_JOB_ID))).isFalse()
        assertThat(redisTemplate.hasKey(failureKey(validFailureJobId))).isTrue()
        assertThat(redisTemplate.hasKey(jobKey(validFailureJobId))).isTrue()
    }

    private fun persist(job: JobStatusUnifiedResponse) {
        redisTemplate.opsForValue().set(
            jobKey(job.jobId),
            objectMapper.writeValueAsString(job),
            Duration.ofDays(1)
        )
        redisTemplate.opsForZSet().add(
            JOB_INDEX_KEY,
            job.jobId,
            job.queuedAt.toDouble()
        )
    }

    private fun clearJobKeys() {
        listOf(JOB_KEY_PREFIX, JOB_FAILURE_KEY_PREFIX).forEach { prefix ->
            val keys = redisTemplate.keys("$prefix*")
            if (keys.isNotEmpty()) {
                redisTemplate.delete(keys)
            }
        }
        redisTemplate.delete(JOB_INDEX_KEY)
    }

    private fun jobKey(jobId: String): String = "$JOB_KEY_PREFIX$jobId"

    private fun failureKey(jobId: String): String = "$JOB_FAILURE_KEY_PREFIX$jobId"

    private companion object {
        const val JOB_KEY_PREFIX = "problem:job:status:"
        const val JOB_FAILURE_KEY_PREFIX = "problem:job:failures:"
        const val JOB_INDEX_KEY = "problem:job:index"
        const val WARMUP_KEY = "problem:job:list:warmup"
        const val MISSING_JOB_ID = "job-missing"
        const val MALFORMED_JOB_ID = "job-malformed"
        const val BASE_QUEUED_AT = 1_700_000_000L
    }
}

private class JobListRedisCommandRecorder : CommandListener {
    private val enabled = AtomicBoolean(false)
    private val commands = ConcurrentLinkedQueue<ObservedJobListRedisCommand>()

    override fun commandStarted(event: CommandStartedEvent) {
        if (!enabled.get()) {
            return
        }
        commands.add(
            ObservedJobListRedisCommand(
                name = event.command.type.name().lowercase(),
                argumentCount = event.command.args.count()
            )
        )
    }

    fun enableAndReset() {
        commands.clear()
        enabled.set(true)
    }

    fun disableAndReset() {
        enabled.set(false)
        commands.clear()
    }

    fun disableAndSnapshot(): JobListRedisCommandSnapshot {
        enabled.set(false)
        val snapshot = commands.toList()
        commands.clear()
        return JobListRedisCommandSnapshot(snapshot)
    }
}

private data class ObservedJobListRedisCommand(
    val name: String,
    val argumentCount: Int
)

private data class JobListRedisCommandSnapshot(
    val commands: List<ObservedJobListRedisCommand>
) {
    fun count(name: String): Int = commands.count { command -> command.name == name }

    fun counts(): Map<String, Int> {
        return commands
            .groupingBy(ObservedJobListRedisCommand::name)
            .eachCount()
            .toSortedMap()
    }

    fun totalCount(): Int = commands.size

    fun requireSingle(name: String): ObservedJobListRedisCommand {
        val matching = commands.filter { command -> command.name == name }
        check(matching.size == 1) {
            "$name 명령이 정확히 1개가 아닙니다. commands=$commands"
        }
        return matching.single()
    }
}

private data class CapturedJobPage(
    val value: JobPageResponse<JobStatusUnifiedResponse>,
    val commands: JobListRedisCommandSnapshot
)
