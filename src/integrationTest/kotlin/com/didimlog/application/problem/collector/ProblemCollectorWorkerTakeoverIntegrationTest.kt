package com.didimlog.application.problem.collector

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.valueobject.ProblemId
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
import io.mockk.verify
import io.mockk.verifyOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest
import org.springframework.data.redis.core.StringRedisTemplate

@DataRedisTest(
    properties = [
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=\${TEST_REDIS_PORT:6379}",
        "spring.data.redis.database=\${TEST_REDIS_WORKER_TAKEOVER_DATABASE:9}"
    ]
)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("문제 수집 worker 자동 인계 통합 테스트")
class ProblemCollectorWorkerTakeoverIntegrationTest {

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
    @DisplayName("실행기 거부로 대기 중인 작업을 같은 jobId와 manifest로 다시 제출한다")
    fun `scanner resubmits a rejected pending job without creating a child`() {
        val creator = createService(
            ownerId = "owner-rejected",
            taskExecutor = RejectingExecutor
        )
        val scannerService = createService(ownerId = "owner-scanner")
        every { solvedAcClient.fetchProblem(any()) } answers {
            problemResponse(firstArg())
        }
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs

        val jobId = creator.collectMetadataAsync(1, 2, "admin", "127.0.0.1")
        val pending = readJob(jobId)
        val manifestRaw = requireNotNull(
            redisTemplate.opsForValue().get(targetKey(jobId))
        )

        assertThat(pending.status).isEqualTo(JobStatus.PENDING)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()

        val submittedCount = scannerService.submitRecoverableJobs()

        val completed = readJob(jobId)
        assertThat(submittedCount).isEqualTo(1)
        assertThat(completed.jobId).isEqualTo(jobId)
        assertThat(completed.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(completed.processedCount).isEqualTo(2)
        assertThat(completed.successCount).isEqualTo(2)
        assertThat(completed.targetManifest).isEqualTo(pending.targetManifest)
        assertThat(redisTemplate.opsForValue().get(targetKey(jobId)))
            .isEqualTo(manifestRaw)
        assertThat(redisTemplate.opsForZSet().range(JOB_INDEX_KEY, 0, -1))
            .containsExactly(jobId)
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verifyOrder {
            solvedAcClient.fetchProblem(1)
            solvedAcClient.fetchProblem(2)
        }
    }

    @Test
    @DisplayName("만료된 메타데이터 작업은 기존 진행 상태를 보존하고 hybrid manifest suffix만 처리한다")
    fun `scanner takes over an expired metadata job from the exact suffix`() {
        val jobId = "job-expired-metadata"
        val startedAt = 1_700_000_100L
        val oldAttempt = ProblemJobWorkerAttempt(
            ownerId = "owner-old",
            attemptId = "attempt-old",
            attemptNumber = 7
        )
        val manifest = ProblemJobTargetManifest(
            version = ProblemJobTargetManifest.CURRENT_VERSION,
            jobId = jobId,
            jobType = ProblemJobType.COLLECT_METADATA,
            explicitIds = listOf("2"),
            range = JobRange(4, 6)
        )
        val stored = persistRunningJob(
            jobId = jobId,
            type = ProblemJobType.COLLECT_METADATA,
            manifest = manifest,
            startedAt = startedAt,
            totalCount = 4,
            processedCount = 2,
            successCount = 1,
            failCount = 1,
            checkpointId = "4",
            attempt = oldAttempt,
            failedProblemIds = setOf("2"),
            responseRange = JobRange(2, 6),
            lease = oldAttempt
        )
        val manifestRaw = requireNotNull(
            redisTemplate.opsForValue().get(targetKey(jobId))
        )
        assertThat(redisTemplate.delete(leaseKey(jobId))).isTrue()
        every { solvedAcClient.fetchProblem(any()) } answers {
            problemResponse(firstArg())
        }
        every { problemRepository.upsertMetadata(any<Problem>()) } just runs
        val scannerService = createService(ownerId = "owner-new")

        val submittedCount = scannerService.submitRecoverableJobs()

        val completed = readJob(jobId)
        assertThat(submittedCount).isEqualTo(1)
        assertThat(completed.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(completed.startedAt).isEqualTo(startedAt)
        assertThat(completed.processedCount).isEqualTo(4)
        assertThat(completed.successCount).isEqualTo(3)
        assertThat(completed.failCount).isEqualTo(1)
        assertThat(completed.lastCheckpointId).isEqualTo("6")
        assertThat(completed.workerAttempt?.ownerId).isEqualTo("owner-new")
        assertThat(completed.workerAttempt?.attemptNumber)
            .isEqualTo(oldAttempt.attemptNumber + 1)
        assertThat(completed.workerAttempt?.attemptId)
            .isNotEqualTo(oldAttempt.attemptId)
        assertThat(completed.targetManifest).isEqualTo(stored.targetManifest)
        assertThat(redisTemplate.opsForValue().get(targetKey(jobId)))
            .isEqualTo(manifestRaw)
        assertThat(redisTemplate.opsForSet().members(failureKey(jobId)))
            .containsExactly("2")
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verifyOrder {
            solvedAcClient.fetchProblem(5)
            solvedAcClient.fetchProblem(6)
        }
        verify(exactly = 0) { solvedAcClient.fetchProblem(2) }
        verify(exactly = 0) { solvedAcClient.fetchProblem(4) }
    }

    @Test
    @DisplayName("유효한 lease가 있는 실행 중 작업은 제출하거나 변경하지 않는다")
    fun `scanner leaves a running job with a valid lease untouched`() {
        val jobId = "job-live-worker"
        val attempt = ProblemJobWorkerAttempt(
            ownerId = "owner-live",
            attemptId = "attempt-live",
            attemptNumber = 3
        )
        val manifest = ProblemJobTargetManifest(
            version = ProblemJobTargetManifest.CURRENT_VERSION,
            jobId = jobId,
            jobType = ProblemJobType.COLLECT_METADATA,
            range = JobRange(1, 2)
        )
        persistRunningJob(
            jobId = jobId,
            type = ProblemJobType.COLLECT_METADATA,
            manifest = manifest,
            startedAt = 1_700_000_200L,
            totalCount = 2,
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            checkpointId = null,
            attempt = attempt,
            lease = attempt
        )
        val statusRaw = requireNotNull(
            redisTemplate.opsForValue().get(jobKey(jobId))
        )
        val leaseRaw = requireNotNull(
            redisTemplate.opsForValue().get(leaseKey(jobId))
        )
        val leaseTtlBeforeScan = redisTemplate.getExpire(
            leaseKey(jobId),
            TimeUnit.MILLISECONDS
        )
        val executor = CaptureOnlyExecutor()
        val scannerService = createService(
            ownerId = "owner-scanner",
            taskExecutor = executor
        )

        val submittedCount = scannerService.submitRecoverableJobs()

        assertThat(submittedCount).isZero()
        assertThat(executor.taskCount).isZero()
        assertThat(redisTemplate.opsForValue().get(jobKey(jobId)))
            .isEqualTo(statusRaw)
        assertThat(redisTemplate.opsForValue().get(leaseKey(jobId)))
            .isEqualTo(leaseRaw)
        val leaseTtlAfterScan = redisTemplate.getExpire(
            leaseKey(jobId),
            TimeUnit.MILLISECONDS
        )
        assertThat(leaseTtlAfterScan)
            .isBetween(leaseTtlBeforeScan - 1_000L, leaseTtlBeforeScan)
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }
        verify(exactly = 0) { problemRepository.upsertMetadata(any<Problem>()) }
    }

    @Test
    @DisplayName("비메타데이터 작업은 manifest 순서를 복원하고 삭제된 ID를 실패로 완료한다")
    fun `scanner restores nonmetadata suffix order and records a deleted target`() {
        val jobId = "job-expired-details"
        val oldAttempt = ProblemJobWorkerAttempt(
            ownerId = "owner-old",
            attemptId = "attempt-old-details",
            attemptNumber = 2
        )
        val manifest = ProblemJobTargetManifest(
            version = ProblemJobTargetManifest.CURRENT_VERSION,
            jobId = jobId,
            jobType = ProblemJobType.COLLECT_DETAILS,
            explicitIds = listOf("1001", "1003", "1005", "1007")
        )
        persistRunningJob(
            jobId = jobId,
            type = ProblemJobType.COLLECT_DETAILS,
            manifest = manifest,
            startedAt = 1_700_000_300L,
            totalCount = 4,
            processedCount = 1,
            successCount = 1,
            failCount = 0,
            checkpointId = "1001",
            attempt = oldAttempt
        )
        val problem1003 = sampleProblem("1003")
        val problem1007 = sampleProblem("1007")
        every {
            problemRepository.findAllById(setOf("1003", "1005", "1007"))
        } returns listOf(problem1007, problem1003)
        every { bojCrawler.crawlProblemDetails(any()) } returns sampleDetails()
        every { problemRepository.updateDetails(any(), any()) } answers {
            sampleProblem(firstArg())
        }
        val scannerService = createService(ownerId = "owner-new-details")

        val submittedCount = scannerService.submitRecoverableJobs()

        val completed = readJob(jobId)
        assertThat(submittedCount).isEqualTo(1)
        assertThat(completed.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(completed.processedCount).isEqualTo(4)
        assertThat(completed.successCount).isEqualTo(3)
        assertThat(completed.failCount).isEqualTo(1)
        assertThat(completed.lastCheckpointId).isEqualTo("1007")
        assertThat(completed.workerAttempt?.attemptNumber)
            .isEqualTo(oldAttempt.attemptNumber + 1)
        assertThat(redisTemplate.opsForSet().members(failureKey(jobId)))
            .containsExactly("1005")
        assertThat(redisTemplate.hasKey(leaseKey(jobId))).isFalse()
        verify(exactly = 1) {
            problemRepository.findAllById(setOf("1003", "1005", "1007"))
        }
        verifyOrder {
            bojCrawler.crawlProblemDetails("1003")
            bojCrawler.crawlProblemDetails("1007")
        }
        verify(exactly = 0) { bojCrawler.crawlProblemDetails("1001") }
        verify(exactly = 0) { bojCrawler.crawlProblemDetails("1005") }
        verify(exactly = 0) { problemRepository.findAll() }
    }

    @Test
    @DisplayName("상세 새로고침은 manifest의 처리 완료 prefix를 건너뛰고 이어서 실행한다")
    fun `scanner resumes a refresh job from its manifest suffix`() {
        val jobId = "job-expired-refresh"
        val oldAttempt = ProblemJobWorkerAttempt(
            ownerId = "owner-old",
            attemptId = "attempt-old-refresh",
            attemptNumber = 4
        )
        val manifest = ProblemJobTargetManifest(
            version = ProblemJobTargetManifest.CURRENT_VERSION,
            jobId = jobId,
            jobType = ProblemJobType.REFRESH_DETAILS,
            explicitIds = listOf("1001", "1003", "1005")
        )
        persistRunningJob(
            jobId = jobId,
            type = ProblemJobType.REFRESH_DETAILS,
            manifest = manifest,
            startedAt = 1_700_000_400L,
            totalCount = 3,
            processedCount = 1,
            successCount = 1,
            failCount = 0,
            checkpointId = "1001",
            attempt = oldAttempt
        )
        val problem1003 = sampleProblem("1003")
        val problem1005 = sampleProblem("1005")
        every {
            problemRepository.findAllById(setOf("1003", "1005"))
        } returns listOf(problem1005, problem1003)
        every { bojCrawler.crawlProblemDetails(any()) } returns sampleDetails()
        every { problemRepository.updateDetails(any(), any()) } answers {
            sampleProblem(firstArg())
        }
        val scannerService = createService(ownerId = "owner-new-refresh")

        val submittedCount = scannerService.submitRecoverableJobs()

        val completed = readJob(jobId)
        assertThat(submittedCount).isEqualTo(1)
        assertThat(completed.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(completed.processedCount).isEqualTo(3)
        assertThat(completed.successCount).isEqualTo(3)
        assertThat(completed.failCount).isZero()
        assertThat(completed.lastCheckpointId).isEqualTo("1005")
        assertThat(completed.workerAttempt?.attemptNumber)
            .isEqualTo(oldAttempt.attemptNumber + 1)
        verifyOrder {
            bojCrawler.crawlProblemDetails("1003")
            bojCrawler.crawlProblemDetails("1005")
        }
        verify(exactly = 0) { bojCrawler.crawlProblemDetails("1001") }
        verify(exactly = 0) { problemRepository.findAll() }
    }

    @Test
    @DisplayName("언어 갱신은 manifest 순서의 처리되지 않은 대상만 이어서 실행한다")
    fun `scanner resumes a language job from its manifest suffix`() {
        val jobId = "job-expired-language"
        val oldAttempt = ProblemJobWorkerAttempt(
            ownerId = "owner-old",
            attemptId = "attempt-old-language",
            attemptNumber = 5
        )
        val manifest = ProblemJobTargetManifest(
            version = ProblemJobTargetManifest.CURRENT_VERSION,
            jobId = jobId,
            jobType = ProblemJobType.UPDATE_LANGUAGE,
            explicitIds = listOf("1001", "1003", "1005")
        )
        persistRunningJob(
            jobId = jobId,
            type = ProblemJobType.UPDATE_LANGUAGE,
            manifest = manifest,
            startedAt = 1_700_000_500L,
            totalCount = 3,
            processedCount = 1,
            successCount = 1,
            failCount = 0,
            checkpointId = "1001",
            attempt = oldAttempt
        )
        val problem1003 = sampleProblem("1003")
            .copy(title = "English problem 1003", language = "ko")
        val problem1005 = sampleProblem("1005")
            .copy(title = "English problem 1005", language = "ko")
        every {
            problemRepository.findAllById(setOf("1003", "1005"))
        } returns listOf(problem1005, problem1003)
        every { problemRepository.updateLanguage(any(), "en") } returns true
        val scannerService = createService(ownerId = "owner-new-language")

        val submittedCount = scannerService.submitRecoverableJobs()

        val completed = readJob(jobId)
        assertThat(submittedCount).isEqualTo(1)
        assertThat(completed.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(completed.processedCount).isEqualTo(3)
        assertThat(completed.successCount).isEqualTo(3)
        assertThat(completed.failCount).isZero()
        assertThat(completed.lastCheckpointId).isEqualTo("1005")
        assertThat(completed.workerAttempt?.attemptNumber)
            .isEqualTo(oldAttempt.attemptNumber + 1)
        verifyOrder {
            problemRepository.updateLanguage("1003", "en")
            problemRepository.updateLanguage("1005", "en")
        }
        verify(exactly = 0) { problemRepository.updateLanguage("1001", any()) }
        verify(exactly = 0) { problemRepository.findAll() }
    }

    private fun createService(
        ownerId: String,
        taskExecutor: Executor? = null
    ): ProblemCollectorService {
        val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
        heartbeatExecutors.add(heartbeatExecutor)
        return ProblemCollectorService(
            solvedAcClient = solvedAcClient,
            problemRepository = problemRepository,
            bojCrawler = bojCrawler,
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
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

    private fun persistRunningJob(
        jobId: String,
        type: ProblemJobType,
        manifest: ProblemJobTargetManifest,
        startedAt: Long,
        totalCount: Int,
        processedCount: Int,
        successCount: Int,
        failCount: Int,
        checkpointId: String?,
        attempt: ProblemJobWorkerAttempt,
        failedProblemIds: Set<String> = emptySet(),
        responseRange: JobRange? = null,
        lease: ProblemJobWorkerAttempt? = null
    ): JobStatusUnifiedResponse {
        val manifestRaw = objectMapper.writeValueAsString(manifest)
        val stored = JobStatusUnifiedResponse(
            jobId = jobId,
            jobType = type,
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
            range = responseRange,
            lastCheckpointId = checkpointId,
            errorCode = null,
            errorMessage = null,
            createdBy = "admin",
            targetManifest = ProblemJobTargetManifestReference(
                schemaVersion = manifest.version,
                sha256 = sha256(manifestRaw)
            ),
            workerAttempt = attempt
        )
        redisTemplate.opsForValue().set(
            jobKey(jobId),
            objectMapper.writeValueAsString(stored),
            Duration.ofDays(1)
        )
        redisTemplate.opsForValue().set(
            targetKey(jobId),
            manifestRaw,
            Duration.ofDays(1)
        )
        redisTemplate.opsForZSet().add(
            JOB_INDEX_KEY,
            jobId,
            stored.queuedAt.toDouble()
        )
        if (failedProblemIds.isNotEmpty()) {
            redisTemplate.opsForSet().add(
                failureKey(jobId),
                *failedProblemIds.toTypedArray()
            )
            redisTemplate.expire(failureKey(jobId), Duration.ofDays(1))
        }
        if (lease != null) {
            redisTemplate.opsForValue().set(
                leaseKey(jobId),
                objectMapper.writeValueAsString(lease),
                Duration.ofSeconds(30)
            )
        }
        return stored
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

    private fun sampleProblem(id: String): Problem {
        return Problem(
            id = ProblemId(id),
            title = "문제 $id",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 1,
            url = "https://www.acmicpc.net/problem/$id"
        )
    }

    private fun sampleDetails(): ProblemDetails {
        return ProblemDetails(
            descriptionHtml = "<p>상세 설명</p>",
            inputDescriptionHtml = "<p>입력 설명</p>",
            outputDescriptionHtml = "<p>출력 설명</p>",
            sampleInputs = listOf("1"),
            sampleOutputs = listOf("2")
        )
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
        var taskCount: Int = 0
            private set

        override fun execute(command: Runnable) {
            taskCount++
        }
    }

    private object RejectingExecutor : Executor {
        override fun execute(command: Runnable) {
            throw RejectedExecutionException("rejected for test")
        }
    }

    private companion object {
        const val JOB_KEY_PREFIX = "problem:job:status:"
        const val JOB_FAILURE_KEY_PREFIX = "problem:job:failures:"
        const val JOB_TARGET_KEY_PREFIX = "problem:job:targets:"
        const val JOB_LEASE_KEY_PREFIX = "problem:job:lease:"
        const val JOB_INDEX_KEY = "problem:job:index"
    }
}
