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
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
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
        "spring.data.redis.database=10"
    ]
)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("문제 수집 worker lease 통합 테스트")
class ProblemCollectorWorkerLeaseIntegrationTest {

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
    @DisplayName("같은 PENDING 작업을 동시에 실행해도 lease를 얻은 worker만 처리한다")
    fun `only one worker claims a pending job`() {
        var submittedTask: Runnable? = null
        val claimBarrier = CyclicBarrier(2)
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val service = createService(
            ownerId = "owner-a",
            taskExecutor = Executor { submittedTask = it },
            mapper = mapperWithPendingBarrier(claimBarrier)
        )
        every { solvedAcClient.fetchProblem(1) } answers {
            fetchStarted.countDown()
            check(releaseFetch.await(5, TimeUnit.SECONDS))
            problemResponse(1)
        }
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        val task = requireNotNull(submittedTask)
        val outcomes = Collections.synchronizedList(mutableListOf<Throwable?>())
        val pool = Executors.newFixedThreadPool(2)

        try {
            val futures = List(2) {
                pool.submit {
                    outcomes.add(
                        runCatching { task.run() }.exceptionOrNull()
                    )
                }
            }
            check(fetchStarted.await(5, TimeUnit.SECONDS))
            waitUntil(Duration.ofSeconds(5)) {
                futures.count { it.isDone } == 1
            }

            val running = readJob(jobId)
            assertThat(running.status).isEqualTo(JobStatus.RUNNING)
            assertThat(redisTemplate.opsForValue().get(leaseKey(jobId)))
                .isEqualTo(objectMapper.writeValueAsString(running.workerAttempt))

            releaseFetch.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            releaseFetch.countDown()
            pool.shutdownNow()
        }

        assertThat(outcomes).containsOnlyNulls()
        val stored = readJob(jobId)
        assertThat(stored.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(stored.workerAttempt?.ownerId).isEqualTo("owner-a")
        assertThat(stored.workerAttempt?.attemptNumber).isEqualTo(1)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verify(exactly = 1) { solvedAcClient.fetchProblem(1) }
        verify(exactly = 1) { problemRepository.upsertMetadata(any<Problem>()) }
    }

    @Test
    @DisplayName("긴 항목 처리 중 heartbeat가 lease 만료를 막고 완료 시 lease를 지운다")
    fun `heartbeat renews lease until the worker completes`() {
        var submittedTask: Runnable? = null
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val service = createService(
            ownerId = "owner-heartbeat",
            taskExecutor = Executor { submittedTask = it },
            leaseDuration = Duration.ofSeconds(2),
            heartbeatInterval = Duration.ofMillis(400)
        )
        every { solvedAcClient.fetchProblem(1) } answers {
            fetchStarted.countDown()
            check(releaseFetch.await(5, TimeUnit.SECONDS))
            problemResponse(1)
        }
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        val worker = Executors.newSingleThreadExecutor()
        val future = worker.submit(requireNotNull(submittedTask))

        try {
            check(fetchStarted.await(5, TimeUnit.SECONDS))
            val started = readJob(jobId)
            assertThat(started.status).isEqualTo(JobStatus.RUNNING)
            assertLeaseStaysAlive(
                jobId,
                observationDuration = Duration.ofMillis(2_600),
                configuredLease = Duration.ofSeconds(2)
            )

            val heartbeatState = readJob(jobId)
            assertThat(heartbeatState.lastHeartbeatAt).isGreaterThan(started.lastHeartbeatAt)
            releaseFetch.countDown()
            future.get(10, TimeUnit.SECONDS)
        } finally {
            releaseFetch.countDown()
            worker.shutdownNow()
        }

        assertThat(readJob(jobId).status).isEqualTo(JobStatus.COMPLETED)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
    }

    @Test
    @DisplayName("heartbeat와 실패 진행률이 경합해도 진행률과 실패 원장을 함께 저장한다")
    fun `progress retries after a heartbeat state conflict`() {
        var submittedTask: Runnable? = null
        val progressRead = CountDownLatch(1)
        val releaseProgress = CountDownLatch(1)
        val blockedHeartbeat = arrayOfNulls<Long>(1)
        val runningReadCount = AtomicInteger()
        val mapper = mapperWithJobReadCallback { status ->
            if (
                Thread.currentThread().name == PROGRESS_WORKER_THREAD &&
                status.status == JobStatus.RUNNING &&
                runningReadCount.incrementAndGet() == 2
            ) {
                blockedHeartbeat[0] = status.lastHeartbeatAt
                progressRead.countDown()
                check(releaseProgress.await(5, TimeUnit.SECONDS))
            }
        }
        val service = createService(
            ownerId = "owner-progress-race",
            taskExecutor = Executor { submittedTask = it },
            leaseDuration = Duration.ofSeconds(1),
            heartbeatInterval = Duration.ofMillis(100),
            mapper = mapper
        )
        every { solvedAcClient.fetchProblem(1) } throws IllegalStateException("item failed")

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, PROGRESS_WORKER_THREAD)
        }
        val future = worker.submit(requireNotNull(submittedTask))

