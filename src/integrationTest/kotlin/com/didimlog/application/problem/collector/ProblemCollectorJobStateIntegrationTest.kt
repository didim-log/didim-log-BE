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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections
import java.util.HexFormat
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
        every { solvedAcClient.fetchProblem(1) } throws IllegalStateException("temporary failure")

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
            assertThat(stored.processedCount).isZero()
            assertThat(stored.failCount).isZero()
            assertThat(stored.lastCheckpointId).isNull()
            assertThat(redisTemplate.opsForSet().members(failureKey(jobId))).isEmpty()
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

    @Test
    @DisplayName("재시작 복구가 실행 중 작업을 실패 처리하면 다음 문제를 실행하지 않는다")
    fun `restart recovery stops in-flight worker before next metadata item`() {
        var submittedTask: Runnable? = null
        val firstCallStarted = CountDownLatch(1)
        val releaseFirstCall = CountDownLatch(1)
        val workerService = createService(
            mapper = objectMapper,
            taskExecutor = Executor { task -> submittedTask = task }
        )
        val recoveryService = createService(
            mapper = objectMapper,
            recoveryState = ProblemCollectorRecoveryState(
                ProblemCollectorRecoveryProperties(failOrphanedJobsOnStartup = true)
            )
        )
        every { solvedAcClient.fetchProblem(1) } answers {
            firstCallStarted.countDown()
            check(releaseFirstCall.await(5, TimeUnit.SECONDS))
            SolvedAcProblemResponse(1, "P1", 1, emptyList())
        }
        every { solvedAcClient.fetchProblem(2) } returns
            SolvedAcProblemResponse(2, "P2", 1, emptyList())
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        val jobId = workerService.collectMetadataAsync(1, 2, "admin", "127.0.0.1")
        val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, WORKER_THREAD_NAME)
        }
        val workerFuture = workerExecutor.submit(requireNotNull(submittedTask))

        try {
            check(firstCallStarted.await(5, TimeUnit.SECONDS))
            assertThat(readJob(jobId).status).isEqualTo(JobStatus.RUNNING)

            assertThat(recoveryService.failOrphanedJobsDuringStartup()).isEqualTo(1)
            val recoveredJson = requireNotNull(redisTemplate.opsForValue().get(jobKey(jobId)))
            val recovered = readJob(jobId)
            assertThat(recovered.status).isEqualTo(JobStatus.FAILED)
            assertThat(recovered.errorCode).isEqualTo(ErrorCode.WORKER_UNAVAILABLE.code)
            assertThat(recovered.errorMessage).contains("서버 재시작")

            releaseFirstCall.countDown()
            workerFuture.get(10, TimeUnit.SECONDS)

            val stored = readJob(jobId)
            assertThat(stored.status).isEqualTo(JobStatus.FAILED)
            assertThat(stored.processedCount).isZero()
            assertThat(stored.successCount).isZero()
            assertThat(stored.failCount).isZero()
            assertThat(stored.lastCheckpointId).isNull()
            assertThat(redisTemplate.opsForValue().get(jobKey(jobId))).isEqualTo(recoveredJson)
            assertStatusStorage(jobId)
            verify(exactly = 1) { solvedAcClient.fetchProblem(1) }
            verify(exactly = 0) { solvedAcClient.fetchProblem(2) }
            verify(exactly = 1) { problemRepository.upsertMetadata(any<Problem>()) }
        } finally {
            releaseFirstCall.countDown()
            workerExecutor.shutdownNow()
        }
    }

    @Test
    @DisplayName("완료 작업의 실패 원장은 TTL과 함께 저장되고 실패 문제만 재시도한다")
    fun `completed job retries only recorded failed metadata item`() {
        var problemTwoAttempts = 0
        every { solvedAcClient.fetchProblem(any()) } answers {
            val problemId = firstArg<Int>()
            if (problemId == 2 && problemTwoAttempts++ == 0) {
                throw IllegalStateException("temporary failure")
            }
            SolvedAcProblemResponse(problemId, "P$problemId", 1, emptyList())
        }
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs
        val service = createService(objectMapper)

        val sourceJobId = service.collectMetadataAsync(1, 5, "admin", "127.0.0.1")
        val sourceJob = readJob(sourceJobId)

        assertThat(sourceJob.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(sourceJob.processedCount).isEqualTo(5)
        assertThat(sourceJob.successCount).isEqualTo(4)
        assertThat(sourceJob.failCount).isEqualTo(1)
        assertThat(sourceJob.lastCheckpointId).isEqualTo("5")
        assertThat(redisTemplate.opsForSet().members(failureKey(sourceJobId)))
            .containsExactly("2")
        val statusTtl = redisTemplate.getExpire(jobKey(sourceJobId), TimeUnit.SECONDS)
        val failureTtl = redisTemplate.getExpire(failureKey(sourceJobId), TimeUnit.SECONDS)
        assertThat(statusTtl).isBetween(1L, JOB_TTL_SECONDS)
        assertThat(failureTtl).isBetween(1L, JOB_TTL_SECONDS)
        assertThat(statusTtl - failureTtl).isBetween(-1L, 1L)

        val retryJob = service.retryJob(sourceJobId, "admin", "127.0.0.1")

        assertThat(retryJob.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(retryJob.totalCount).isEqualTo(1)
        assertThat(retryJob.range).isEqualTo(JobRange(2, 2))
        assertThat(retryJob.successCount).isEqualTo(1)
        assertThat(retryJob.failCount).isZero()
        assertThat(redisTemplate.opsForSet().members(failureKey(retryJob.jobId))).isEmpty()
        verify(exactly = 2) { solvedAcClient.fetchProblem(2) }
        listOf(1, 3, 4, 5).forEach { problemId ->
            verify(exactly = 1) { solvedAcClient.fetchProblem(problemId) }
        }
    }

    @Test
    @DisplayName("구버전 부분 실패 작업은 실패 원장이 없으면 원본 범위를 다시 실행한다")
    fun `legacy job without failure ledger retries original range`() {
        val job = sampleRunningJob("job-legacy-failure-ledger").copy(
            status = JobStatus.COMPLETED,
            completedAt = 1_700_000_003,
            totalCount = 3,
            processedCount = 3,
            successCount = 2,
            failCount = 1,
            progressPercentage = 100,
            range = JobRange(1, 3),
            lastCheckpointId = "3"
        )
        persist(job)
        every { solvedAcClient.fetchProblem(any()) } answers {
            val problemId = firstArg<Int>()
            SolvedAcProblemResponse(problemId, "P$problemId", 1, emptyList())
        }
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs
        val service = createService(objectMapper)

        val retryJob = service.retryJob(job.jobId, "admin", "127.0.0.1")

        assertThat(retryJob.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(retryJob.totalCount).isEqualTo(3)
        assertThat(retryJob.range).isEqualTo(JobRange(1, 3))
        listOf(1, 2, 3).forEach { problemId ->
            verify(exactly = 1) { solvedAcClient.fetchProblem(problemId) }
        }
    }

    @Test
    @DisplayName("존재하는 실패 원장의 크기가 실패 수와 다르면 재시도를 거부한다")
    fun `retry rejects mismatched existing failure ledger`() {
        val job = sampleRunningJob("job-failure-ledger-mismatch").copy(
            status = JobStatus.COMPLETED,
            completedAt = 1_700_000_003,
            totalCount = 2,
            processedCount = 2,
            failCount = 2,
            progressPercentage = 100,
            range = JobRange(1, 2),
            lastCheckpointId = "2"
        )
        persist(job)
        redisTemplate.opsForSet().add(failureKey(job.jobId), "1")
        redisTemplate.expire(failureKey(job.jobId), Duration.ofDays(1))
        val service = createService(objectMapper)
        val indexSizeBeforeRetry = redisTemplate.opsForZSet().zCard(JOB_INDEX_KEY)

        val exception = assertThrows<BusinessException> {
            service.retryJob(job.jobId, "admin", "127.0.0.1")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        assertThat(redisTemplate.opsForZSet().zCard(JOB_INDEX_KEY)).isEqualTo(indexSizeBeforeRetry)
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }
    }

    @Test
    @DisplayName("실패 원장 키 타입이 잘못돼도 실패 진행 상태만 부분 저장하지 않는다")
    fun `invalid failure ledger type does not partially update failed progress`() {
        var submittedTask: Runnable? = null
        val service = createService(
            mapper = objectMapper,
            taskExecutor = Executor { task -> submittedTask = task }
        )
        every { solvedAcClient.fetchProblem(1) } throws IllegalStateException("temporary failure")

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        redisTemplate.opsForValue().set(failureKey(jobId), "invalid", Duration.ofDays(1))

        requireNotNull(submittedTask).run()

        val stored = readJob(jobId)
        assertThat(stored.status).isEqualTo(JobStatus.FAILED)
        assertThat(stored.processedCount).isZero()
        assertThat(stored.failCount).isZero()
        assertThat(stored.lastCheckpointId).isNull()
        assertThat(redisTemplate.opsForValue().get(failureKey(jobId))).isEqualTo("invalid")
        val indexSizeBeforeRetry = redisTemplate.opsForZSet().zCard(JOB_INDEX_KEY)
        val retryException = assertThrows<BusinessException> {
            service.retryJob(jobId, "admin", "127.0.0.1")
        }
        assertThat(retryException.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        assertThat(redisTemplate.opsForZSet().zCard(JOB_INDEX_KEY)).isEqualTo(indexSizeBeforeRetry)
        verify(exactly = 1) { adminAuditService.logAction(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("취소 작업은 실패 원장과 체크포인트 이후 문제를 합쳐 재시도한다")
    fun `cancelled job retries failed item and checkpoint tail`() {
        val job = sampleRunningJob("job-cancelled-retry").copy(
            status = JobStatus.CANCELLED,
            completedAt = 1_700_000_003,
            totalCount = 5,
            processedCount = 3,
            successCount = 2,
            failCount = 1,
            progressPercentage = 60,
            range = JobRange(1, 5),
            lastCheckpointId = "3"
        )
        persist(job)
        redisTemplate.opsForSet().add(failureKey(job.jobId), "2")
        redisTemplate.expire(failureKey(job.jobId), Duration.ofDays(1))
        every { solvedAcClient.fetchProblem(any()) } answers {
            val problemId = firstArg<Int>()
            SolvedAcProblemResponse(problemId, "P$problemId", 1, emptyList())
        }
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs
        val service = createService(objectMapper)

        val retryJob = service.retryJob(job.jobId, "admin", "127.0.0.1")

        assertThat(retryJob.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(retryJob.totalCount).isEqualTo(3)
        assertThat(retryJob.range).isEqualTo(JobRange(2, 5))
        assertThat(retryJob.successCount).isEqualTo(3)
        assertThat(retryJob.lastCheckpointId).isEqualTo("5")
        val retryManifest = objectMapper.readValue(
            requireNotNull(redisTemplate.opsForValue().get(targetKey(retryJob.jobId))),
            ProblemJobTargetManifest::class.java
        )
        assertThat(retryManifest.explicitIds).containsExactly("2")
        assertThat(retryManifest.range).isEqualTo(JobRange(4, 5))
        listOf(2, 4, 5).forEach { problemId ->
            verify(exactly = 1) { solvedAcClient.fetchProblem(problemId) }
        }
        listOf(1, 3).forEach { problemId ->
            verify(exactly = 0) { solvedAcClient.fetchProblem(problemId) }
        }
    }

    @Test
    @DisplayName("떨어진 대상 manifest 작업은 처리 위치 뒤의 실제 대상만 재시도한다")
    fun `sparse manifest retry does not expand public range`() {
        val sourceJobId = "job-sparse-manifest"
        val manifest = ProblemJobTargetManifest(
            version = ProblemJobTargetManifest.CURRENT_VERSION,
            jobId = sourceJobId,
            jobType = ProblemJobType.COLLECT_METADATA,
            explicitIds = listOf("2", "5")
        )
        val manifestJson = objectMapper.writeValueAsString(manifest)
        val source = sampleRunningJob(sourceJobId).copy(
            status = JobStatus.CANCELLED,
            completedAt = 1_700_000_005,
            totalCount = 2,
            processedCount = 1,
            successCount = 1,
            failCount = 0,
            progressPercentage = 50,
            range = JobRange(2, 5),
            lastCheckpointId = "2",
            targetManifest = ProblemJobTargetManifestReference(
                schemaVersion = manifest.version,
                sha256 = sha256(manifestJson)
            )
        )
        persist(source)
        redisTemplate.opsForValue().set(
            targetKey(sourceJobId),
            manifestJson,
            Duration.ofDays(1)
        )
        every { solvedAcClient.fetchProblem(5) } returns
            SolvedAcProblemResponse(5, "P5", 1, emptyList())
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs
        val service = createService(objectMapper)

        val retryJob = service.retryJob(sourceJobId, "admin", "127.0.0.1")

        assertThat(retryJob.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(retryJob.totalCount).isEqualTo(1)
        assertThat(retryJob.range).isEqualTo(JobRange(5, 5))
        assertThat(retryJob.successCount).isEqualTo(1)
        val retryManifestJson = requireNotNull(
            redisTemplate.opsForValue().get(targetKey(retryJob.jobId))
        )
        val retryManifest = objectMapper.readValue(
            retryManifestJson,
            ProblemJobTargetManifest::class.java
        )
        assertThat(retryManifest.explicitIds).containsExactly("5")
        assertThat(retryManifest.range).isNull()
        verify(exactly = 0) { solvedAcClient.fetchProblem(2) }
        verify(exactly = 0) { solvedAcClient.fetchProblem(3) }
        verify(exactly = 0) { solvedAcClient.fetchProblem(4) }
        verify(exactly = 1) { solvedAcClient.fetchProblem(5) }
    }

    @Test
    @DisplayName("혼합 manifest 재시도는 실패 prefix와 범위의 미처리 suffix만 실행한다")
    fun `hybrid manifest retry preserves explicit and range boundary`() {
        val sourceJobId = "job-hybrid-manifest"
        val manifest = ProblemJobTargetManifest(
            version = ProblemJobTargetManifest.CURRENT_VERSION,
            jobId = sourceJobId,
            jobType = ProblemJobType.COLLECT_METADATA,
            explicitIds = listOf("2"),
            range = JobRange(4, 5)
        )
        val manifestJson = objectMapper.writeValueAsString(manifest)
        val source = sampleRunningJob(sourceJobId).copy(
            status = JobStatus.CANCELLED,
            completedAt = 1_700_000_005,
            totalCount = 3,
            processedCount = 2,
            successCount = 1,
            failCount = 1,
            progressPercentage = 66,
            range = JobRange(2, 5),
            lastCheckpointId = "4",
            targetManifest = ProblemJobTargetManifestReference(
                schemaVersion = manifest.version,
                sha256 = sha256(manifestJson)
            )
        )
        persist(source)
        redisTemplate.opsForValue().set(
            targetKey(sourceJobId),
            manifestJson,
            Duration.ofDays(1)
        )
        redisTemplate.opsForSet().add(failureKey(sourceJobId), "2")
        every { solvedAcClient.fetchProblem(2) } returns
            SolvedAcProblemResponse(2, "P2", 1, emptyList())
        every { solvedAcClient.fetchProblem(5) } returns
            SolvedAcProblemResponse(5, "P5", 1, emptyList())
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs
        val service = createService(objectMapper)

        val retryJob = service.retryJob(sourceJobId, "admin", "127.0.0.1")

        assertThat(retryJob.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(retryJob.totalCount).isEqualTo(2)
        val retryManifest = objectMapper.readValue(
            requireNotNull(redisTemplate.opsForValue().get(targetKey(retryJob.jobId))),
            ProblemJobTargetManifest::class.java
        )
        assertThat(retryManifest.explicitIds).containsExactly("2")
        assertThat(retryManifest.range).isEqualTo(JobRange(5, 5))
        verify(exactly = 1) { solvedAcClient.fetchProblem(2) }
        verify(exactly = 0) { solvedAcClient.fetchProblem(4) }
        verify(exactly = 1) { solvedAcClient.fetchProblem(5) }
    }

    private fun createService(
        mapper: ObjectMapper,
        taskExecutor: Executor? = null,
        recoveryState: ProblemCollectorRecoveryState =
            ProblemCollectorRecoveryState(ProblemCollectorRecoveryProperties())
    ): ProblemCollectorService {
        return ProblemCollectorService(
            solvedAcClient = solvedAcClient,
            problemRepository = problemRepository,
            bojCrawler = bojCrawler,
            redisTemplate = redisTemplate,
            objectMapper = mapper,
            adminAuditService = adminAuditService,
            pacer = pacer,
            recoveryState = recoveryState,
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

    companion object {
        private const val JOB_KEY_PREFIX = "problem:job:status:"
        private const val JOB_FAILURE_KEY_PREFIX = "problem:job:failures:"
        private const val JOB_TARGET_KEY_PREFIX = "problem:job:targets:"
        private const val JOB_INDEX_KEY = "problem:job:index"
        private const val JOB_TTL_SECONDS = 86_400L
        private const val SUCCESS = "OK"
        private const val WORKER_THREAD_NAME = "collector-progress-worker"
    }
}
