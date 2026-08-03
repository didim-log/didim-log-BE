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
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test", "portfolio-fixture")
@EnabledIfEnvironmentVariable(named = "CRAWLER_DETAILS_BENCHMARK_ENABLED", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("문제 상세 수집 순차-제한 병렬 벤치마크")
class ProblemCollectorDetailsParallelBenchmarkIntegrationTest {

    @Autowired
    private lateinit var problemRepository: ProblemRepository

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var mongoDatabaseFactory: MongoDatabaseFactory

    private val jobIdsToClean = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        assertThat(mongoDatabaseFactory.mongoDatabase.name).isEqualTo(BENCHMARK_DATABASE)
        problemRepository.deleteAll()
    }

    @AfterEach
    fun cleanUp() {
        if (
            !this::mongoDatabaseFactory.isInitialized ||
            mongoDatabaseFactory.mongoDatabase.name != BENCHMARK_DATABASE
        ) {
            return
        }

        problemRepository.deleteAll()
        jobIdsToClean.forEach(::deleteJobState)
    }

    @Test
    @DisplayName("동일한 3,400건을 순차와 K개 제한 병렬로 수집해 결과와 시간을 기록한다")
    fun compareSequentialAndBoundedParallelDetailsCollection() {
        val itemCount = benchmarkItemCount()
        val delayMillis = benchmarkDelayMillis()
        val concurrency = benchmarkConcurrency()

        warmUp(concurrency)

        val sequential = runScenario(
            mode = "sequential",
            itemCount = itemCount,
            delayMillis = delayMillis,
            concurrency = 1,
            parallel = false
        )
        val parallel = runScenario(
            mode = "parallel-k$concurrency",
            itemCount = itemCount,
            delayMillis = delayMillis,
            concurrency = concurrency,
            parallel = true
        )

        assertThat(parallel.functionalResultSha256)
            .describedAs("순차와 제한 병렬의 최종 문서가 같아야 한다")
            .isEqualTo(sequential.functionalResultSha256)

        writeScenarioResult(sequential)
        writeScenarioResult(parallel)
        writeComparisonResult(sequential, parallel)
    }

    private fun warmUp(concurrency: Int) {
        runScenario(
            mode = "warmup-sequential",
            itemCount = WARMUP_ITEM_COUNT,
            delayMillis = 0,
            concurrency = 1,
            parallel = false
        )
        runScenario(
            mode = "warmup-parallel",
            itemCount = WARMUP_ITEM_COUNT,
            delayMillis = 0,
            concurrency = concurrency,
            parallel = true
        )
    }

    private fun runScenario(
        mode: String,
        itemCount: Int,
        delayMillis: Long,
        concurrency: Int,
        parallel: Boolean
    ): DetailsBenchmarkMeasurement {
        resetScenarioData(itemCount)
        val crawler = FixedDelayBojCrawler(delayMillis)
        var crawlerExecutor: ThreadPoolTaskExecutor? = null

        val parallelFetcher = if (parallel) {
            crawlerExecutor = newCrawlerExecutor(concurrency)
            ParallelProblemDetailsFetcher(
                problemCrawlerExecutor = crawlerExecutor,
                properties = ProblemCollectorParallelProperties(
                    enabled = true,
                    maxConcurrency = concurrency
                )
            )
        } else {
            null
        }

        val service = ProblemCollectorService(
            solvedAcClient = mockk<SolvedAcClient>(relaxed = true),
            problemRepository = problemRepository,
            bojCrawler = crawler,
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            adminAuditService = mockk<AdminAuditService>(relaxed = true),
            taskExecutor = null,
            pacer = NoOpProblemCollectorPacer,
            recoveryState = ProblemCollectorRecoveryState(ProblemCollectorRecoveryProperties()),
            parallelProblemDetailsFetcher = parallelFetcher
        )

        val startedAt = System.nanoTime()
        val jobId = try {
            service.collectDetailsBatchAsync(
                createdBy = "crawler-details-benchmark",
                ipAddress = "127.0.0.1"
            )
        } finally {
            crawlerExecutor?.shutdown()
        }
        val elapsedNanos = System.nanoTime() - startedAt
        jobIdsToClean += jobId

        val job = requireNotNull(service.getDetailsCollectJobStatus(jobId)) {
            "완료된 상세 수집 작업 상태가 없습니다. jobId=$jobId"
        }
        val validation = validateStoredResult(itemCount, crawler, concurrency, parallel, job)

        return DetailsBenchmarkMeasurement(
            mode = mode,
            jobId = jobId,
            itemCount = itemCount,
            fixedDelayMillis = delayMillis,
            maxConcurrency = concurrency,
            elapsedNanos = elapsedNanos,
            itemsPerSecond = itemCount.toDouble() / (elapsedNanos.toDouble() / NANOS_PER_SECOND),
            finalDocumentCount = validation.finalDocumentCount,
            missingDocumentCount = validation.missingDocumentCount,
            duplicateDocumentCount = validation.duplicateDocumentCount,
            incompleteDetailsCount = validation.incompleteDetailsCount,
            jobStatus = job.status,
            jobProcessedCount = job.processedCount,
            jobSuccessCount = job.successCount,
            jobFailCount = job.failCount,
            jobCheckpoint = job.lastCheckpointId,
            crawlerCalls = crawler.calls.get(),
            duplicateCrawlerTargetCount = crawler.duplicateTargetCount(),
            maxInFlight = crawler.maxInFlight.get(),
            functionalResultSha256 = validation.functionalResultSha256
        )
    }

    private fun resetScenarioData(itemCount: Int) {
        problemRepository.deleteAll()
        problemRepository.saveAll((START_PROBLEM_ID until START_PROBLEM_ID + itemCount).map(::seedProblem))
        assertThat(problemRepository.count()).isEqualTo(itemCount.toLong())
    }

    private fun validateStoredResult(
        itemCount: Int,
        crawler: FixedDelayBojCrawler,
        concurrency: Int,
        parallel: Boolean,
        job: JobStatusUnifiedResponse
    ): StoredResultValidation {
        val expectedIds = (START_PROBLEM_ID until START_PROBLEM_ID + itemCount)
            .map(Int::toString)
        val problems = problemRepository.findAllById(expectedIds).sortedBy { it.id.value.toInt() }
        val storedIds = problems.map { it.id.value }.toSet()
        val missingIds = expectedIds.toSet() - storedIds
        val duplicateDocumentCount = duplicateDocumentCount()
        val incompleteDetailsCount = problems.count { problem ->
            problem.descriptionHtml == null ||
                problem.inputDescriptionHtml == null ||
                problem.outputDescriptionHtml == null ||
                problem.sampleInputs.isNullOrEmpty() ||
                problem.sampleOutputs.isNullOrEmpty()
        }

        assertThat(problemRepository.count()).isEqualTo(itemCount.toLong())
        assertThat(problems).hasSize(itemCount)
        assertThat(missingIds).isEmpty()
        assertThat(duplicateDocumentCount).isZero()
        assertThat(incompleteDetailsCount).isZero()
        assertThat(job.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(job.totalCount).isEqualTo(itemCount)
        assertThat(job.processedCount).isEqualTo(itemCount)
        assertThat(job.successCount).isEqualTo(itemCount)
        assertThat(job.failCount).isZero()
        assertThat(job.lastCheckpointId).isEqualTo((START_PROBLEM_ID + itemCount - 1).toString())
        assertThat(crawler.calls.get()).isEqualTo(itemCount)
        assertThat(crawler.distinctTargetCount()).isEqualTo(itemCount)
        assertThat(crawler.duplicateTargetCount()).isZero()
        assertThat(crawler.maxInFlight.get()).isBetween(1, concurrency)
        if (parallel && concurrency > 1 && itemCount > 1 && crawler.delayMillis > 0) {
            assertThat(crawler.maxInFlight.get())
                .describedAs("제한 병렬 경로에서 둘 이상의 fetch가 실제로 겹쳐야 한다")
                .isGreaterThan(1)
        }

        return StoredResultValidation(
            finalDocumentCount = problems.size,
            missingDocumentCount = missingIds.size,
            duplicateDocumentCount = duplicateDocumentCount,
            incompleteDetailsCount = incompleteDetailsCount,
            functionalResultSha256 = functionalResultSha256(problems)
        )
    }

    private fun duplicateDocumentCount(): Int {
        val pipeline = listOf(
            Document("${'$'}group", Document("_id", "${'$'}_id").append("count", Document("${'$'}sum", 1))),
            Document("${'$'}match", Document("count", Document("${'$'}gt", 1)))
        )
        return mongoTemplate.getCollection(PROBLEM_COLLECTION)
            .aggregate(pipeline)
            .sumOf { duplicate -> duplicate.getInteger("count") - 1 }
    }

    private fun functionalResultSha256(problems: List<Problem>): String {
        val canonicalResult = problems.map { problem ->
            linkedMapOf(
                "id" to problem.id.value,
                "title" to problem.title,
                "category" to problem.category.name,
                "difficulty" to problem.difficulty.name,
                "level" to problem.level,
                "url" to problem.url,
                "descriptionHtml" to problem.descriptionHtml,
                "inputDescriptionHtml" to problem.inputDescriptionHtml,
                "outputDescriptionHtml" to problem.outputDescriptionHtml,
                "sampleInputs" to problem.sampleInputs,
                "sampleOutputs" to problem.sampleOutputs,
                "tags" to problem.tags,
                "language" to problem.language
            )
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(objectMapper.writeValueAsBytes(canonicalResult))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun writeScenarioResult(measurement: DetailsBenchmarkMeasurement) {
        val outputFile = benchmarkOutputDirectory().resolve("${measurement.mode}.json")
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
            outputFile.toFile(),
            linkedMapOf(
                "schemaVersion" to 1,
                "mode" to measurement.mode,
                "measurementScope" to "local-fixed-delay",
                "externalNetworkUsed" to false,
                "commitSha" to benchmarkEnvironment("CRAWLER_DETAILS_BENCHMARK_COMMIT_SHA"),
                "gitDirty" to System.getenv("CRAWLER_DETAILS_BENCHMARK_GIT_DIRTY")?.toBooleanStrictOrNull(),
                "harnessSha256" to benchmarkEnvironment("CRAWLER_DETAILS_BENCHMARK_HARNESS_SHA256"),
                "mongoImage" to benchmarkEnvironment("CRAWLER_DETAILS_BENCHMARK_MONGO_IMAGE"),
                "redisImage" to benchmarkEnvironment("CRAWLER_DETAILS_BENCHMARK_REDIS_IMAGE"),
                "itemCount" to measurement.itemCount,
                "fixedDelayMillis" to measurement.fixedDelayMillis,
                "maxConcurrency" to measurement.maxConcurrency,
                "elapsedNanos" to measurement.elapsedNanos,
                "elapsedMillis" to measurement.elapsedNanos / NANOS_PER_MILLISECOND,
                "itemsPerSecond" to measurement.itemsPerSecond,
                "finalDocumentCount" to measurement.finalDocumentCount,
                "missingDocumentCount" to measurement.missingDocumentCount,
                "duplicateDocumentCount" to measurement.duplicateDocumentCount,
                "incompleteDetailsCount" to measurement.incompleteDetailsCount,
                "job" to linkedMapOf(
                    "id" to measurement.jobId,
                    "status" to measurement.jobStatus.name,
                    "processedCount" to measurement.jobProcessedCount,
                    "successCount" to measurement.jobSuccessCount,
                    "failCount" to measurement.jobFailCount,
                    "checkpoint" to measurement.jobCheckpoint
                ),
                "crawlerCalls" to measurement.crawlerCalls,
                "duplicateCrawlerTargetCount" to measurement.duplicateCrawlerTargetCount,
                "maxInFlight" to measurement.maxInFlight,
                "functionalResultSha256" to measurement.functionalResultSha256
            )
        )
        println("crawler details benchmark result=${outputFile.absolutePathString()}")
    }

    private fun writeComparisonResult(
        sequential: DetailsBenchmarkMeasurement,
        parallel: DetailsBenchmarkMeasurement
    ) {
        val speedup = sequential.elapsedNanos.toDouble() / parallel.elapsedNanos.toDouble()
        val reductionPercent =
            (sequential.elapsedNanos - parallel.elapsedNanos).toDouble() /
                sequential.elapsedNanos.toDouble() * 100.0
        val outputFile = benchmarkOutputDirectory().resolve("comparison.json")
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
            outputFile.toFile(),
            linkedMapOf(
                "schemaVersion" to 1,
                "measurementScope" to "local-fixed-delay",
                "externalNetworkUsed" to false,
                "itemCount" to sequential.itemCount,
                "fixedDelayMillis" to sequential.fixedDelayMillis,
                "parallelConcurrency" to parallel.maxConcurrency,
                "sequentialElapsedMillis" to sequential.elapsedNanos / NANOS_PER_MILLISECOND,
                "parallelElapsedMillis" to parallel.elapsedNanos / NANOS_PER_MILLISECOND,
                "speedup" to speedup,
                "elapsedReductionPercent" to reductionPercent,
                "resultHashesEqual" to
                    (sequential.functionalResultSha256 == parallel.functionalResultSha256),
                "missingDocumentCount" to
                    (sequential.missingDocumentCount + parallel.missingDocumentCount),
                "duplicateDocumentCount" to
                    (sequential.duplicateDocumentCount + parallel.duplicateDocumentCount),
                "note" to "BOJ 실측이 아닌 로컬 고정 지연 비교이므로 외부 서비스 수집 시간으로 해석하지 않는다."
            )
        )
        println("crawler details benchmark comparison=${outputFile.absolutePathString()}")
    }

    private fun benchmarkOutputDirectory(): Path {
        val outputDirectory = Path.of(
            System.getenv("CRAWLER_DETAILS_BENCHMARK_OUTPUT_DIR")
                ?: "build/reports/crawler-details-benchmark"
        ).toAbsolutePath().normalize()
        Files.createDirectories(outputDirectory)
        return outputDirectory
    }

    private fun deleteJobState(jobId: String) {
        redisTemplate.delete(
            listOf(
                "problem:job:status:$jobId",
                "problem:job:failures:$jobId",
                "problem:job:targets:$jobId",
                "problem:job:lease:$jobId"
            )
        )
        redisTemplate.opsForZSet().remove("problem:job:index", jobId)
    }

    private fun seedProblem(problemId: Int): Problem {
        val level = (problemId % 30) + 1
        return Problem(
            id = ProblemId(problemId.toString()),
            title = "benchmark-problem-$problemId",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.from(level),
            level = level,
            url = "https://benchmark.invalid/problem/$problemId",
            tags = listOf(ProblemCategory.IMPLEMENTATION.englishName)
        )
    }

    private fun newCrawlerExecutor(concurrency: Int): ThreadPoolTaskExecutor {
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = concurrency
            maxPoolSize = concurrency
            queueCapacity = concurrency * 2
            setThreadNamePrefix("details-benchmark-")
            initialize()
        }
    }

    private fun benchmarkItemCount(): Int {
        val value = System.getenv("CRAWLER_DETAILS_BENCHMARK_ITEM_COUNT")
            ?.toIntOrNull()
            ?: DEFAULT_ITEM_COUNT
        require(value in 1..DEFAULT_ITEM_COUNT) {
            "CRAWLER_DETAILS_BENCHMARK_ITEM_COUNT는 1..$DEFAULT_ITEM_COUNT 범위여야 합니다. value=$value"
        }
        return value
    }

    private fun benchmarkDelayMillis(): Long {
        val value = System.getenv("CRAWLER_DETAILS_BENCHMARK_DELAY_MILLIS")
            ?.toLongOrNull()
            ?: DEFAULT_DELAY_MILLIS
        require(value in 0L..MAX_DELAY_MILLIS) {
            "CRAWLER_DETAILS_BENCHMARK_DELAY_MILLIS는 0..$MAX_DELAY_MILLIS 범위여야 합니다. value=$value"
        }
        return value
    }

    private fun benchmarkConcurrency(): Int {
        val value = System.getenv("CRAWLER_DETAILS_BENCHMARK_CONCURRENCY")
            ?.toIntOrNull()
            ?: DEFAULT_CONCURRENCY
        require(value in 2..MAX_CONCURRENCY) {
            "CRAWLER_DETAILS_BENCHMARK_CONCURRENCY는 2..$MAX_CONCURRENCY 범위여야 합니다. value=$value"
        }
        return value
    }

    private fun benchmarkEnvironment(name: String): String =
        System.getenv(name) ?: "NOT_CAPTURED"

    private class FixedDelayBojCrawler(
        val delayMillis: Long
    ) : BojCrawler() {
        val calls = AtomicInteger()
        val maxInFlight = AtomicInteger()
        private val inFlight = AtomicInteger()
        private val targetCounts = ConcurrentHashMap<String, AtomicInteger>()

        override fun crawlProblemDetails(problemId: String): ProblemDetails {
            calls.incrementAndGet()
            targetCounts.computeIfAbsent(problemId) { AtomicInteger() }.incrementAndGet()
            val currentInFlight = inFlight.incrementAndGet()
            maxInFlight.getAndUpdate { previous -> maxOf(previous, currentInFlight) }
            return try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis)
                }
                ProblemDetails(
                    descriptionHtml = "<p>description-$problemId</p>",
                    inputDescriptionHtml = "<p>input-$problemId</p>",
                    outputDescriptionHtml = "<p>output-$problemId</p>",
                    sampleInputs = listOf("sample-input-$problemId"),
                    sampleOutputs = listOf("sample-output-$problemId")
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }

        fun distinctTargetCount(): Int = targetCounts.size

        fun duplicateTargetCount(): Int = targetCounts.values.sumOf { count ->
            (count.get() - 1).coerceAtLeast(0)
        }
    }

    private object NoOpProblemCollectorPacer : ProblemCollectorPacer {
        override fun pauseMetadata() = Unit

        override fun pauseDetails() = Unit
    }

    private data class StoredResultValidation(
        val finalDocumentCount: Int,
        val missingDocumentCount: Int,
        val duplicateDocumentCount: Int,
        val incompleteDetailsCount: Int,
        val functionalResultSha256: String
    )

    private data class DetailsBenchmarkMeasurement(
        val mode: String,
        val jobId: String,
        val itemCount: Int,
        val fixedDelayMillis: Long,
        val maxConcurrency: Int,
        val elapsedNanos: Long,
        val itemsPerSecond: Double,
        val finalDocumentCount: Int,
        val missingDocumentCount: Int,
        val duplicateDocumentCount: Int,
        val incompleteDetailsCount: Int,
        val jobStatus: JobStatus,
        val jobProcessedCount: Int,
        val jobSuccessCount: Int,
        val jobFailCount: Int,
        val jobCheckpoint: String?,
        val crawlerCalls: Int,
        val duplicateCrawlerTargetCount: Int,
        val maxInFlight: Int,
        val functionalResultSha256: String
    )

    companion object {
        private const val BENCHMARK_DATABASE = "didimlog-crawler-details-benchmark"
        private const val PROBLEM_COLLECTION = "problems"
        private const val START_PROBLEM_ID = 1000
        private const val DEFAULT_ITEM_COUNT = 3_400
        private const val WARMUP_ITEM_COUNT = 8
        private const val DEFAULT_DELAY_MILLIS = 10L
        private const val MAX_DELAY_MILLIS = 10_000L
        private const val DEFAULT_CONCURRENCY = 4
        private const val MAX_CONCURRENCY = 16
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
