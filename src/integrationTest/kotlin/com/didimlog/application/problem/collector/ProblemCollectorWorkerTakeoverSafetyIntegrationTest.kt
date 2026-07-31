package com.didimlog.application.problem.collector

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.domain.Problem
import com.didimlog.domain.repository.ProblemRepository
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
import java.util.HexFormat
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest
import org.springframework.data.redis.core.StringRedisTemplate

@DataRedisTest(
    properties = [
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=\${TEST_REDIS_PORT:6379}",
        "spring.data.redis.database=\${TEST_REDIS_WORKER_TAKEOVER_SAFETY_DATABASE:8}"
    ]
)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("문제 수집 worker 자동 인계 안전성 통합 테스트")
class ProblemCollectorWorkerTakeoverSafetyIntegrationTest {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private lateinit var solvedAcClient: SolvedAcClient
    private lateinit var problemRepository: ProblemRepository
    private lateinit var bojCrawler: BojCrawler
    private lateinit var adminAuditService: AdminAuditService
    private lateinit var pacer: ProblemCollectorPacer
    private val heartbeatExecutors = mutableListOf<ScheduledExecutorService>()

    @BeforeEach
    fun setUp() {
        deleteJobKeys()
        solvedAcClient = mockk()
        problemRepository = mockk()
        bojCrawler = mockk()
        adminAuditService = mockk(relaxed = true)
        pacer = mockk(relaxed = true)
    }

    @AfterEach
    fun cleanUp() {
        heartbeatExecutors.forEach { it.shutdownNow() }
        heartbeatExecutors.clear()
        deleteJobKeys()
    }

