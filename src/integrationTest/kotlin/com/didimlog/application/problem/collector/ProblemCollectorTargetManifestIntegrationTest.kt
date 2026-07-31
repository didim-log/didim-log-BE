package com.didimlog.application.problem.collector

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.solvedac.SolvedAcClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        "spring.data.redis.database=14"
    ]
)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("문제 수집 대상 manifest Redis 통합 테스트")
class ProblemCollectorTargetManifestIntegrationTest {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val solvedAcClient: SolvedAcClient = mockk()
    private val problemRepository: ProblemRepository = mockk()
    private val bojCrawler: BojCrawler = mockk()
    private val adminAuditService: AdminAuditService = mockk(relaxed = true)
    private val pacer: ProblemCollectorPacer = mockk(relaxed = true)
    private lateinit var executor: CaptureOnlyExecutor
    private lateinit var service: ProblemCollectorService

    @BeforeEach
    fun setUp() {
        clearJobKeys()
        executor = CaptureOnlyExecutor()
        service = ProblemCollectorService(
            solvedAcClient = solvedAcClient,
            problemRepository = problemRepository,
            bojCrawler = bojCrawler,
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            adminAuditService = adminAuditService,
            pacer = pacer,
            recoveryState = ProblemCollectorRecoveryState(ProblemCollectorRecoveryProperties()),
            taskExecutor = executor
        )
    }

    @AfterEach
    fun cleanUp() {
        clearJobKeys()
    }

    @Test
    fun `작업 생성은 범위와 명시 ID manifest를 순서대로 상태와 함께 저장한다`() {
        val detailsTargets = listOf(
            sampleProblem("1005"),
            sampleProblem("1001"),
            sampleProblem("1003")
        )
        val refreshTargets = listOf(
            sampleProblem("2005"),
            sampleProblem("2001"),
            sampleProblem("2003")
        )
        val languageTargets = listOf(
            sampleProblem("3005"),
            sampleProblem("3001"),
            sampleProblem("3003")
        )
        every { problemRepository.findByDescriptionHtmlIsNull() } returns detailsTargets
        every { problemRepository.findAll() } returnsMany listOf(refreshTargets, languageTargets)

        val metadataJobId = service.collectMetadataAsync(3, 5, "admin", "127.0.0.1")
        val detailsJobId = service.collectDetailsBatchAsync("admin", "127.0.0.1")
        val refreshJobId = service.refreshDetailsBatchAsync(
            start = 2001,
            end = 2005,
            createdBy = "admin",
            ipAddress = "127.0.0.1"
        )
        val languageJobId = service.updateLanguageBatchAsync("admin", "127.0.0.1")

        assertStoredManifest(
            jobId = metadataJobId,
            expectedType = ProblemJobType.COLLECT_METADATA,
            expectedExplicitIds = emptyList(),
            expectedRange = JobRange(3, 5),
            expectedCount = 3
        )
        assertStoredManifest(
            jobId = detailsJobId,
            expectedType = ProblemJobType.COLLECT_DETAILS,
            expectedExplicitIds = listOf("1001", "1003", "1005"),
            expectedRange = null,
            expectedCount = 3
        )
        assertStoredManifest(
            jobId = refreshJobId,
            expectedType = ProblemJobType.REFRESH_DETAILS,
            expectedExplicitIds = listOf("2001", "2003", "2005"),
            expectedRange = null,
            expectedCount = 3
        )
        assertStoredManifest(
            jobId = languageJobId,
            expectedType = ProblemJobType.UPDATE_LANGUAGE,
            expectedExplicitIds = listOf("3001", "3003", "3005"),
            expectedRange = null,
            expectedCount = 3
        )
        assertThat(executor.taskCount).isEqualTo(4)
    }

