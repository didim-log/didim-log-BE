package com.didimlog.application.problem.collector

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.solvedac.SolvedAcClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest
import org.springframework.data.redis.core.StringRedisTemplate

@DataRedisTest(
    properties = [
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=\${TEST_REDIS_PORT:6379}",
        "spring.data.redis.database=11"
    ]
)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("문제 수집 orphan 작업 재시작 복구 통합 테스트")
class ProblemCollectorOrphanRecoveryIntegrationTest {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        deleteJobKeys()
    }

    @AfterEach
    fun cleanUp() {
        deleteJobKeys()
    }

    @Test
    fun `재시작 복구는 진행 중 작업만 실패 처리하고 저장된 진행 상태를 보존한다`() {
        val pending = sampleJob("job-pending", JobStatus.PENDING)
        val runningManifest = ProblemJobTargetManifest(
            version = ProblemJobTargetManifest.CURRENT_VERSION,
            jobId = "job-running",
            jobType = ProblemJobType.COLLECT_METADATA,
            range = JobRange(1, 5)
        )
        val runningManifestJson = objectMapper.writeValueAsString(runningManifest)
        val running = sampleJob("job-running", JobStatus.RUNNING).copy(
            startedAt = BASE_TIME + 1,
            lastHeartbeatAt = BASE_TIME + 3,
            totalCount = 5,
            processedCount = 3,
            successCount = 2,
            failCount = 1,
            progressPercentage = 60,
            range = JobRange(1, 5),
            lastCheckpointId = "3",
            targetManifest = ProblemJobTargetManifestReference(
                schemaVersion = runningManifest.version,
                sha256 = sha256(runningManifestJson)
            )
        )
        val completed = sampleJob("job-completed", JobStatus.COMPLETED).copy(
            startedAt = BASE_TIME + 1,
            completedAt = BASE_TIME + 5,
            processedCount = 1,
            successCount = 1,
            progressPercentage = 100
        )
        val failed = sampleJob("job-failed", JobStatus.FAILED).copy(
            startedAt = BASE_TIME + 1,
            completedAt = BASE_TIME + 5,
            errorCode = "EXISTING_FAILURE",
            errorMessage = "기존 실패"
        )
        val cancelled = sampleJob("job-cancelled", JobStatus.CANCELLED).copy(
            startedAt = BASE_TIME + 1,
            completedAt = BASE_TIME + 5
        )
        listOf(pending, running, completed, failed, cancelled).forEach(::persist)
        listOf(pending.jobId, running.jobId).forEach { jobId ->
            redisTemplate.expire(jobKey(jobId), Duration.ofSeconds(SHORT_TTL_SECONDS))
        }
        redisTemplate.opsForValue().set(
            targetKey(running.jobId),
            runningManifestJson,
            Duration.ofSeconds(SHORT_TTL_SECONDS)
        )
        redisTemplate.opsForSet().add(failureKey(running.jobId), "2")
        redisTemplate.expire(
            failureKey(running.jobId),
            Duration.ofSeconds(SHORT_TTL_SECONDS)
        )
        val terminalRaw = listOf(completed, failed, cancelled).associate { job ->
            job.jobId to requireNotNull(redisTemplate.opsForValue().get(jobKey(job.jobId)))
        }
        val runningManifestRaw = requireNotNull(
            redisTemplate.opsForValue().get(targetKey(running.jobId))
        )

        val recoveredCount = createService().failOrphanedJobsDuringStartup()

        assertThat(recoveredCount).isEqualTo(2)
        assertRestartFailure(readJob(pending.jobId), pending)
        assertRestartFailure(readJob(running.jobId), running)
        assertThat(redisTemplate.opsForSet().members(failureKey(running.jobId)))
            .containsExactly("2")
        assertThat(redisTemplate.opsForValue().get(targetKey(running.jobId)))
            .isEqualTo(runningManifestRaw)

        terminalRaw.forEach { (jobId, rawJson) ->
            assertThat(redisTemplate.opsForValue().get(jobKey(jobId))).isEqualTo(rawJson)
        }
        listOf(pending.jobId, running.jobId).forEach { jobId ->
            assertThat(redisTemplate.getExpire(jobKey(jobId), TimeUnit.SECONDS))
                .isBetween(JOB_TTL_SECONDS - TTL_ASSERTION_TOLERANCE_SECONDS, JOB_TTL_SECONDS)
            assertThat(redisTemplate.opsForZSet().score(JOB_INDEX_KEY, jobId)).isNotNull
        }
        val statusTtl = redisTemplate.getExpire(jobKey(running.jobId), TimeUnit.SECONDS)
        val failureTtl = redisTemplate.getExpire(failureKey(running.jobId), TimeUnit.SECONDS)
        val targetTtl = redisTemplate.getExpire(targetKey(running.jobId), TimeUnit.SECONDS)
        assertThat(failureTtl)
            .isBetween(JOB_TTL_SECONDS - TTL_ASSERTION_TOLERANCE_SECONDS, JOB_TTL_SECONDS)
        assertThat(targetTtl)
            .isBetween(JOB_TTL_SECONDS - TTL_ASSERTION_TOLERANCE_SECONDS, JOB_TTL_SECONDS)
        assertThat(statusTtl - failureTtl).isBetween(-1L, 1L)
        assertThat(statusTtl - targetTtl).isBetween(-1L, 1L)
    }

    @Test
    fun `두 서비스가 동시에 복구해도 각 orphan 작업은 한 번만 실패 처리한다`() {
        val orphanJobs = (0 until ORPHAN_COUNT).map { index ->
            sampleJob(
                jobId = "job-concurrent-$index",
                status = if (index % 2 == 0) JobStatus.PENDING else JobStatus.RUNNING
            ).copy(
                startedAt = if (index % 2 == 0) null else BASE_TIME + 1
            )
        }
        orphanJobs.forEach(::persist)
        val firstReadsReady = CountDownLatch(2)
        val firstService = createService(
            mapper = FirstReadBarrierObjectMapper(firstReadsReady)
        )
        val secondService = createService(
            mapper = FirstReadBarrierObjectMapper(firstReadsReady)
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val recoveredCounts = try {
            val futures = listOf(firstService, secondService).map { service ->
                executor.submit<Int> {
                    check(start.await(5, TimeUnit.SECONDS))
                    service.failOrphanedJobsDuringStartup()
                }
            }
            start.countDown()
            futures.map { future -> future.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(recoveredCounts.sum()).isEqualTo(ORPHAN_COUNT)
        orphanJobs.forEach { original ->
            assertRestartFailure(readJob(original.jobId), original)
        }
    }

    @Test
    fun `손상된 상태 JSON이 있어도 다른 orphan 작업을 계속 복구한다`() {
        val valid = sampleJob("job-valid-orphan", JobStatus.RUNNING).copy(
            startedAt = BASE_TIME + 1,
            processedCount = 1,
            successCount = 1,
            progressPercentage = 20,
            lastCheckpointId = "1"
        )
        persist(valid)
        redisTemplate.opsForValue().set(
            jobKey(MALFORMED_JOB_ID),
            "{malformed-json",
            Duration.ofDays(1)
        )
        redisTemplate.opsForZSet().add(
            JOB_INDEX_KEY,
            MALFORMED_JOB_ID,
            (BASE_TIME + 100).toDouble()
        )

        val recoveredCount = createService().failOrphanedJobsDuringStartup()

        assertThat(recoveredCount).isEqualTo(1)
        assertRestartFailure(readJob(valid.jobId), valid)
    }

    @Test
    fun `작업 생성 gate가 열린 뒤에는 재시작 복구를 다시 실행하지 않는다`() {
        val running = sampleJob("job-ready-state", JobStatus.RUNNING).copy(
            startedAt = BASE_TIME + 1
        )
        persist(running)
        val originalJson = requireNotNull(redisTemplate.opsForValue().get(jobKey(running.jobId)))
        val service = createService(
            recoveryState = ProblemCollectorRecoveryState(ProblemCollectorRecoveryProperties())
        )

        val exception = assertThrows<IllegalStateException> {
            service.failOrphanedJobsDuringStartup()
        }

        assertThat(exception.message).contains("시작 단계에서만")
        assertThat(redisTemplate.opsForValue().get(jobKey(running.jobId))).isEqualTo(originalJson)
    }

    @Test
    fun `작업 상태 키 타입이 잘못되면 시작을 실패시키고 생성 gate를 열지 않는다`() {
        redisTemplate.opsForList().rightPush(jobKey(WRONG_TYPE_JOB_ID), "invalid")
        redisTemplate.opsForZSet().add(
            JOB_INDEX_KEY,
            WRONG_TYPE_JOB_ID,
            BASE_TIME.toDouble()
        )
        val properties = ProblemCollectorRecoveryProperties(
            failOrphanedJobsOnStartup = true
        )
        val recoveryState = ProblemCollectorRecoveryState(properties)
        val service = createService(recoveryState = recoveryState)
        val runner = ProblemCollectorRecoveryRunner(
            properties = properties,
            recoveryState = recoveryState,
            problemCollectorService = service
        )

        assertThrows<RuntimeException> {
            runner.run(mockk<ApplicationArguments>())
        }

        assertThat(recoveryState.isReady()).isFalse()
    }

    private fun createService(
        mapper: ObjectMapper = objectMapper,
        recoveryState: ProblemCollectorRecoveryState = ProblemCollectorRecoveryState(
            ProblemCollectorRecoveryProperties(failOrphanedJobsOnStartup = true)
        )
    ): ProblemCollectorService {
        return ProblemCollectorService(
            solvedAcClient = mockk<SolvedAcClient>(),
            problemRepository = mockk<ProblemRepository>(),
            bojCrawler = mockk<BojCrawler>(),
            redisTemplate = redisTemplate,
            objectMapper = mapper,
            adminAuditService = mockk<AdminAuditService>(relaxed = true),
            pacer = mockk<ProblemCollectorPacer>(relaxed = true),
            recoveryState = recoveryState,
            taskExecutor = null
        )
    }

    private fun assertRestartFailure(
        actual: JobStatusUnifiedResponse,
        original: JobStatusUnifiedResponse
    ) {
        assertThat(actual.status).isEqualTo(JobStatus.FAILED)
        assertThat(actual.errorCode).isEqualTo(ErrorCode.WORKER_UNAVAILABLE.code)
        assertThat(actual.errorMessage).contains("서버 재시작")
        assertThat(actual.completedAt).isNotNull
        assertThat(actual.lastHeartbeatAt).isNotNull
        assertThat(actual.totalCount).isEqualTo(original.totalCount)
        assertThat(actual.processedCount).isEqualTo(original.processedCount)
        assertThat(actual.successCount).isEqualTo(original.successCount)
        assertThat(actual.failCount).isEqualTo(original.failCount)
        assertThat(actual.progressPercentage).isEqualTo(original.progressPercentage)
        assertThat(actual.lastCheckpointId).isEqualTo(original.lastCheckpointId)
        assertThat(actual.range).isEqualTo(original.range)
        assertThat(actual.targetManifest).isEqualTo(original.targetManifest)
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

    private fun readJob(jobId: String): JobStatusUnifiedResponse {
        val rawJson = requireNotNull(redisTemplate.opsForValue().get(jobKey(jobId)))
        return objectMapper.readValue(rawJson, JobStatusUnifiedResponse::class.java)
    }

    private fun sampleJob(
        jobId: String,
        status: JobStatus
    ): JobStatusUnifiedResponse {
        return JobStatusUnifiedResponse(
            jobId = jobId,
            jobType = ProblemJobType.COLLECT_METADATA,
            status = status,
            queuedAt = BASE_TIME,
            startedAt = null,
            lastHeartbeatAt = BASE_TIME,
            completedAt = null,
            totalCount = 5,
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            progressPercentage = 0,
            estimatedRemainingSeconds = null,
            queuePosition = null,
            range = JobRange(1, 5),
            lastCheckpointId = null,
            errorCode = null,
            errorMessage = null,
            createdBy = "admin"
        )
    }

    private fun deleteJobKeys() {
        listOf(JOB_KEY_PREFIX, JOB_FAILURE_KEY_PREFIX, JOB_TARGET_KEY_PREFIX).forEach { prefix ->
            val keys = redisTemplate.keys("$prefix*")
            if (keys.isNotEmpty()) {
                redisTemplate.delete(keys)
            }
        }
        redisTemplate.delete(JOB_INDEX_KEY)
    }

    private fun jobKey(jobId: String): String = "$JOB_KEY_PREFIX$jobId"

    private fun failureKey(jobId: String): String = "$JOB_FAILURE_KEY_PREFIX$jobId"

    private fun targetKey(jobId: String): String = "$JOB_TARGET_KEY_PREFIX$jobId"

    private fun sha256(value: String): String {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
        )
    }

    private class FirstReadBarrierObjectMapper(
        private val firstReadsReady: CountDownLatch
    ) : ObjectMapper() {
        private val firstJobRead = AtomicBoolean(true)

        init {
            registerKotlinModule()
        }

        override fun <T : Any?> readValue(content: String, valueType: Class<T>): T {
            val value = super.readValue(content, valueType)
            if (
                valueType == JobStatusUnifiedResponse::class.java &&
                firstJobRead.compareAndSet(true, false)
            ) {
                firstReadsReady.countDown()
                check(firstReadsReady.await(5, TimeUnit.SECONDS)) {
                    "두 복구 서비스가 같은 초기 작업 상태를 읽지 못했습니다."
                }
            }
            return value
        }
    }

    private companion object {
        const val JOB_KEY_PREFIX = "problem:job:status:"
        const val JOB_FAILURE_KEY_PREFIX = "problem:job:failures:"
        const val JOB_TARGET_KEY_PREFIX = "problem:job:targets:"
        const val JOB_INDEX_KEY = "problem:job:index"
        const val MALFORMED_JOB_ID = "job-malformed"
        const val WRONG_TYPE_JOB_ID = "job-wrong-type"
        const val ORPHAN_COUNT = 12
        const val JOB_TTL_SECONDS = 86_400L
        const val SHORT_TTL_SECONDS = 60L
        const val TTL_ASSERTION_TOLERANCE_SECONDS = 5L
        const val BASE_TIME = 1_700_000_000L
    }
}