    @Test
    @DisplayName("두 서비스가 같은 PENDING 작업을 실행해도 Redis claim 승자만 처리한다")
    fun `two services execute only one copy of the same pending job`() {
        val jobId = "job-pending-race"
        persistPendingMetadataJob(jobId)
        val claimBarrier = CyclicBarrier(2)
        val firstExecutor = CaptureOnlyExecutor()
        val secondExecutor = CaptureOnlyExecutor()
        val firstService = createService(
            ownerId = "owner-race-a",
            taskExecutor = firstExecutor,
            mapper = mapperWithClaimBarrier(claimBarrier, JobStatus.PENDING)
        )
        val secondService = createService(
            ownerId = "owner-race-b",
            taskExecutor = secondExecutor,
            mapper = mapperWithClaimBarrier(claimBarrier, JobStatus.PENDING)
        )
        every { solvedAcClient.fetchProblem(1) } returns problemResponse(1)
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        assertThat(firstService.submitRecoverableJobs()).isEqualTo(1)
        assertThat(secondService.submitRecoverableJobs()).isEqualTo(1)
        assertThat(firstExecutor.taskCount).isEqualTo(1)
        assertThat(secondExecutor.taskCount).isEqualTo(1)
        assertThat(readJob(jobId).status).isEqualTo(JobStatus.PENDING)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()

        val threadNumber = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2) { task ->
            Thread(
                task,
                "$CLAIM_THREAD_PREFIX${threadNumber.incrementAndGet()}"
            )
        }
        try {
            val futures = listOf(
                pool.submit(firstExecutor.singleTask()),
                pool.submit(secondExecutor.singleTask())
            )
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val completed = readJob(jobId)
        assertThat(completed.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(completed.processedCount).isEqualTo(1)
        assertThat(completed.successCount).isEqualTo(1)
        assertThat(completed.workerAttempt?.attemptNumber).isEqualTo(1)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        assertThat(redisTemplate.opsForZSet().range(JOB_INDEX_KEY, 0, -1))
            .containsExactly(jobId)
        verify(exactly = 1) { solvedAcClient.fetchProblem(1) }
        verify(exactly = 1) { problemRepository.upsertMetadata(any<Problem>()) }
    }

    @Test
    @DisplayName("두 서비스가 만료된 RUNNING 작업을 인계해도 suffix는 한 번만 처리한다")
    fun `two services take over one expired running job`() {
        val jobId = "job-running-race"
        val oldAttempt = ProblemJobWorkerAttempt(
            ownerId = "owner-old",
            attemptId = "attempt-old",
            attemptNumber = 6
        )
        persistRunningMetadataJob(
            jobId = jobId,
            manifest = metadataManifest(jobId, JobRange(1, 2)),
            attempt = oldAttempt,
            totalCount = 2,
            processedCount = 1,
            successCount = 1,
            failCount = 0,
            checkpointId = "1"
        )
        val claimBarrier = CyclicBarrier(2)
        val firstExecutor = CaptureOnlyExecutor()
        val secondExecutor = CaptureOnlyExecutor()
        val firstService = createService(
            ownerId = "owner-running-a",
            taskExecutor = firstExecutor,
            mapper = mapperWithClaimBarrier(claimBarrier, JobStatus.RUNNING)
        )
        val secondService = createService(
            ownerId = "owner-running-b",
            taskExecutor = secondExecutor,
            mapper = mapperWithClaimBarrier(claimBarrier, JobStatus.RUNNING)
        )
        every { solvedAcClient.fetchProblem(2) } returns problemResponse(2)
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        assertThat(firstService.submitRecoverableJobs()).isEqualTo(1)
        assertThat(secondService.submitRecoverableJobs()).isEqualTo(1)

        val threadNumber = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2) { task ->
            Thread(
                task,
                "$CLAIM_THREAD_PREFIX${threadNumber.incrementAndGet()}"
            )
        }
        try {
            val futures = listOf(
                pool.submit(firstExecutor.singleTask()),
                pool.submit(secondExecutor.singleTask())
            )
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val completed = readJob(jobId)
        assertThat(completed.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(completed.processedCount).isEqualTo(2)
        assertThat(completed.successCount).isEqualTo(2)
        assertThat(completed.workerAttempt?.attemptNumber)
            .isEqualTo(oldAttempt.attemptNumber + 1)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verify(exactly = 0) { solvedAcClient.fetchProblem(1) }
        verify(exactly = 1) { solvedAcClient.fetchProblem(2) }
        verify(exactly = 1) { problemRepository.upsertMetadata(any<Problem>()) }
    }

    @Test
    @DisplayName("같은 서비스의 반복 scan은 대기 중인 작업을 한 번만 제출한다")
    fun `repeated scans in one service keep one queued task`() {
        val jobId = "job-local-queue"
        persistPendingMetadataJob(jobId)
        val executor = CaptureOnlyExecutor()
        val service = createService(
            ownerId = "owner-local-queue",
            taskExecutor = executor
        )
        every { solvedAcClient.fetchProblem(1) } returns problemResponse(1)
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        val firstSubmitted = service.submitRecoverableJobs()
        val secondSubmitted = service.submitRecoverableJobs()

        assertThat(firstSubmitted).isEqualTo(1)
        assertThat(secondSubmitted).isZero()
        assertThat(executor.taskCount).isEqualTo(1)
        assertThat(readJob(jobId).status).isEqualTo(JobStatus.PENDING)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()

        executor.singleTask().run()

        assertThat(readJob(jobId).status).isEqualTo(JobStatus.COMPLETED)
        verify(exactly = 1) { solvedAcClient.fetchProblem(1) }
    }

    @Test
    @DisplayName("실행기 거부는 상태를 선점하지 않고 다음 scan에서 다시 실행한다")
    fun `rejected submission remains retriable without state or lease changes`() {
        val jobId = "job-rejected-scan"
        persistPendingMetadataJob(jobId)
        val statusRaw = requireNotNull(
            redisTemplate.opsForValue().get(jobKey(jobId))
        )
        val manifestRaw = requireNotNull(
            redisTemplate.opsForValue().get(targetKey(jobId))
        )
        val executor = RejectThenRunExecutor()
        val service = createService(
            ownerId = "owner-retry-submit",
            taskExecutor = executor
        )
        every { solvedAcClient.fetchProblem(1) } returns problemResponse(1)
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        val rejectedCount = service.submitRecoverableJobs()

        assertThat(rejectedCount).isZero()
        assertThat(redisTemplate.opsForValue().get(jobKey(jobId)))
            .isEqualTo(statusRaw)
        assertThat(redisTemplate.opsForValue().get(targetKey(jobId)))
            .isEqualTo(manifestRaw)
        assertThat(readJob(jobId).workerAttempt).isNull()
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }

        executor.accept()
        val acceptedCount = service.submitRecoverableJobs()

        val completed = readJob(jobId)
        assertThat(acceptedCount).isEqualTo(1)
        assertThat(completed.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(completed.workerAttempt?.ownerId).isEqualTo("owner-retry-submit")
        assertThat(completed.workerAttempt?.attemptNumber).isEqualTo(1)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verify(exactly = 1) { solvedAcClient.fetchProblem(1) }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ManifestCorruption::class)
    @DisplayName("손상된 manifest 작업은 owner claim 후 상태 충돌로 실패한다")
    fun `corrupt manifest fails through an owned transition without external work`(
        corruption: ManifestCorruption
    ) {
        val jobId = "job-corrupt-${corruption.name.lowercase()}"
        val oldAttempt = ProblemJobWorkerAttempt(
            ownerId = "owner-old",
            attemptId = "attempt-old-${corruption.name.lowercase()}",
            attemptNumber = 1
        )
        val manifest = metadataManifest(jobId, JobRange(1, 2))
        val seeded = persistRunningMetadataJob(
            jobId = jobId,
            manifest = manifest,
            attempt = oldAttempt,
            totalCount = 2,
            processedCount = 1,
            successCount = 1,
            failCount = 0,
            checkpointId = "1"
        )
        when (corruption) {
            ManifestCorruption.MISSING -> {
                redisTemplate.delete(targetKey(jobId))
            }

            ManifestCorruption.HASH_MISMATCH -> {
                val raw = requireNotNull(
                    redisTemplate.opsForValue().get(targetKey(jobId))
                )
                redisTemplate.opsForValue().set(
                    targetKey(jobId),
                    "$raw\n",
                    Duration.ofDays(1)
                )
            }
        }
        val service = createService(ownerId = "owner-corruption")

        val submittedCount = service.submitRecoverableJobs()

        val failed = readJob(jobId)
        assertThat(submittedCount).isEqualTo(1)
        assertThat(failed.status).isEqualTo(JobStatus.FAILED)
        assertThat(failed.errorCode)
            .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT.code)
        assertThat(failed.completedAt).isNotNull()
        assertThat(failed.startedAt).isEqualTo(seeded.startedAt)
        assertThat(failed.processedCount).isEqualTo(1)
        assertThat(failed.successCount).isEqualTo(1)
        assertThat(failed.failCount).isZero()
        assertThat(failed.workerAttempt?.ownerId).isEqualTo("owner-corruption")
        assertThat(failed.workerAttempt?.attemptNumber)
            .isEqualTo(oldAttempt.attemptNumber + 1)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }
        verify(exactly = 0) { problemRepository.upsertMetadata(any<Problem>()) }
    }

