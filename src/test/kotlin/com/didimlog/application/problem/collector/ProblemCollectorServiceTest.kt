package com.didimlog.application.problem.collector

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.domain.Problem
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.valueobject.ProblemId
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
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.ZSetOperations

@DisplayName("ProblemCollectorService 테스트")
class ProblemCollectorServiceTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val problemRepository: ProblemRepository = mockk()
    private val bojCrawler: BojCrawler = mockk()
    private val redisTemplate: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private val zSetOps: ZSetOperations<String, String> = mockk()
    private val adminAuditService: AdminAuditService = mockk(relaxed = true)

    private val objectMapper = ObjectMapper().registerKotlinModule()

    private lateinit var service: ProblemCollectorService

    private val valueStore = ConcurrentHashMap<String, String>()
    private val zsetStore = ConcurrentHashMap<String, MutableMap<String, Double>>()

    companion object {
        private const val JOB_KEY_PREFIX = "problem:job:status:"
        private const val JOB_INDEX_KEY = "problem:job:index"
    }

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { redisTemplate.opsForZSet() } returns zSetOps

        every { valueOps.set(any(), any(), any<Duration>()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String>()
            valueStore[key] = value
        }
        every { valueOps.get(any()) } answers {
            valueStore[firstArg<String>()]
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

        service = ProblemCollectorService(
            solvedAcClient = solvedAcClient,
            problemRepository = problemRepository,
            bojCrawler = bojCrawler,
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            adminAuditService = adminAuditService
        )
    }

    @Test
    @DisplayName("메타데이터 수집 작업은 COMPLETED 상태로 종료되며 공통 상태 응답을 반환한다")
    fun `collect metadata status transition`() {
        every { solvedAcClient.fetchProblem(1) } returns SolvedAcProblemResponse(1, "A", 1, emptyList())
        every { solvedAcClient.fetchProblem(2) } returns SolvedAcProblemResponse(2, "B", 1, emptyList())
        every { problemRepository.findById(any()) } returns Optional.empty()
        every { problemRepository.save(any<Problem>()) } answers { firstArg() }

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
                successCount = 2,
                failCount = 1,
                lastCheckpointId = "3",
                errorCode = ErrorCode.WORKER_UNAVAILABLE.code,
                completedAt = 1700001000
            )
        )

        every { solvedAcClient.fetchProblem(4) } returns SolvedAcProblemResponse(4, "P4", 1, emptyList())
        every { solvedAcClient.fetchProblem(5) } returns SolvedAcProblemResponse(5, "P5", 1, emptyList())
        every { problemRepository.findById(any()) } returns Optional.empty()
        every { problemRepository.save(any<Problem>()) } answers { firstArg() }

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
