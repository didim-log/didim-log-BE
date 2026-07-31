package com.didimlog.application.problem.collector

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemDetailsUpdate
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.crawler.ProblemDetails
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcProblemResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.data.redis.core.script.RedisScript

@DisplayName("ProblemCollectorService 테스트")
class ProblemCollectorServiceTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val problemRepository: ProblemRepository = mockk()
    private val bojCrawler: BojCrawler = mockk()
    private val redisTemplate: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private val setOps: SetOperations<String, String> = mockk()
    private val zSetOps: ZSetOperations<String, String> = mockk()
    private val adminAuditService: AdminAuditService = mockk(relaxed = true)
    private val pacer: ProblemCollectorPacer = mockk(relaxed = true)

    private val objectMapper = ObjectMapper().registerKotlinModule()

    private lateinit var service: ProblemCollectorService

    private val valueStore = ConcurrentHashMap<String, String>()
    private val failureStore = ConcurrentHashMap<String, MutableSet<String>>()
    private val zsetStore = ConcurrentHashMap<String, MutableMap<String, Double>>()

    companion object {
        private const val JOB_KEY_PREFIX = "problem:job:status:"
        private const val JOB_FAILURE_KEY_PREFIX = "problem:job:failures:"
        private const val JOB_INDEX_KEY = "problem:job:index"
    }

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { redisTemplate.opsForSet() } returns setOps
        every { redisTemplate.opsForZSet() } returns zSetOps
        every { redisTemplate.type(any()) } answers {
            if (failureStore.containsKey(firstArg())) DataType.SET else DataType.NONE
        }
        every { setOps.members(any()) } answers {
            failureStore[firstArg()].orEmpty().toSet()
        }
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                *anyVararg()
            )
        } answers {
            val keys = secondArg<List<String>>()
            val scriptArguments = thirdArg<Array<out Any>>()
            synchronized(valueStore) {
                when {
                    keys.size == 2 && keys[1] == JOB_INDEX_KEY -> {
                        val jobKey = keys[0]
                        if (valueStore.containsKey(jobKey)) {
                            0L
                        } else {
                            val jobId = scriptArguments[3].toString()
                            valueStore[jobKey] = scriptArguments[0].toString()
                            zsetStore.computeIfAbsent(keys[1]) { mutableMapOf() }[jobId] =
                                scriptArguments[2].toString().toDouble()
                            1L
                        }
                    }

                    keys.size == 2 -> {
                        val current = valueStore[keys[0]]
                        when {
                            current == null -> -1L
                            current != scriptArguments[0].toString() -> 0L
                            else -> {
                                valueStore[keys[0]] = scriptArguments[1].toString()
                                val failedProblemId = scriptArguments[3].toString()
                                if (failedProblemId.isNotEmpty()) {
                                    failureStore.computeIfAbsent(keys[1]) { mutableSetOf() }
                                        .add(failedProblemId)
                                }
                                1L
                            }
                        }
                    }

                    else -> error("Unexpected Redis script keys: $keys")
                }
            }
        }

        every { valueOps.set(any(), any(), any<Duration>()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String>()
            valueStore[key] = value
        }
        every { valueOps.get(any()) } answers {
            valueStore[firstArg<String>()]
        }
        every { valueOps.multiGet(any()) } answers {
            firstArg<Collection<String>>().map(valueStore::get)
        }

        every { zSetOps.add(any(), any(), any<Double>()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String>()
            val score = thirdArg<Double>()
            val set = zsetStore.computeIfAbsent(key) { mutableMapOf() }
            set[value] = score
            true
        }

        every { zSetOps.reverseRange(any(), any(), any()) } answers {
            val key = firstArg<String>()
            val set = zsetStore[key].orEmpty()
            val sorted = set.entries
                .sortedByDescending { it.value }
                .map { it.key }
            LinkedHashSet(sorted)
        }

        every { zSetOps.remove(any(), *anyVararg()) } answers {
            val key = firstArg<String>()
            val set = zsetStore[key] ?: return@answers 0L
            val values = args.drop(1)
            var removed = 0L
            values.forEach { value ->
                val casted = value as? String ?: return@forEach
                if (set.remove(casted) != null) {
                    removed++
                }
            }
            removed
        }

        every { adminAuditService.logAction(any(), any(), any(), any()) } just runs

        service = createService()
    }

    @Test
    @DisplayName("재시작 복구 중에는 새 작업 상태를 Redis에 만들지 않는다")
    fun `job creation is blocked until restart recovery is ready`() {
        val properties = ProblemCollectorRecoveryProperties(
            failOrphanedJobsOnStartup = true
        )
        val recoveryState = ProblemCollectorRecoveryState(properties)
        val recoveringService = createService(recoveryState = recoveryState)

        val exception = assertThrows<BusinessException> {
            recoveringService.collectMetadataAsync(
                start = 1,
                end = 1,
                createdBy = "admin",
                ipAddress = "127.0.0.1"
            )
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.WORKER_UNAVAILABLE)
        assertThat(valueStore).isEmpty()
        assertThat(zsetStore[JOB_INDEX_KEY].orEmpty()).isEmpty()
        verify(exactly = 0) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                *anyVararg()
            )
        }
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }
        verify(exactly = 0) { adminAuditService.logAction(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("감사 로그 실행기가 요청을 거부해도 생성한 작업은 계속 실행한다")
    fun `audit rejection does not prevent worker execution`() {
        every { adminAuditService.logAction(any(), any(), any(), any()) } throws
            RejectedExecutionException("audit executor rejected")
        every { solvedAcClient.fetchProblem(1) } returns
            SolvedAcProblemResponse(1, "A", 1, emptyList())
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs
        val workerService = createService(taskExecutor = Executor { task -> task.run() })

        val jobId = workerService.collectMetadataAsync(
            start = 1,
            end = 1,
            createdBy = "admin",
            ipAddress = "127.0.0.1"
        )

        val stored = objectMapper.readValue(
            requireNotNull(valueStore["$JOB_KEY_PREFIX$jobId"]),
            JobStatusUnifiedResponse::class.java
        )
        assertThat(stored.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(stored.processedCount).isEqualTo(1)
        assertThat(stored.successCount).isEqualTo(1)
        verify(exactly = 1) { adminAuditService.logAction(any(), any(), any(), any()) }
        verify(exactly = 1) { solvedAcClient.fetchProblem(1) }
        verify(exactly = 1) { problemRepository.upsertMetadata(any<Problem>()) }
    }

    @Test
    @DisplayName("작업 목록 상태를 index 순서의 키로 한 번에 조회한다")
    fun `job list loads indexed statuses in one batch`() {
        val oldestPending = sampleJob("job-oldest").copy(
            queuedAt = 1_700_000_001
        )
        val newestRunning = sampleJob("job-newest").copy(
            status = JobStatus.RUNNING,
            queuedAt = 1_700_000_003,
            startedAt = 1_700_000_004
        )
        val middlePending = sampleJob("job-middle").copy(
            queuedAt = 1_700_000_002
        )
        listOf(oldestPending, newestRunning, middlePending).forEach(::seedJob)

        val result = service.getJobs(
            type = null,
            status = null,
            from = null,
            to = null,
            page = 1,
            size = 2
        )

        assertThat(result.content.map(JobStatusUnifiedResponse::jobId))
            .containsExactly("job-newest", "job-middle")
        assertThat(result.content.map(JobStatusUnifiedResponse::queuePosition))
            .containsExactly(null, 2)
        assertThat(result.totalElements).isEqualTo(3)
        assertThat(result.totalPages).isEqualTo(2)
        assertThat(result.hasNext).isTrue()
        assertThat(result.hasPrevious).isFalse()
        verify(exactly = 1) {
            valueOps.multiGet(
                listOf(
                    "$JOB_KEY_PREFIX${newestRunning.jobId}",
                    "$JOB_KEY_PREFIX${middlePending.jobId}",
                    "$JOB_KEY_PREFIX${oldestPending.jobId}"
                )
            )
        }
        verify(exactly = 0) { valueOps.get(any()) }
    }

    @Test
    @DisplayName("일괄 조회 직후 생성된 String 상태는 단건 조회로 다시 확인한다")
    fun `job list rechecks string status created after batch read`() {
        val job = sampleJob("job-created-after-batch")
        val key = "$JOB_KEY_PREFIX${job.jobId}"
        seedJob(job)
        every { valueOps.multiGet(listOf(key)) } returns listOf(null)
        every { redisTemplate.type(key) } returns DataType.STRING

        val result = service.getJobs(
            type = null,
            status = null,
            from = null,
            to = null,
            page = 1,
            size = 20
        )

        assertThat(result.content).containsExactly(job.copy(queuePosition = 1))
        verify(exactly = 1) { valueOps.get(key) }
    }

    @Test
    @DisplayName("메타데이터 수집 작업은 COMPLETED 상태로 종료되며 공통 상태 응답을 반환한다")
    fun `collect metadata status transition`() {
        every { solvedAcClient.fetchProblem(1) } returns SolvedAcProblemResponse(1, "A", 1, emptyList())
        every { solvedAcClient.fetchProblem(2) } returns SolvedAcProblemResponse(2, "B", 1, emptyList())
        every { problemRepository.upsertMetadata(any()) } just runs

        val jobId = service.collectMetadataAsync(1, 2, "admin", "127.0.0.1")
        val status = service.getMetadataCollectJobStatus(jobId)

        assertThat(status).isNotNull
        assertThat(status!!.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(status.totalCount).isEqualTo(2)
        assertThat(status.processedCount).isEqualTo(2)
        assertThat(status.progressPercentage).isEqualTo(100)
        assertThat(status.lastCheckpointId).isEqualTo("2")
        assertThat(status.startedAt).isNotNull
        assertThat(status.completedAt).isNotNull
        verify(exactly = 1) {
            problemRepository.upsertMetadata(
                match<Problem> { problem ->
                    problem.id.value == "1" &&
                        problem.title == "A" &&
                        problem.url == "https://www.acmicpc.net/problem/1"
                }
            )
        }
        verify(exactly = 1) {
            problemRepository.upsertMetadata(
                match<Problem> { problem ->
                    problem.id.value == "2" &&
                        problem.title == "B" &&
                        problem.url == "https://www.acmicpc.net/problem/2"
                }
            )
        }
        verify(exactly = 0) { problemRepository.findById(any()) }
        verify(exactly = 0) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("메타데이터 수집 요청은 작업을 executor에 제출하고 즉시 PENDING 상태를 반환한다")
    fun `collect metadata submits work to executor`() {
        var submittedTask: Runnable? = null
        service = createService(Executor { task -> submittedTask = task })
        every { solvedAcClient.fetchProblem(1) } returns SolvedAcProblemResponse(1, "A", 1, emptyList())
        every { problemRepository.upsertMetadata(any()) } just runs

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")

        assertThat(service.getMetadataCollectJobStatus(jobId)?.status).isEqualTo(JobStatus.PENDING)
        assertThat(submittedTask).isNotNull

        submittedTask!!.run()

        assertThat(service.getMetadataCollectJobStatus(jobId)?.status).isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    @DisplayName("동일한 메타데이터 수집 작업은 두 번 실행돼도 한 번만 처리한다")
    fun `same metadata task runs only once`() {
        var submittedTask: Runnable? = null
        service = createService(Executor { task -> submittedTask = task })
        every { solvedAcClient.fetchProblem(1) } returns SolvedAcProblemResponse(1, "A", 1, emptyList())
        every { problemRepository.upsertMetadata(any()) } just runs

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        val capturedTask = requireNotNull(submittedTask)

        capturedTask.run()
        capturedTask.run()

        assertThat(service.getMetadataCollectJobStatus(jobId)?.status).isEqualTo(JobStatus.COMPLETED)
        verify(exactly = 1) { solvedAcClient.fetchProblem(1) }
        verify(exactly = 1) { problemRepository.upsertMetadata(any()) }
    }

    @Test
    @DisplayName("executor가 작업을 거부하면 수집 작업을 FAILED로 전환한다")
    fun `collect metadata marks job failed when executor rejects`() {
        service = createService(Executor { throw RejectedExecutionException("queue full") })

        val jobId = service.collectMetadataAsync(1, 1, "admin", "127.0.0.1")
        val status = service.getMetadataCollectJobStatus(jobId)

        assertThat(status?.status).isEqualTo(JobStatus.FAILED)
        assertThat(status?.errorCode).isEqualTo(ErrorCode.WORKER_UNAVAILABLE.code)
        assertThat(status?.errorMessage).isEqualTo("작업 실행을 제출할 수 없습니다.")
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }
    }

    @Test
    @DisplayName("작업 취소는 RUNNING에서 CANCELLED로 전이되며 이후 재취소는 409를 반환한다")
    fun `cancel transition and terminal conflict`() {
        val jobId = "job-cancel-1"
        seedJob(
            sampleJob(jobId).copy(
                status = JobStatus.RUNNING,
                startedAt = 1700000001,
                lastHeartbeatAt = 1700000002
            )
        )

        val cancelled = service.cancelJob(jobId, "admin", "127.0.0.1")
        assertThat(cancelled.status).isEqualTo(JobStatus.CANCELLED)
        assertThat(cancelled.completedAt).isNotNull

        val ex = assertThrows<BusinessException> {
            service.cancelJob(jobId, "admin", "127.0.0.1")
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.JOB_ALREADY_TERMINAL)
    }

    @Test
    @DisplayName("실패 작업 재시도는 체크포인트 이후 범위만 재실행한다")
    fun `retry uses checkpoint`() {
        val sourceJobId = "job-failed-1"
        seedJob(
            sampleJob(sourceJobId).copy(
                jobType = ProblemJobType.COLLECT_METADATA,
                status = JobStatus.FAILED,
                range = JobRange(1, 5),
                totalCount = 5,
                processedCount = 3,
                successCount = 3,
                failCount = 0,
                lastCheckpointId = "3",
                errorCode = ErrorCode.WORKER_UNAVAILABLE.code,
                completedAt = 1700001000
            )
        )

        every { solvedAcClient.fetchProblem(4) } returns SolvedAcProblemResponse(4, "P4", 1, emptyList())
        every { solvedAcClient.fetchProblem(5) } returns SolvedAcProblemResponse(5, "P5", 1, emptyList())
        every { problemRepository.upsertMetadata(any()) } just runs

        val retryJob = service.retryJob(sourceJobId, "admin", "127.0.0.1")

        assertThat(retryJob.jobType).isEqualTo(ProblemJobType.COLLECT_METADATA)
        assertThat(retryJob.range).isEqualTo(JobRange(4, 5))
        assertThat(retryJob.status).isEqualTo(JobStatus.COMPLETED)

        verify(exactly = 1) { solvedAcClient.fetchProblem(4) }
        verify(exactly = 1) { solvedAcClient.fetchProblem(5) }
        verify(exactly = 0) { solvedAcClient.fetchProblem(1) }
        verify(exactly = 0) { solvedAcClient.fetchProblem(2) }
        verify(exactly = 0) { solvedAcClient.fetchProblem(3) }
    }

    @Test
    @DisplayName("부분 실패한 완료 작업은 실패 문제만 다시 수집한다")
    fun `retry reprocesses only failed metadata items`() {
        var problemTwoAttempts = 0
        every { solvedAcClient.fetchProblem(any()) } answers {
            val problemId = firstArg<Int>()
            if (problemId == 2 && problemTwoAttempts++ == 0) {
                throw IllegalStateException("temporary failure")
            }
            SolvedAcProblemResponse(problemId, "P$problemId", 1, emptyList())
        }
        every { problemRepository.upsertMetadata(any()) } just runs

        val sourceJobId = service.collectMetadataAsync(1, 5, "admin", "127.0.0.1")
        val sourceJob = requireNotNull(service.getMetadataCollectJobStatus(sourceJobId))

        assertThat(sourceJob.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(sourceJob.failCount).isEqualTo(1)
        assertThat(sourceJob.lastCheckpointId).isEqualTo("5")

        val retryJob = service.retryJob(sourceJobId, "admin", "127.0.0.1")

        assertThat(retryJob.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(retryJob.totalCount).isEqualTo(1)
        assertThat(retryJob.range).isEqualTo(JobRange(2, 2))
        assertThat(retryJob.successCount).isEqualTo(1)
        assertThat(retryJob.failCount).isZero()
        verify(exactly = 2) { solvedAcClient.fetchProblem(2) }
        listOf(1, 3, 4, 5).forEach { problemId ->
            verify(exactly = 1) { solvedAcClient.fetchProblem(problemId) }
        }
    }

    @Test
    @DisplayName("상세 새로고침 대상은 숫자 문제 ID 순서로 처리한다")
    fun `detail refresh orders targets by numeric problem id`() {
        val problem1005 = sampleProblem("1005")
        val problem1001 = sampleProblem("1001")
        val problem1003 = sampleProblem("1003")
        every { problemRepository.findAll() } returns listOf(problem1005, problem1001, problem1003)
        every { bojCrawler.crawlProblemDetails(any()) } returns sampleDetails()
        every { problemRepository.updateDetails(any(), any()) } answers {
            sampleProblem(firstArg())
        }

        service.refreshDetailsBatchAsync(createdBy = "admin", ipAddress = "127.0.0.1")

        verifyOrder {
            bojCrawler.crawlProblemDetails("1001")
            bojCrawler.crawlProblemDetails("1003")
            bojCrawler.crawlProblemDetails("1005")
        }
    }

    @Test
    @DisplayName("실패 원장이 없는 이전 상세 작업은 체크포인트와 관계없이 현재 대상을 다시 처리한다")
    fun `legacy interrupted detail job retries all current targets in numeric order`() {
        val sourceJobId = "job-legacy-unsorted-details"
        val problem1005 = sampleProblem("1005")
        val problem1001 = sampleProblem("1001")
        val problem1003 = sampleProblem("1003")
        seedJob(
            sampleJob(sourceJobId).copy(
                jobType = ProblemJobType.REFRESH_DETAILS,
                status = JobStatus.CANCELLED,
                totalCount = 3,
                processedCount = 1,
                successCount = 1,
                progressPercentage = 33,
                range = JobRange(1001, 1005),
                lastCheckpointId = "1005",
                completedAt = 1700001000
            )
        )
        every {
            problemRepository.findAll()
        } returns listOf(problem1005, problem1001, problem1003)
        every { bojCrawler.crawlProblemDetails(any()) } returns sampleDetails()
        every { problemRepository.updateDetails(any(), any()) } answers {
            sampleProblem(firstArg())
        }

        val retryJob = service.retryJob(sourceJobId, "admin", "127.0.0.1")

        assertThat(retryJob.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(retryJob.totalCount).isEqualTo(3)
        verifyOrder {
            bojCrawler.crawlProblemDetails("1001")
            bojCrawler.crawlProblemDetails("1003")
            bojCrawler.crawlProblemDetails("1005")
        }
    }

    @Test
    @DisplayName("삭제된 실패 문제는 제외하고 남아 있는 상세 수집 실패 문제를 재시도한다")
    fun `detail retry skips deleted failed item and continues remaining failures`() {
        val sourceJobId = "job-deleted-failed-item"
        val remainingProblem = sampleProblem("1003")
        seedJob(
            sampleJob(sourceJobId).copy(
                jobType = ProblemJobType.COLLECT_DETAILS,
                status = JobStatus.COMPLETED,
                totalCount = 2,
                processedCount = 2,
                failCount = 2,
                progressPercentage = 100,
                range = null,
                lastCheckpointId = "1003",
                completedAt = 1700001000
            )
        )
        failureStore["$JOB_FAILURE_KEY_PREFIX$sourceJobId"] = mutableSetOf("1002", "1003")
        every { problemRepository.findByDescriptionHtmlIsNull() } returns emptyList()
        every {
            problemRepository.findAllById(setOf("1002", "1003"))
        } returns listOf(remainingProblem)
        every { bojCrawler.crawlProblemDetails("1003") } returns sampleDetails()
        every { problemRepository.updateDetails("1003", any()) } returns remainingProblem

        val retryJob = service.retryJob(sourceJobId, "admin", "127.0.0.1")

        assertThat(retryJob.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(retryJob.totalCount).isEqualTo(1)
        assertThat(retryJob.successCount).isEqualTo(1)
        verify(exactly = 1) { bojCrawler.crawlProblemDetails("1003") }
        verify(exactly = 0) { bojCrawler.crawlProblemDetails("1002") }
    }

    @Test
    @DisplayName("동시 취소 요청 경쟁 상황에서 1건만 성공하고 나머지는 terminal 충돌이 난다")
    fun `cancel race condition`() {
        val jobId = "job-race-1"
        seedJob(sampleJob(jobId).copy(status = JobStatus.RUNNING, startedAt = 1700000001))

        val startLatch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val outcomes = Collections.synchronizedList(mutableListOf<String>())

        repeat(2) {
            pool.submit {
                startLatch.await()
                try {
                    service.cancelJob(jobId, "admin", "127.0.0.1")
                    outcomes.add("OK")
                } catch (e: BusinessException) {
                    outcomes.add(e.errorCode.code)
                }
            }
        }

        startLatch.countDown()
        pool.shutdown()
        pool.awaitTermination(3, TimeUnit.SECONDS)

        assertThat(outcomes.count { it == "OK" }).isEqualTo(1)
        assertThat(outcomes.count { it == ErrorCode.JOB_ALREADY_TERMINAL.code }).isEqualTo(1)
    }

    @Test
    @DisplayName("작업 취소 CAS 충돌이 계속되면 재시도 가능한 409를 반환한다")
    fun `cancel returns conflict after CAS retries are exhausted`() {
        val jobId = "job-cas-conflict"
        seedJob(sampleJob(jobId).copy(status = JobStatus.RUNNING, startedAt = 1700000001))
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                match<List<String>> {
                    it == listOf(
                        "$JOB_KEY_PREFIX$jobId",
                        "$JOB_FAILURE_KEY_PREFIX$jobId"
                    )
                },
                *anyVararg()
            )
        } returns 0L

        val exception = assertThrows<BusinessException> {
            service.cancelJob(jobId, "admin", "127.0.0.1")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        assertThat(service.getJob(jobId)?.status).isEqualTo(JobStatus.RUNNING)
        verify(exactly = 4) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(
                    "$JOB_KEY_PREFIX$jobId",
                    "$JOB_FAILURE_KEY_PREFIX$jobId"
                ),
                *anyVararg()
            )
        }
    }

    @Test
    @DisplayName("동기 상세 수집은 현재 상세 필드만 부분 갱신한다")
    fun `collect details updates only detail fields`() {
        val problem = sampleProblem("1000")
        val details = sampleDetails()
        val detailsSlot = slot<ProblemDetailsUpdate>()
        every { problemRepository.findByDescriptionHtmlIsNull() } returns listOf(problem)
        every { bojCrawler.crawlProblemDetails(problem.id.value) } returns details
        every {
            problemRepository.updateDetails(problem.id.value, capture(detailsSlot))
        } returns problem.copy(descriptionHtml = details.descriptionHtml)

        service.collectDetailsBatch()

        assertThat(detailsSlot.captured.descriptionHtml).isEqualTo(details.descriptionHtml)
        assertThat(detailsSlot.captured.sampleInputs).isEqualTo(details.sampleInputs)
        assertThat(detailsSlot.captured.language).isNull()
        verify(exactly = 1) { problemRepository.updateDetails(problem.id.value, any()) }
        verify(exactly = 0) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("동기 상세 수집 중 문제가 삭제돼도 외부 요청 간격을 유지한다")
    fun `collect details preserves pacing when the target was deleted`() {
        val problem = sampleProblem("1006")
        every { problemRepository.findByDescriptionHtmlIsNull() } returns listOf(problem)
        every { bojCrawler.crawlProblemDetails(problem.id.value) } returns sampleDetails()
        every { problemRepository.updateDetails(problem.id.value, any()) } returns null

        service.collectDetailsBatch()

        verify(exactly = 1) { pacer.pauseDetails() }
        verify(exactly = 0) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("비동기 상세 수집은 부분 갱신 결과가 있을 때 성공 처리한다")
    fun `async detail collection uses partial update`() {
        val problem = sampleProblem("1001")
        val details = sampleDetails()
        every { problemRepository.findByDescriptionHtmlIsNull() } returns listOf(problem)
        every { bojCrawler.crawlProblemDetails(problem.id.value) } returns details
        every {
            problemRepository.updateDetails(problem.id.value, any())
        } returns problem.copy(descriptionHtml = details.descriptionHtml)

        val jobId = service.collectDetailsBatchAsync("admin", "127.0.0.1")
        val status = service.getDetailsCollectJobStatus(jobId)

        assertThat(status?.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(status?.successCount).isEqualTo(1)
        assertThat(status?.failCount).isZero()
        verify(exactly = 1) { problemRepository.updateDetails(problem.id.value, any()) }
        verify(exactly = 0) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("비동기 상세 수집 중 문제가 삭제되면 실패 건수로 기록한다")
    fun `async detail collection counts a deleted target as failure`() {
        val problem = sampleProblem("1004")
        val details = sampleDetails()
        every { problemRepository.findByDescriptionHtmlIsNull() } returns listOf(problem)
        every { bojCrawler.crawlProblemDetails(problem.id.value) } returns details
        every { problemRepository.updateDetails(problem.id.value, any()) } returns null

        val jobId = service.collectDetailsBatchAsync("admin", "127.0.0.1")
        val status = service.getDetailsCollectJobStatus(jobId)

        assertThat(status?.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(status?.successCount).isZero()
        assertThat(status?.failCount).isEqualTo(1)
        assertThat(failureStore["$JOB_FAILURE_KEY_PREFIX$jobId"].orEmpty()).containsExactly(problem.id.value)
        verify(exactly = 0) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("상세 새로고침은 감지한 언어와 상세 필드만 함께 갱신한다")
    fun `detail refresh updates details and detected language`() {
        val problem = sampleProblem("1002", title = "English problem title").copy(language = "ko")
        val details = sampleDetails(descriptionHtml = "<p>English problem description</p>")
        val detailsSlot = slot<ProblemDetailsUpdate>()
        every { problemRepository.findAll() } returns listOf(problem)
        every { bojCrawler.crawlProblemDetails(problem.id.value) } returns details
        every {
            problemRepository.updateDetails(problem.id.value, capture(detailsSlot))
        } returns problem.copy(
            descriptionHtml = details.descriptionHtml,
            language = "en"
        )

        val jobId = service.refreshDetailsBatchAsync(createdBy = "admin", ipAddress = "127.0.0.1")
        val status = service.getDetailsRefreshJobStatus(jobId)

        assertThat(status?.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(status?.successCount).isEqualTo(1)
        assertThat(detailsSlot.captured.language).isEqualTo("en")
        assertThat(detailsSlot.captured.descriptionHtml).isEqualTo(details.descriptionHtml)
        verify(exactly = 0) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("상세 새로고침 중 문제가 삭제되면 실패 문제를 원장에 기록한다")
    fun `detail refresh records a deleted target in failure ledger`() {
        val problem = sampleProblem("1007", title = "English problem title")
        every { problemRepository.findAll() } returns listOf(problem)
        every { bojCrawler.crawlProblemDetails(problem.id.value) } returns sampleDetails()
        every { problemRepository.updateDetails(problem.id.value, any()) } returns null

        val jobId = service.refreshDetailsBatchAsync(createdBy = "admin", ipAddress = "127.0.0.1")
        val status = service.getDetailsRefreshJobStatus(jobId)

        assertThat(status?.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(status?.successCount).isZero()
        assertThat(status?.failCount).isEqualTo(1)
        assertThat(failureStore["$JOB_FAILURE_KEY_PREFIX$jobId"].orEmpty()).containsExactly(problem.id.value)
    }

    @Test
    @DisplayName("언어 수집은 언어 필드만 부분 갱신한다")
    fun `language collection updates only language`() {
        val problem = sampleProblem("1003", title = "English problem title").copy(language = "ko")
        every { problemRepository.findAll() } returns listOf(problem)
        every { problemRepository.updateLanguage(problem.id.value, "en") } returns true

        val jobId = service.updateLanguageBatchAsync("admin", "127.0.0.1")
        val status = service.getLanguageUpdateJobStatus(jobId)

        assertThat(status?.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(status?.successCount).isEqualTo(1)
        verify(exactly = 1) { problemRepository.updateLanguage(problem.id.value, "en") }
        verify(exactly = 0) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("언어 수집 중 문제가 삭제되면 실패 건수로 기록한다")
    fun `language collection counts a deleted target as failure`() {
        val problem = sampleProblem("1005", title = "English problem title").copy(language = "ko")
        every { problemRepository.findAll() } returns listOf(problem)
        every { problemRepository.updateLanguage(problem.id.value, "en") } returns false

        val jobId = service.updateLanguageBatchAsync("admin", "127.0.0.1")
        val status = service.getLanguageUpdateJobStatus(jobId)

        assertThat(status?.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(status?.successCount).isZero()
        assertThat(status?.failCount).isEqualTo(1)
        assertThat(failureStore["$JOB_FAILURE_KEY_PREFIX$jobId"].orEmpty()).containsExactly(problem.id.value)
        verify(exactly = 0) { problemRepository.save(any<Problem>()) }
    }

    private fun createService(
        taskExecutor: Executor? = null,
        recoveryState: ProblemCollectorRecoveryState =
            ProblemCollectorRecoveryState(ProblemCollectorRecoveryProperties())
    ): ProblemCollectorService {
        return ProblemCollectorService(
            solvedAcClient = solvedAcClient,
            problemRepository = problemRepository,
            bojCrawler = bojCrawler,
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            adminAuditService = adminAuditService,
            taskExecutor = taskExecutor,
            pacer = pacer,
            recoveryState = recoveryState
        )
    }

    private fun sampleProblem(id: String, title: String = "기존 문제"): Problem {
        return Problem(
            id = ProblemId(id),
            title = title,
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$id"
        )
    }

    private fun sampleDetails(
        descriptionHtml: String = "<p>상세 설명</p>"
    ): ProblemDetails {
        return ProblemDetails(
            descriptionHtml = descriptionHtml,
            inputDescriptionHtml = "<p>입력 설명</p>",
            outputDescriptionHtml = "<p>출력 설명</p>",
            sampleInputs = listOf("1"),
            sampleOutputs = listOf("2")
        )
    }

    private fun seedJob(job: JobStatusUnifiedResponse) {
        valueStore["$JOB_KEY_PREFIX${job.jobId}"] = objectMapper.writeValueAsString(job)
        val set = zsetStore.computeIfAbsent(JOB_INDEX_KEY) { mutableMapOf() }
        set[job.jobId] = job.queuedAt.toDouble()
    }

    private fun sampleJob(jobId: String): JobStatusUnifiedResponse {
        return JobStatusUnifiedResponse(
            jobId = jobId,
            jobType = ProblemJobType.COLLECT_METADATA,
            status = JobStatus.PENDING,
            queuedAt = 1700000000,
            startedAt = null,
            lastHeartbeatAt = 1700000000,
            completedAt = null,
            totalCount = 10,
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            progressPercentage = 0,
            estimatedRemainingSeconds = null,
            queuePosition = null,
            range = JobRange(1, 10),
            lastCheckpointId = null,
            errorCode = null,
            errorMessage = null,
            createdBy = "admin"
        )
    }
}