        try {
            check(progressRead.await(5, TimeUnit.SECONDS))
            waitUntil(Duration.ofSeconds(3)) {
                val currentHeartbeat = readJob(jobId).lastHeartbeatAt
                currentHeartbeat != null && currentHeartbeat > requireNotNull(blockedHeartbeat[0])
            }
            releaseProgress.countDown()
            future.get(10, TimeUnit.SECONDS)
        } finally {
            releaseProgress.countDown()
            worker.shutdownNow()
        }

        val stored = readJob(jobId)
        assertThat(stored.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(stored.processedCount).isEqualTo(1)
        assertThat(stored.failCount).isEqualTo(1)
        assertThat(redisTemplate.opsForSet().members(failureKey(jobId))).containsExactly("1")
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
    }

    @Test
    @DisplayName("관리자 취소는 상태 전이와 lease 삭제를 함께 처리한다")
    fun `cancellation revokes the active worker lease`() {
        var submittedTask: Runnable? = null
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val workerService = createService(
            ownerId = "owner-cancelled",
            taskExecutor = Executor { submittedTask = it }
        )
        val adminService = createService(ownerId = "owner-admin")
        every { solvedAcClient.fetchProblem(1) } answers {
            fetchStarted.countDown()
            check(releaseFetch.await(5, TimeUnit.SECONDS))
            problemResponse(1)
        }
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        val jobId = workerService.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        val worker = Executors.newSingleThreadExecutor()
        val future = worker.submit(requireNotNull(submittedTask))

        try {
            check(fetchStarted.await(5, TimeUnit.SECONDS))
            assertThat(redisTemplate.hasKey(leaseKey(jobId))).isTrue()

            val cancelled = adminService.cancelJob(jobId, "admin", "127.0.0.1")
            assertThat(cancelled.status).isEqualTo(JobStatus.CANCELLED)
            assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
            val cancelledJson = requireNotNull(
                redisTemplate.opsForValue().get(jobKey(jobId))
            )

            releaseFetch.countDown()
            future.get(10, TimeUnit.SECONDS)
            assertThat(redisTemplate.opsForValue().get(jobKey(jobId)))
                .isEqualTo(cancelledJson)
        } finally {
            releaseFetch.countDown()
            worker.shutdownNow()
        }

        val stored = readJob(jobId)
        assertThat(stored.status).isEqualTo(JobStatus.CANCELLED)
        assertThat(stored.processedCount).isZero()
        assertThat(redisTemplate.opsForSet().members(failureKey(jobId))).isEmpty()
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verify(exactly = 1) { problemRepository.upsertMetadata(any<Problem>()) }
    }