    @Test
    @DisplayName("legacy RUNNING 작업은 진행 상태를 보존하고 안전하게 실패한다")
    fun `legacy running job without a manifest fails without external work`() {
        val jobId = "job-legacy-running"
        val oldAttempt = ProblemJobWorkerAttempt(
            ownerId = "owner-legacy-old",
            attemptId = "attempt-legacy-old",
            attemptNumber = 4
        )
        val seeded = persistRunningMetadataJob(
            jobId = jobId,
            manifest = null,
            attempt = oldAttempt,
            totalCount = 3,
            processedCount = 2,
            successCount = 1,
            failCount = 1,
            checkpointId = "2",
            failedProblemIds = setOf("2")
        )
        val service = createService(ownerId = "owner-legacy-recovery")

        val submittedCount = service.submitRecoverableJobs()

        val failed = readJob(jobId)
        assertThat(submittedCount).isEqualTo(1)
        assertThat(failed.status).isEqualTo(JobStatus.FAILED)
        assertThat(failed.errorCode).isEqualTo(ErrorCode.WORKER_UNAVAILABLE.code)
        assertThat(failed.startedAt).isEqualTo(seeded.startedAt)
        assertThat(failed.processedCount).isEqualTo(2)
        assertThat(failed.successCount).isEqualTo(1)
        assertThat(failed.failCount).isEqualTo(1)
        assertThat(failed.lastCheckpointId).isEqualTo("2")
        assertThat(failed.targetManifest).isNull()
        assertThat(failed.workerAttempt?.ownerId)
            .isEqualTo("owner-legacy-recovery")
        assertThat(failed.workerAttempt?.attemptNumber)
            .isEqualTo(oldAttempt.attemptNumber + 1)
        assertThat(redisTemplate.opsForSet().members(failureKey(jobId)))
            .containsExactly("2")
        assertThat(redisTemplate.hasKey(targetKey(jobId))).isFalse()
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }
        verify(exactly = 0) { problemRepository.upsertMetadata(any<Problem>()) }
    }

    @Test
    @DisplayName("attempt 번호를 더 늘릴 수 없는 작업은 실행기에 제출하지 않는다")
    fun `maximum attempt number fails closed before submission`() {
        val jobId = "job-attempt-exhausted"
        val exhaustedAttempt = ProblemJobWorkerAttempt(
            ownerId = "owner-exhausted",
            attemptId = "attempt-exhausted",
            attemptNumber = Long.MAX_VALUE
        )
        persistRunningMetadataJob(
            jobId = jobId,
            manifest = metadataManifest(jobId, JobRange(1, 2)),
            attempt = exhaustedAttempt,
            totalCount = 2,
            processedCount = 1,
            successCount = 1,
            failCount = 0,
            checkpointId = "1"
        )
        val statusRaw = requireNotNull(
            redisTemplate.opsForValue().get(jobKey(jobId))
        )
        val executor = CaptureOnlyExecutor()
        val service = createService(
            ownerId = "owner-next",
            taskExecutor = executor
        )

        val submittedCount = service.submitRecoverableJobs()

        assertThat(submittedCount).isZero()
        assertThat(executor.taskCount).isZero()
        assertThat(redisTemplate.opsForValue().get(jobKey(jobId)))
            .isEqualTo(statusRaw)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }
        verify(exactly = 0) { problemRepository.upsertMetadata(any<Problem>()) }
    }

    @Test
    @DisplayName("큐 대기 중 attempt 번호가 소진돼도 claim은 상태를 바꾸지 않는다")
    fun `claim rechecks an exhausted attempt after queueing`() {
        val jobId = "job-attempt-exhausted-in-queue"
        val queuedAttempt = ProblemJobWorkerAttempt(
            ownerId = "owner-before-queue",
            attemptId = "attempt-before-queue",
            attemptNumber = 7
        )
        persistRunningMetadataJob(
            jobId = jobId,
            manifest = metadataManifest(jobId, JobRange(1, 2)),
            attempt = queuedAttempt,
            totalCount = 2,
            processedCount = 1,
            successCount = 1,
            failCount = 0,
            checkpointId = "1"
        )
        val executor = CaptureOnlyExecutor()
        val service = createService(
            ownerId = "owner-next",
            taskExecutor = executor
        )

        assertThat(service.submitRecoverableJobs()).isEqualTo(1)

        val exhausted = readJob(jobId).copy(
            workerAttempt = queuedAttempt.copy(attemptNumber = Long.MAX_VALUE)
        )
        redisTemplate.opsForValue().set(
            jobKey(jobId),
            objectMapper.writeValueAsString(exhausted),
            Duration.ofDays(1)
        )
        val exhaustedRaw = requireNotNull(
            redisTemplate.opsForValue().get(jobKey(jobId))
        )

        executor.singleTask().run()

        assertThat(redisTemplate.opsForValue().get(jobKey(jobId)))
            .isEqualTo(exhaustedRaw)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }
        verify(exactly = 0) { problemRepository.upsertMetadata(any<Problem>()) }
    }

    private fun createService(
        ownerId: String,
        taskExecutor: Executor? = null,
        mapper: ObjectMapper = objectMapper
    ): ProblemCollectorService {
        val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
        heartbeatExecutors.add(heartbeatExecutor)
        return ProblemCollectorService(
            solvedAcClient = solvedAcClient,
            problemRepository = problemRepository,
            bojCrawler = bojCrawler,
            redisTemplate = redisTemplate,
            objectMapper = mapper,
            adminAuditService = adminAuditService,
            pacer = pacer,
            recoveryState = ProblemCollectorRecoveryState(
                ProblemCollectorRecoveryProperties()
            ),
            taskExecutor = taskExecutor,
            workerLeaseProperties = ProblemCollectorWorkerLeaseProperties(
                enabled = true,
                leaseDuration = Duration.ofSeconds(3),
                heartbeatInterval = Duration.ofMillis(500)
            ),
            workerIdentity = ProblemCollectorWorkerIdentity(ownerId),
            heartbeatExecutor = heartbeatExecutor
        )
    }

    private fun persistPendingMetadataJob(jobId: String): JobStatusUnifiedResponse {
        val queuedAt = 1_700_001_000L
        val manifest = metadataManifest(jobId, JobRange(1, 1))
        val pending = JobStatusUnifiedResponse(
            jobId = jobId,
            jobType = ProblemJobType.COLLECT_METADATA,
            status = JobStatus.PENDING,
            queuedAt = queuedAt,
            startedAt = null,
            lastHeartbeatAt = queuedAt,
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
        return persistJob(pending, manifest)
    }

    private fun persistRunningMetadataJob(
        jobId: String,
        manifest: ProblemJobTargetManifest?,
        attempt: ProblemJobWorkerAttempt,
        totalCount: Int,
        processedCount: Int,
        successCount: Int,
        failCount: Int,
        checkpointId: String?,
        failedProblemIds: Set<String> = emptySet()
    ): JobStatusUnifiedResponse {
        val startedAt = 1_700_002_000L
        val running = JobStatusUnifiedResponse(
            jobId = jobId,
            jobType = ProblemJobType.COLLECT_METADATA,
            status = JobStatus.RUNNING,
            queuedAt = startedAt - 10,
            startedAt = startedAt,
            lastHeartbeatAt = startedAt + 1,
            completedAt = null,
            totalCount = totalCount,
            processedCount = processedCount,
            successCount = successCount,
            failCount = failCount,
            progressPercentage = processedCount * 100 / totalCount,
            estimatedRemainingSeconds = null,
            queuePosition = null,
            range = JobRange(1, totalCount),
            lastCheckpointId = checkpointId,
            errorCode = null,
            errorMessage = null,
            createdBy = "admin",
            workerAttempt = attempt
        )
        return persistJob(running, manifest, failedProblemIds)
    }

    private fun persistJob(
        job: JobStatusUnifiedResponse,
        manifest: ProblemJobTargetManifest?,
        failedProblemIds: Set<String> = emptySet()
    ): JobStatusUnifiedResponse {
        val manifestRaw = manifest?.let { objectMapper.writeValueAsString(it) }
        val stored = job.copy(
            targetManifest = manifest?.let {
                ProblemJobTargetManifestReference(
                    schemaVersion = it.version,
                    sha256 = sha256(requireNotNull(manifestRaw))
                )
            }
        )
        redisTemplate.opsForValue().set(
            jobKey(stored.jobId),
            objectMapper.writeValueAsString(stored),
            Duration.ofDays(1)
        )
        if (manifestRaw != null) {
            redisTemplate.opsForValue().set(
                targetKey(stored.jobId),
                manifestRaw,
                Duration.ofDays(1)
            )
        }
        redisTemplate.opsForZSet().add(
            JOB_INDEX_KEY,
            stored.jobId,
            stored.queuedAt.toDouble()
        )
        if (failedProblemIds.isNotEmpty()) {
            redisTemplate.opsForSet().add(
                failureKey(stored.jobId),
                *failedProblemIds.toTypedArray()
            )
            redisTemplate.expire(failureKey(stored.jobId), Duration.ofDays(1))
        }
        return stored
    }

    private fun metadataManifest(
        jobId: String,
        range: JobRange
    ): ProblemJobTargetManifest {
        return ProblemJobTargetManifest(
            version = ProblemJobTargetManifest.CURRENT_VERSION,
            jobId = jobId,
            jobType = ProblemJobType.COLLECT_METADATA,
            range = range
        )
    }

    private fun mapperWithClaimBarrier(
        barrier: CyclicBarrier,
        expectedStatus: JobStatus
    ): ObjectMapper {
        val mapper = mockk<ObjectMapper>()
        val firstClaimRead = AtomicBoolean(true)
        every { mapper.writeValueAsString(any()) } answers {
            objectMapper.writeValueAsString(firstArg())
        }
        every {
            mapper.readValue(any<String>(), JobStatusUnifiedResponse::class.java)
        } answers {
            objectMapper.readValue(
                firstArg<String>(),
                JobStatusUnifiedResponse::class.java
            ).also { status ->
                if (
                    Thread.currentThread().name.startsWith(CLAIM_THREAD_PREFIX) &&
                    status.status == expectedStatus &&
                    firstClaimRead.compareAndSet(true, false)
                ) {
                    barrier.await(5, TimeUnit.SECONDS)
                }
            }
        }
        every {
            mapper.readValue(any<String>(), ProblemJobTargetManifest::class.java)
        } answers {
            objectMapper.readValue(
                firstArg<String>(),
                ProblemJobTargetManifest::class.java
            )
        }
        return mapper
    }

    private fun readJob(jobId: String): JobStatusUnifiedResponse {
        return objectMapper.readValue(
            requireNotNull(redisTemplate.opsForValue().get(jobKey(jobId))),
            JobStatusUnifiedResponse::class.java
        )
    }

    private fun problemResponse(problemId: Int): SolvedAcProblemResponse {
        return SolvedAcProblemResponse(problemId, "P$problemId", 1, emptyList())
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    private fun deleteJobKeys() {
        listOf(
            JOB_KEY_PREFIX,
            JOB_FAILURE_KEY_PREFIX,
            JOB_TARGET_KEY_PREFIX,
            JOB_LEASE_KEY_PREFIX
        ).forEach { prefix ->
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

    private fun leaseKey(jobId: String): String = "$JOB_LEASE_KEY_PREFIX$jobId"

    private class CaptureOnlyExecutor : Executor {
        private val tasks = mutableListOf<Runnable>()

        val taskCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun singleTask(): Runnable = tasks.single()
    }

    private class RejectThenRunExecutor : Executor {
        private var rejecting = true

        override fun execute(command: Runnable) {
            if (rejecting) {
                throw RejectedExecutionException("rejected for test")
            }
            command.run()
        }

        fun accept() {
            rejecting = false
        }
    }

    enum class ManifestCorruption {
        MISSING,
        HASH_MISMATCH
    }

    private companion object {
        const val JOB_KEY_PREFIX = "problem:job:status:"
        const val JOB_FAILURE_KEY_PREFIX = "problem:job:failures:"
        const val JOB_TARGET_KEY_PREFIX = "problem:job:targets:"
        const val JOB_LEASE_KEY_PREFIX = "problem:job:lease:"
        const val JOB_INDEX_KEY = "problem:job:index"
        const val CLAIM_THREAD_PREFIX = "takeover-claim-"
    }
}