    @Test
    fun `index 자료형이 잘못되면 상태와 manifest를 부분 생성하지 않는다`() {
        redisTemplate.opsForValue().set(JOB_INDEX_KEY, "wrong-type")

        assertThrows<IllegalStateException> {
            service.collectMetadataAsync(1, 2, "admin", "127.0.0.1")
        }

        assertThat(redisTemplate.keys("$JOB_KEY_PREFIX*")).isEmpty()
        assertThat(redisTemplate.keys("$JOB_TARGET_KEY_PREFIX*")).isEmpty()
        assertThat(redisTemplate.opsForValue().get(JOB_INDEX_KEY)).isEqualTo("wrong-type")
        assertThat(executor.taskCount).isZero()
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ManifestCorruption::class)
    fun `참조가 있는 작업은 손상된 manifest 재시도를 거부한다`(
        corruption: ManifestCorruption
    ) {
        val fixture = createCancelledMetadataFixture()
        corruptManifest(fixture, corruption)
        val indexSizeBeforeRetry = redisTemplate.opsForZSet().zCard(JOB_INDEX_KEY)
        val taskCountBeforeRetry = executor.taskCount

        val exception = assertThrows<BusinessException> {
            service.retryJob(fixture.jobId, "admin", "127.0.0.1")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        assertThat(redisTemplate.opsForZSet().zCard(JOB_INDEX_KEY))
            .isEqualTo(indexSizeBeforeRetry)
        assertThat(executor.taskCount).isEqualTo(taskCountBeforeRetry)
    }

    @Test
    fun `상태 CAS는 manifest 원문을 바꾸지 않고 상태와 manifest TTL을 갱신한다`() {
        val jobId = service.collectMetadataAsync(10, 12, "admin", "127.0.0.1")
        val manifestRawBefore = requireNotNull(
            redisTemplate.opsForValue().get(targetKey(jobId))
        )
        redisTemplate.expire(jobKey(jobId), Duration.ofSeconds(SHORT_TTL_SECONDS))
        redisTemplate.expire(targetKey(jobId), Duration.ofSeconds(SHORT_TTL_SECONDS))

        val cancelled = service.cancelJob(jobId, "admin", "127.0.0.1")

        assertThat(cancelled.status).isEqualTo(JobStatus.CANCELLED)
        assertThat(redisTemplate.opsForValue().get(targetKey(jobId)))
            .isEqualTo(manifestRawBefore)
        assertThat(cancelled.targetManifest?.sha256).isEqualTo(sha256(manifestRawBefore))
        assertAlignedJobAndManifestTtl(jobId)
    }

    @Test
    fun `완료 상태의 처리 수가 manifest 대상 수보다 작으면 재시도를 거부한다`() {
        val fixture = createCancelledMetadataFixture()
        val inconsistent = fixture.status.copy(
            status = JobStatus.COMPLETED,
            processedCount = 1,
            successCount = 1,
            progressPercentage = 50,
            lastCheckpointId = "1",
            completedAt = 1_700_000_100
        )
        redisTemplate.opsForValue().set(
            jobKey(fixture.jobId),
            objectMapper.writeValueAsString(inconsistent),
            Duration.ofDays(1)
        )

        val exception = assertThrows<BusinessException> {
            service.retryJob(fixture.jobId, "admin", "127.0.0.1")
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        assertThat(executor.taskCount).isEqualTo(1)
    }

    private fun assertStoredManifest(
        jobId: String,
        expectedType: ProblemJobType,
        expectedExplicitIds: List<String>,
        expectedRange: JobRange?,
        expectedCount: Int
    ) {
        val status = readJob(jobId)
        val rawManifest = requireNotNull(
            redisTemplate.opsForValue().get(targetKey(jobId))
        )
        val manifest = objectMapper.readValue(
            rawManifest,
            ProblemJobTargetManifest::class.java
        )

        assertThat(status.status).isEqualTo(JobStatus.PENDING)
        assertThat(status.jobType).isEqualTo(expectedType)
        assertThat(status.totalCount).isEqualTo(expectedCount)
        assertThat(status.targetManifest?.schemaVersion)
            .isEqualTo(ProblemJobTargetManifest.CURRENT_VERSION)
        assertThat(status.targetManifest?.sha256).isEqualTo(sha256(rawManifest))
        assertThat(manifest.version).isEqualTo(ProblemJobTargetManifest.CURRENT_VERSION)
        assertThat(manifest.jobId).isEqualTo(jobId)
        assertThat(manifest.jobType).isEqualTo(expectedType)
        assertThat(manifest.explicitIds).containsExactlyElementsOf(expectedExplicitIds)
        assertThat(manifest.range).isEqualTo(expectedRange)
        assertThat(redisTemplate.opsForZSet().score(JOB_INDEX_KEY, jobId)).isNotNull
        assertAlignedJobAndManifestTtl(jobId)
    }

    private fun assertAlignedJobAndManifestTtl(jobId: String) {
        val statusTtl = redisTemplate.getExpire(jobKey(jobId), TimeUnit.SECONDS)
        val manifestTtl = redisTemplate.getExpire(targetKey(jobId), TimeUnit.SECONDS)

        assertThat(statusTtl)
            .isBetween(JOB_TTL_SECONDS - TTL_ASSERTION_TOLERANCE_SECONDS, JOB_TTL_SECONDS)
        assertThat(manifestTtl)
            .isBetween(JOB_TTL_SECONDS - TTL_ASSERTION_TOLERANCE_SECONDS, JOB_TTL_SECONDS)
        assertThat(statusTtl - manifestTtl).isBetween(-1L, 1L)
    }

    private fun createCancelledMetadataFixture(): ManifestFixture {
        val jobId = service.collectMetadataAsync(1, 2, "admin", "127.0.0.1")
        val status = service.cancelJob(jobId, "admin", "127.0.0.1")
        val manifestRaw = requireNotNull(
            redisTemplate.opsForValue().get(targetKey(jobId))
        )
        val manifest = objectMapper.readValue(
            manifestRaw,
            ProblemJobTargetManifest::class.java
        )
        return ManifestFixture(
            jobId = jobId,
            status = status,
            manifestRaw = manifestRaw,
            manifest = manifest
        )
    }

    private fun corruptManifest(
        fixture: ManifestFixture,
        corruption: ManifestCorruption
    ) {
        when (corruption) {
            ManifestCorruption.MISSING -> {
                redisTemplate.delete(targetKey(fixture.jobId))
            }

            ManifestCorruption.WRONG_TYPE -> {
                redisTemplate.delete(targetKey(fixture.jobId))
                redisTemplate.opsForList().rightPush(targetKey(fixture.jobId), "wrong-type")
            }

            ManifestCorruption.MALFORMED -> {
                writeManifestAndReference(fixture.status, "{malformed-json")
            }

            ManifestCorruption.HASH_MISMATCH -> {
                redisTemplate.opsForValue().set(
                    targetKey(fixture.jobId),
                    "${fixture.manifestRaw}\n",
                    Duration.ofDays(1)
                )
            }

            ManifestCorruption.SEMANTIC_COUNT_MISMATCH -> {
                val mismatched = fixture.manifest.copy(range = JobRange(1, 1))
                writeManifestAndReference(
                    fixture.status,
                    objectMapper.writeValueAsString(mismatched)
                )
            }
        }
    }

    private fun writeManifestAndReference(
        status: JobStatusUnifiedResponse,
        manifestRaw: String
    ) {
        val reference = requireNotNull(status.targetManifest)
        redisTemplate.opsForValue().set(
            targetKey(status.jobId),
            manifestRaw,
            Duration.ofDays(1)
        )
        redisTemplate.opsForValue().set(
            jobKey(status.jobId),
            objectMapper.writeValueAsString(
                status.copy(
                    targetManifest = reference.copy(sha256 = sha256(manifestRaw))
                )
            ),
            Duration.ofDays(1)
        )
    }

    private fun readJob(jobId: String): JobStatusUnifiedResponse {
        val rawJson = requireNotNull(redisTemplate.opsForValue().get(jobKey(jobId)))
        return objectMapper.readValue(rawJson, JobStatusUnifiedResponse::class.java)
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

    private fun clearJobKeys() {
        listOf(JOB_KEY_PREFIX, JOB_FAILURE_KEY_PREFIX, JOB_TARGET_KEY_PREFIX).forEach { prefix ->
            val keys = redisTemplate.keys("$prefix*")
            if (keys.isNotEmpty()) {
                redisTemplate.delete(keys)
            }
        }
        redisTemplate.delete(JOB_INDEX_KEY)
    }

    private fun jobKey(jobId: String): String = "$JOB_KEY_PREFIX$jobId"

    private fun targetKey(jobId: String): String = "$JOB_TARGET_KEY_PREFIX$jobId"

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    private data class ManifestFixture(
        val jobId: String,
        val status: JobStatusUnifiedResponse,
        val manifestRaw: String,
        val manifest: ProblemJobTargetManifest
    )

    private class CaptureOnlyExecutor : Executor {
        private val tasks = mutableListOf<Runnable>()

        val taskCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.add(command)
        }
    }

    enum class ManifestCorruption {
        MISSING,
        WRONG_TYPE,
        MALFORMED,
        HASH_MISMATCH,
        SEMANTIC_COUNT_MISMATCH
    }

    private companion object {
        const val JOB_KEY_PREFIX = "problem:job:status:"
        const val JOB_FAILURE_KEY_PREFIX = "problem:job:failures:"
        const val JOB_TARGET_KEY_PREFIX = "problem:job:targets:"
        const val JOB_INDEX_KEY = "problem:job:index"
        const val JOB_TTL_SECONDS = 86_400L
        const val SHORT_TTL_SECONDS = 60L
        const val TTL_ASSERTION_TOLERANCE_SECONDS = 5L
    }
}
