package com.didimlog.application.problem.collector

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.domain.Problem
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcProblemResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest
import org.springframework.data.redis.core.StringRedisTemplate

@DataRedisTest(
    properties = [
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=\${TEST_REDIS_PORT:6379}",
        "spring.data.redis.database=13"
    ]
)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("문제 수집 작업 상태 통합 테스트")
class ProblemCollectorJobStateIntegrationTest {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val solvedAcClient: SolvedAcClient = mockk()
    private val problemRepository: ProblemRepository = mockk()
    private val bojCrawler: BojCrawler = mockk()
    private val adminAuditService: AdminAuditService = mockk(relaxed = true)
    private val pacer: ProblemCollectorPacer = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        deleteJobKeys()
    }

    @AfterEach
    fun cleanUp() {
        deleteJobKeys()
    }

    @Test
    @DisplayName("두 인스턴스의 동일 RUNNING 상태 취소 중 한 요청만 성공한다")
    fun `concurrent cancellation has one winner`() {
        val job = sampleRunningJob("job-cancel-race")
        persist(job)
        val readBarrier = CyclicBarrier(2)
        val firstService = createService(
            mapperWithReadBarrier { status ->
                if (status.jobId == job.jobId && status.status == JobStatus.RUNNING) {
                    readBarrier.await(5, TimeUnit.SECONDS)
                }
            }
        )
        val secondService = createService(
            mapperWithReadBarrier { status ->
                if (status.jobId == job.jobId && status.status == JobStatus.RUNNING) {
                    readBarrier.await(5, TimeUnit.SECONDS)
                }
            }
        )
        val start = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = listOf(firstService, secondService).map { service ->
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    try {
                        service.cancelJob(job.jobId, "admin", "127.0.0.1")
                        outcomes.add(SUCCESS)
                    } catch (e: BusinessException) {
                        outcomes.add(e.errorCode.code)
                    }
                }
            }

            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(outcomes.count { it == SUCCESS }).isEqualTo(1)
        assertThat(outcomes.count { it == ErrorCode.JOB_ALREADY_TERMINAL.code }).isEqualTo(1)
        verify(exactly = 1) { adminAuditService.logAction(any(), any(), any(), any()) }

        val stored = readJob(job.jobId)
        assertThat(stored.status).isEqualTo(JobStatus.CANCELLED)
        assertThat(stored.completedAt).isNotNull
        assertStatusStorage(job.jobId)

        val terminalJson = requireNotNull(redisTemplate.opsForValue().get(jobKey(job.jobId)))
        val exception = assertThrows<BusinessException> {
            createService(objectMapper).cancelJob(job.jobId, "admin", "127.0.0.1")
        }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.JOB_ALREADY_TERMINAL)
        assertThat(redisTemplate.opsForValue().get(jobKey(job.jobId))).isEqualTo(terminalJson)
    }

    @Test
    @DisplayName("두 worker가 같은 PENDING 상태를 읽어도 한 worker만 작업을 실행한다")
    fun `concurrent workers have one running claim winner`() {
        var submittedTask: Runnable? = null
        val pendingReadBarrier = CyclicBarrier(2)
        val service = createService(
            mapper = mapperWithReadBarrier { status ->
                if (status.status == JobStatus.PENDING) {
                    pendingReadBarrier.await(5, TimeUnit.SECONDS)
                }
            },
            taskExecutor = Executor { task -> submittedTask = task }
        )
        every { solvedAcClient.fetchProblem(1) } returns SolvedAcProblemResponse(1, "A", 1, emptyList())
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        val capturedTask = requireNotNull(submittedTask)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = List(2) {
                executor.submit(capturedTask)
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(readJob(jobId).status).isEqualTo(JobStatus.COMPLETED)
        assertStatusStorage(jobId)
        verify(exactly = 1) { solvedAcClient.fetchProblem(1) }
        verify(exactly = 1) { problemRepository.upsertMetadata(any<Problem>()) }
    }

    @Test
    @DisplayName("저장된 JSON 형식이 달라도 원문 기준으로 상태를 전이한다")
    fun `formatted stored JSON is compatible with raw CAS`() {
        val job = sampleRunningJob("job-formatted-json")
        val formattedJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(job)
        redisTemplate.opsForValue().set(jobKey(job.jobId), formattedJson, Duration.ofDays(1))
        redisTemplate.opsForZSet().add(JOB_INDEX_KEY, job.jobId, job.queuedAt.toDouble())

        val cancelled = createService(objectMapper).cancelJob(
            job.jobId,
            "admin",
            "127.0.0.1"
        )

        assertThat(cancelled.status).isEqualTo(JobStatus.CANCELLED)
        assertThat(readJob(job.jobId).status).isEqualTo(JobStatus.CANCELLED)
        assertStatusStorage(job.jobId)
    }

    @Test
    @DisplayName("취소 이후 늦게 도착한 진행 상태는 CANCELLED를 덮어쓰지 않는다")
    fun `late progress cannot overwrite cancellation`() {
        var submittedTask: Runnable? = null
        val progressSnapshotRead = CountDownLatch(1)
        val releaseProgressWrite = CountDownLatch(1)
        val runningReadCount = AtomicInteger()
        val workerMapper = mapperWithReadBarrier { status ->
            if (
                Thread.currentThread().name == WORKER_THREAD_NAME &&
                status.status == JobStatus.RUNNING &&
                runningReadCount.incrementAndGet() == 2
            ) {
                progressSnapshotRead.countDown()
                check(releaseProgressWrite.await(5, TimeUnit.SECONDS))
            }
        }
        val workerService = createService(
            mapper = workerMapper,
            taskExecutor = Executor { task -> submittedTask = task }
        )
        val cancellationService = createService(objectMapper)
        every { solvedAcClient.fetchProblem(1) } returns SolvedAcProblemResponse(1, "A", 1, emptyList())
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        val jobId = workerService.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        assertThat(readJob(jobId).status).isEqualTo(JobStatus.PENDING)
        assertStatusStorage(jobId)

        val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, WORKER_THREAD_NAME)
        }
        val workerFuture = workerExecutor.submit(requireNotNull(submittedTask))

        try {
            check(progressSnapshotRead.await(5, TimeUnit.SECONDS))
            val cancelled = cancellationService.cancelJob(jobId, "admin", "127.0.0.1")
            assertThat(cancelled.status).isEqualTo(JobStatus.CANCELLED)
            val cancelledJson = requireNotNull(redisTemplate.opsForValue().get(jobKey(jobId)))

            releaseProgressWrite.countDown()
            workerFuture.get(10, TimeUnit.SECONDS)

            val stored = readJob(jobId)
            assertThat(stored.status).isEqualTo(JobStatus.CANCELLED)
            assertThat(stored.completedAt).isEqualTo(cancelled.completedAt)
            assertThat(redisTemplate.opsForValue().get(jobKey(jobId))).isEqualTo(cancelledJson)
            assertStatusStorage(jobId)

            val exception = assertThrows<BusinessException> {
                cancellationService.cancelJob(jobId, "admin", "127.0.0.1")
            }
            assertThat(exception.errorCode).isEqualTo(ErrorCode.JOB_ALREADY_TERMINAL)
            assertThat(redisTemplate.opsForValue().get(jobKey(jobId))).isEqualTo(cancelledJson)
        } finally {
            releaseProgressWrite.countDown()
            workerExecutor.shutdownNow()
        }
    }

    private fun createService(
        mapper: ObjectMapper,
        taskExecutor: Executor? = null
    ): ProblemCollectorService {
        return ProblemCollectorService(
            solvedAcClient = solvedAcClient,
            problemRepository = problemRepository,
            bojCrawler = bojCrawler,
            redisTemplate = redisTemplate,
            objectMapper = mapper,
            adminAuditService = adminAuditService,
            pacer = pacer,
            taskExecutor = taskExecutor
        )
    }

    private fun mapperWithReadBarrier(
        onRead: (JobStatusUnifiedResponse) -> Unit
    ): ObjectMapper {
        val mapper = mockk<ObjectMapper>()
        every { mapper.writeValueAsString(any()) } answers {
            objectMapper.writeValueAsString(firstArg())
        }
        every {
            mapper.readValue(any<String>(), JobStatusUnifiedResponse::class.java)
        } answers {
            objectMapper.readValue(
                firstArg<String>(),
                JobStatusUnifiedResponse::class.java
            ).also(onRead)
        }
        return mapper
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
        val json = requireNotNull(redisTemplate.opsForValue().get(jobKey(jobId)))
        return objectMapper.readValue(json, JobStatusUnifiedResponse::class.java)
    }

    private fun assertStatusStorage(jobId: String) {
        assertThat(redisTemplate.opsForZSet().score(JOB_INDEX_KEY, jobId)).isNotNull
        assertThat(
            redisTemplate.getExpire(jobKey(jobId), TimeUnit.SECONDS)
        ).isBetween(1L, JOB_TTL_SECONDS)
    }

    private fun sampleRunningJob(jobId: String): JobStatusUnifiedResponse {
        return JobStatusUnifiedResponse(
            jobId = jobId,
            jobType = ProblemJobType.COLLECT_METADATA,
            status = JobStatus.RUNNING,
            queuedAt = 1_700_000_000,
            startedAt = 1_700_000_001,
            lastHeartbeatAt = 1_700_000_002,
            completedAt = null,
            totalCount = 1,
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            progressPercentage = 0,
            estimatedRemainingSeconds = null,
            queuePosition = null,
            range = JobRange(1, 1),
            lastCheckpointId = null,
            errorCode = null,
            errorMessage = null,
            createdBy = "admin"
        )
    }

    private fun deleteJobKeys() {
        val keys = redisTemplate.keys("$JOB_KEY_PREFIX*")
        if (keys.isNotEmpty()) {
            redisTemplate.delete(keys)
        }
        redisTemplate.delete(JOB_INDEX_KEY)
    }

    private fun jobKey(jobId: String): String = "$JOB_KEY_PREFIX$jobId"

    companion object {
        private const val JOB_KEY_PREFIX = "problem:job:status:"
        private const val JOB_INDEX_KEY = "problem:job:index"
        private const val JOB_TTL_SECONDS = 86_400L
        private const val SUCCESS = "OK"
        private const val WORKER_THREAD_NAME = "collector-progress-worker"
    }
}