    @Test
    @DisplayName("이전 worker는 다른 lease의 상태와 실패 원장과 TTL을 변경하지 못한다")
    fun `stale worker cannot write when the lease value changes`() {
        var submittedTask: Runnable? = null
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val service = createService(
            ownerId = "owner-old",
            taskExecutor = Executor { submittedTask = it },
            leaseDuration = Duration.ofSeconds(30),
            heartbeatInterval = Duration.ofSeconds(10)
        )
        every { solvedAcClient.fetchProblem(1) } answers {
            fetchStarted.countDown()
            check(releaseFetch.await(5, TimeUnit.SECONDS))
            throw IllegalStateException("late failure")
        }

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        val worker = Executors.newSingleThreadExecutor()
        val future = worker.submit(requireNotNull(submittedTask))

        try {
            check(fetchStarted.await(5, TimeUnit.SECONDS))
            val oldState = readJob(jobId)
            val newAttempt = ProblemJobWorkerAttempt(
                ownerId = "owner-new",
                attemptId = "attempt-new",
                attemptNumber = requireNotNull(oldState.workerAttempt).attemptNumber + 1
            )
            val newLeaseValue = objectMapper.writeValueAsString(newAttempt)
            redisTemplate.opsForValue().set(
                leaseKey(jobId),
                newLeaseValue,
                Duration.ofSeconds(60)
            )

            releaseFetch.countDown()
            future.get(10, TimeUnit.SECONDS)

            val stored = readJob(jobId)
            assertThat(stored.status).isEqualTo(JobStatus.RUNNING)
            assertThat(stored.workerAttempt).isEqualTo(oldState.workerAttempt)
            assertThat(stored.processedCount).isZero()
            assertThat(stored.failCount).isZero()
            assertThat(redisTemplate.opsForSet().members(failureKey(jobId))).isEmpty()
            assertThat(redisTemplate.opsForValue().get(leaseKey(jobId))).isEqualTo(newLeaseValue)
        } finally {
            releaseFetch.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    @DisplayName("이전 heartbeat는 교체된 lease의 TTL을 연장하지 못한다")
    fun `stale heartbeat cannot renew a replacement lease`() {
        var submittedTask: Runnable? = null
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val trackingExecutor = TrackingScheduledExecutor()
        val service = createService(
            ownerId = "owner-stale-heartbeat",
            taskExecutor = Executor { submittedTask = it },
            heartbeatExecutorOverride = trackingExecutor
        )
        every { solvedAcClient.fetchProblem(1) } answers {
            fetchStarted.countDown()
            check(releaseFetch.await(5, TimeUnit.SECONDS))
            problemResponse(1)
        }
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        val worker = Executors.newSingleThreadExecutor()
        val future = worker.submit(requireNotNull(submittedTask))

        try {
            check(fetchStarted.await(5, TimeUnit.SECONDS))
            val replacement = ProblemJobWorkerAttempt(
                ownerId = "replacement-owner",
                attemptId = "replacement-attempt",
                attemptNumber = 2
            )
            val replacementValue = objectMapper.writeValueAsString(replacement)
            redisTemplate.opsForValue().set(
                leaseKey(jobId),
                replacementValue,
                Duration.ofSeconds(10)
            )

            check(trackingExecutor.completedTask.await(3, TimeUnit.SECONDS))

            assertThat(redisTemplate.opsForValue().get(leaseKey(jobId)))
                .isEqualTo(replacementValue)
            assertThat(redisTemplate.getExpire(leaseKey(jobId), TimeUnit.MILLISECONDS))
                .isGreaterThan(8_000)

            releaseFetch.countDown()
            future.get(10, TimeUnit.SECONDS)
        } finally {
            releaseFetch.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    @DisplayName("실패 원장 타입이 잘못돼도 worker를 FAILED로 종료하고 lease를 지운다")
    fun `invalid failure ledger cannot strand a running worker`() {
        var submittedTask: Runnable? = null
        val service = createService(
            ownerId = "owner-invalid-ledger",
            taskExecutor = Executor { submittedTask = it }
        )
        every { solvedAcClient.fetchProblem(1) } throws IllegalStateException("item failed")

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        redisTemplate.opsForValue().set(
            failureKey(jobId),
            "wrong-type",
            Duration.ofDays(1)
        )

        requireNotNull(submittedTask).run()

        val stored = readJob(jobId)
        assertThat(stored.status).isEqualTo(JobStatus.FAILED)
        assertThat(stored.processedCount).isZero()
        assertThat(redisTemplate.opsForValue().get(failureKey(jobId))).isEqualTo("wrong-type")
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
    }

    @Test
    @DisplayName("실행 중 manifest가 사라져도 현재 worker의 완료 전이는 유지한다")
    fun `missing manifest does not block live worker state transitions`() {
        var submittedTask: Runnable? = null
        val service = createService(
            ownerId = "owner-missing-manifest",
            taskExecutor = Executor { submittedTask = it }
        )
        every { solvedAcClient.fetchProblem(1) } returns problemResponse(1)
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        redisTemplate.delete(targetKey(jobId))

        requireNotNull(submittedTask).run()

        val stored = readJob(jobId)
        assertThat(stored.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(stored.processedCount).isEqualTo(1)
        assertThat(redisTemplate.hasKey(targetKey(jobId))).isFalse()
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
    }

    @Test
    @DisplayName("heartbeat 실행기가 거부하면 작업을 FAILED로 종료하고 lease를 지운다")
    fun `heartbeat scheduling rejection fails the owned job`() {
        var submittedTask: Runnable? = null
        val service = createService(
            ownerId = "owner-heartbeat-rejected",
            taskExecutor = Executor { submittedTask = it }
        )
        val heartbeatExecutor = heartbeatExecutors.last()
        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        heartbeatExecutor.shutdownNow()

        requireNotNull(submittedTask).run()

        val stored = readJob(jobId)
        assertThat(stored.status).isEqualTo(JobStatus.FAILED)
        assertThat(stored.errorCode).isEqualTo(ErrorCode.WORKER_UNAVAILABLE.code)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }
    }

    @Test
    @DisplayName("worker lease 모드에서는 단일 BE 시작 복구를 직접 실행할 수 없다")
    fun `startup orphan failure is blocked in worker lease mode`() {
        val recoveryProperties = ProblemCollectorRecoveryProperties(
            failOrphanedJobsOnStartup = true
        )
        val service = createService(
            ownerId = "owner-recovery-conflict",
            recoveryState = ProblemCollectorRecoveryState(recoveryProperties)
        )

        val exception = assertThrows<IllegalStateException> {
            service.failOrphanedJobsDuringStartup()
        }

        assertThat(exception.message).contains("worker lease")
    }

    @Test
    @DisplayName("lease key 타입이 잘못되면 PENDING 상태를 바꾸지 않는다")
    fun `invalid lease type fails before changing job state`() {
        var submittedTask: Runnable? = null
        val service = createService(
            ownerId = "owner-invalid",
            taskExecutor = Executor { submittedTask = it }
        )
        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        val pendingJson = requireNotNull(redisTemplate.opsForValue().get(jobKey(jobId)))
        redisTemplate.opsForSet().add(leaseKey(jobId), "invalid")

        val exception = assertThrows<IllegalStateException> {
            requireNotNull(submittedTask).run()
        }

        assertThat(exception.message).contains("lease Redis 타입")
        assertThat(redisTemplate.opsForValue().get(jobKey(jobId))).isEqualTo(pendingJson)
        assertThat(readJob(jobId).status).isEqualTo(JobStatus.PENDING)
        assertThat(redisTemplate.type(leaseKey(jobId)).code()).isEqualTo("set")
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }
    }

    private fun createService(
        ownerId: String,
        taskExecutor: Executor? = null,
        leaseDuration: Duration = Duration.ofSeconds(3),
        heartbeatInterval: Duration = Duration.ofMillis(500),
        mapper: ObjectMapper = objectMapper,
        recoveryState: ProblemCollectorRecoveryState =
            ProblemCollectorRecoveryState(ProblemCollectorRecoveryProperties()),
        heartbeatExecutorOverride: ScheduledExecutorService? = null
    ): ProblemCollectorService {
        val heartbeatExecutor = heartbeatExecutorOverride
            ?: Executors.newSingleThreadScheduledExecutor()
        heartbeatExecutors.add(heartbeatExecutor)
        return ProblemCollectorService(
            solvedAcClient = solvedAcClient,
            problemRepository = problemRepository,
            bojCrawler = bojCrawler,
            redisTemplate = redisTemplate,
            objectMapper = mapper,
            adminAuditService = adminAuditService,
            pacer = pacer,
            recoveryState = recoveryState,
            taskExecutor = taskExecutor,
            workerLeaseProperties = ProblemCollectorWorkerLeaseProperties(
                enabled = true,
                leaseDuration = leaseDuration,
                heartbeatInterval = heartbeatInterval
            ),
            workerIdentity = ProblemCollectorWorkerIdentity(ownerId),
            heartbeatExecutor = heartbeatExecutor
        )
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

    private fun mapperWithPendingBarrier(barrier: CyclicBarrier): ObjectMapper {
        return mapperWithJobReadCallback { status ->
            if (status.status == JobStatus.PENDING) {
                barrier.await(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun mapperWithJobReadCallback(
        callback: (JobStatusUnifiedResponse) -> Unit
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
            ).also(callback)
        }
        return mapper
    }

    private fun assertLeaseStaysAlive(
        jobId: String,
        observationDuration: Duration,
        configuredLease: Duration
    ) {
        val deadline = System.nanoTime() + observationDuration.toNanos()
        var previousTtl = redisTemplate.getExpire(leaseKey(jobId), TimeUnit.MILLISECONDS)
        var observedRenewal = false
        while (System.nanoTime() < deadline) {
            val currentTtl = redisTemplate.getExpire(leaseKey(jobId), TimeUnit.MILLISECONDS)
            assertThat(currentTtl)
                .isPositive()
                .isLessThanOrEqualTo(configuredLease.toMillis())
            if (currentTtl > previousTtl + 50) {
                observedRenewal = true
            }
            previousTtl = currentTtl
            Thread.sleep(50)
        }
        assertThat(observedRenewal).isTrue()
    }

    private fun waitUntil(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition was not met within $timeout" }
            Thread.sleep(20)
        }
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

    companion object {
        private const val JOB_KEY_PREFIX = "problem:job:status:"
        private const val JOB_FAILURE_KEY_PREFIX = "problem:job:failures:"
        private const val JOB_TARGET_KEY_PREFIX = "problem:job:targets:"
        private const val JOB_LEASE_KEY_PREFIX = "problem:job:lease:"
        private const val JOB_INDEX_KEY = "problem:job:index"
        private const val PROGRESS_WORKER_THREAD = "collector-progress-race"
    }

    private class TrackingScheduledExecutor : ScheduledThreadPoolExecutor(1) {
        val completedTask = CountDownLatch(1)

        override fun afterExecute(runnable: Runnable, throwable: Throwable?) {
            super.afterExecute(runnable, throwable)
            completedTask.countDown()
        }
    }
}
