package com.didimlog.application.problem.collector

import com.didimlog.application.admin.AdminAuditService
import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcProblemResponse
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import com.didimlog.portfolio.PortfolioBojCrawler
import com.didimlog.portfolio.PortfolioSolvedAcClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import kotlin.io.path.absolutePathString
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test", "portfolio-fixture")
@Import(ProblemCollectorBaselineIntegrationTest.MongoCommandCountingConfiguration::class)
@EnabledIfEnvironmentVariable(named = "CRAWLER_BASELINE_ENABLED", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("문제 메타데이터 수집 baseline 통합 테스트")
class ProblemCollectorBaselineIntegrationTest {

    @Autowired
    private lateinit var problemRepository: ProblemRepository

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var solvedAcClient: SolvedAcClient

    @Autowired
    private lateinit var bojCrawler: BojCrawler

    @Autowired
    private lateinit var mongoCommandCounter: MongoCommandCounter

    @Autowired
    private lateinit var mongoDatabaseFactory: MongoDatabaseFactory

    private lateinit var countingSolvedAcClient: CountingSolvedAcClient
    private lateinit var service: ProblemCollectorService
    private var jobIdToClean: String? = null

    @BeforeEach
    fun setUp() {
        assertThat(solvedAcClient).isInstanceOf(PortfolioSolvedAcClient::class.java)
        assertThat(bojCrawler).isInstanceOf(PortfolioBojCrawler::class.java)
        assertThat(mongoDatabaseFactory.mongoDatabase.name).isEqualTo(BASELINE_DATABASE)

        countingSolvedAcClient = CountingSolvedAcClient(solvedAcClient)
        service = ProblemCollectorService(
            solvedAcClient = countingSolvedAcClient,
            problemRepository = problemRepository,
            bojCrawler = bojCrawler,
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            adminAuditService = mockk(relaxed = true),
            taskExecutor = null,
            pacer = NoOpProblemCollectorPacer
        )

        problemRepository.deleteAllById(PROBLEM_IDS)
        warmConnections()
        mongoCommandCounter.stopAndSnapshot()
    }

    @AfterEach
    fun cleanUp() {
        if (
            !this::mongoDatabaseFactory.isInitialized ||
            mongoDatabaseFactory.mongoDatabase.name != BASELINE_DATABASE
        ) {
            return
        }

        mongoCommandCounter.stopAndSnapshot()
        problemRepository.deleteAllById(PROBLEM_IDS)
        jobIdToClean?.let { jobId ->
            redisTemplate.delete("$JOB_KEY_PREFIX$jobId")
            redisTemplate.opsForZSet().remove(JOB_INDEX_KEY, jobId)
        }
    }

    @Test
    @DisplayName("cold 수집의 정확성, Mongo/Redis 명령 수와 처리량을 기록한다")
    fun metadataColdBaseline() {
        val measurement = measureMetadata("metadata-cold")

        assertMetadataDocuments(detailsExpected = false)
        assertCompletedJob(measurement.jobId)
        assertBaselineCommands(measurement)
        assertThat(countingSolvedAcClient.problemFetchCount.get()).isEqualTo(ITEM_COUNT)

        writeResult(measurement)
    }

    @Test
    @DisplayName("warm 수집은 기존 상세를 보존하며 명령 수와 처리량을 기록한다")
    fun metadataWarmBaseline() {
        problemRepository.saveAll(PROBLEM_IDS.map(::existingProblemWithDetails))

        val measurement = measureMetadata("metadata-warm")

        assertMetadataDocuments(detailsExpected = true)
        assertCompletedJob(measurement.jobId)
        assertBaselineCommands(measurement)
        assertThat(countingSolvedAcClient.problemFetchCount.get()).isEqualTo(ITEM_COUNT)

        writeResult(measurement)
    }

    private fun measureMetadata(scenario: String): BaselineMeasurement {
        countingSolvedAcClient.problemFetchCount.set(0)
        val redisBefore = redisCommandStats()
        mongoCommandCounter.start()

        val startedAt = System.nanoTime()
        val jobId = try {
            service.collectMetadataAsync(
                start = START_PROBLEM_ID,
                end = END_PROBLEM_ID,
                createdBy = "crawler-baseline",
                ipAddress = "127.0.0.1"
            )
        } finally {
            if (mongoCommandCounter.isActive()) {
                mongoCommandCounter.stop()
            }
        }
        val elapsedNanos = System.nanoTime() - startedAt
        jobIdToClean = jobId

        val mongoCommands = mongoCommandCounter.snapshot()
        val redisAfter = redisCommandStats()
        val redisCommands = commandDelta(redisBefore, redisAfter)

        return BaselineMeasurement(
            scenario = scenario,
            jobId = jobId,
            itemCount = ITEM_COUNT,
            elapsedNanos = elapsedNanos,
            itemsPerSecond = ITEM_COUNT.toDouble() / (elapsedNanos.toDouble() / NANOS_PER_SECOND),
            mongoCommands = mongoCommands,
            redisCommands = redisCommands,
            solvedAcCalls = countingSolvedAcClient.problemFetchCount.get()
        )
    }

    private fun assertMetadataDocuments(detailsExpected: Boolean) {
        val problems = problemRepository.findAllById(PROBLEM_IDS).associateBy { it.id.value }
        assertThat(problems).hasSize(ITEM_COUNT)

        EXPECTED_METADATA.forEach { (problemId, expected) ->
            val problem = problems.getValue(problemId)
            assertThat(problem.title).isEqualTo(expected.title)
            assertThat(problem.level).isEqualTo(expected.level)
            assertThat(problem.category).isEqualTo(expected.category)
            assertThat(problem.tags).containsExactlyElementsOf(expected.tags)

            if (detailsExpected) {
                assertThat(problem.descriptionHtml).isEqualTo(descriptionMarker(problemId))
                assertThat(problem.inputDescriptionHtml).isEqualTo(inputMarker(problemId))
                assertThat(problem.outputDescriptionHtml).isEqualTo(outputMarker(problemId))
                assertThat(problem.sampleInputs).containsExactly("input-$problemId")
                assertThat(problem.sampleOutputs).containsExactly("output-$problemId")
            } else {
                assertThat(problem.descriptionHtml).isNull()
                assertThat(problem.inputDescriptionHtml).isNull()
                assertThat(problem.outputDescriptionHtml).isNull()
                assertThat(problem.sampleInputs).isNull()
                assertThat(problem.sampleOutputs).isNull()
            }
        }
    }

    private fun assertCompletedJob(jobId: String) {
        val status = service.getMetadataCollectJobStatus(jobId)
        assertThat(status).isNotNull
        assertThat(status!!.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(status.totalCount).isEqualTo(ITEM_COUNT)
        assertThat(status.processedCount).isEqualTo(ITEM_COUNT)
        assertThat(status.successCount).isEqualTo(ITEM_COUNT)
        assertThat(status.failCount).isZero()
        assertThat(status.lastCheckpointId).isEqualTo(END_PROBLEM_ID.toString())
    }

    private fun assertBaselineCommands(measurement: BaselineMeasurement) {
        assertThat(measurement.mongoCommands)
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "find" to ITEM_COUNT.toLong(),
                    "update" to ITEM_COUNT.toLong()
                )
            )

        assertThat(measurement.redisCommands)
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "get" to (2L * ITEM_COUNT + 3L),
                    "setex" to (ITEM_COUNT + 3L),
                    "zadd" to (ITEM_COUNT + 3L)
                )
            )
    }

    private fun existingProblemWithDetails(problemId: String): Problem {
        return Problem(
            id = ProblemId(problemId),
            title = "stale-$problemId",
            category = ProblemCategory.UNKNOWN,
            difficulty = Tier.BRONZE,
            level = 1,
            url = "https://www.acmicpc.net/problem/$problemId",
            descriptionHtml = descriptionMarker(problemId),
            inputDescriptionHtml = inputMarker(problemId),
            outputDescriptionHtml = outputMarker(problemId),
            sampleInputs = listOf("input-$problemId"),
            sampleOutputs = listOf("output-$problemId"),
            tags = listOf(ProblemCategory.UNKNOWN.englishName)
        )
    }

    private fun warmConnections() {
        problemRepository.findById("__crawler-baseline-warmup__")
        redisTemplate.opsForValue().set(WARMUP_KEY, "1")
        redisTemplate.opsForValue().get(WARMUP_KEY)
        redisTemplate.delete(WARMUP_KEY)
        redisCommandStats()
    }

    private fun redisCommandStats(): Map<String, Long> {
        val properties = redisTemplate.execute(
            RedisCallback<Properties> { connection ->
                connection.serverCommands().info("commandstats")
            }
        ) ?: Properties()

        return properties.stringPropertyNames().associate { key ->
            key.removePrefix("cmdstat_") to parseCommandCalls(properties.getProperty(key))
        }
    }

    private fun parseCommandCalls(value: String): Long {
        return value.split(',')
            .first { part -> part.startsWith("calls=") }
            .substringAfter('=')
            .toLong()
    }

    private fun commandDelta(before: Map<String, Long>, after: Map<String, Long>): Map<String, Long> {
        return (before.keys + after.keys)
            .associateWith { command ->
                after.getOrDefault(command, 0L) - before.getOrDefault(command, 0L)
            }
            .filter { (command, count) -> command != "info" && count > 0L }
            .toSortedMap()
    }

    private fun writeResult(measurement: BaselineMeasurement) {
        val outputDirectory = Path.of(
            System.getenv("CRAWLER_BASELINE_OUTPUT_DIR")
                ?: "build/reports/crawler-baseline"
        ).toAbsolutePath().normalize()
        Files.createDirectories(outputDirectory)

        val outputFile = outputDirectory.resolve("${measurement.scenario}.json")
        val payload = linkedMapOf(
            "schemaVersion" to 1,
            "scenario" to measurement.scenario,
            "iteration" to (System.getenv("CRAWLER_BASELINE_ITERATION") ?: "NOT_CAPTURED"),
            "diagnosticOnly" to true,
            "sampleSizeWarning" to "6건 smoke의 itemsPerSecond는 JIT와 로컬 환경 노이즈가 커 성능 개선 주장에 사용하지 않는다.",
            "commitSha" to (System.getenv("CRAWLER_BASELINE_COMMIT_SHA") ?: "NOT_CAPTURED"),
            "gitDirty" to System.getenv("CRAWLER_BASELINE_GIT_DIRTY")?.toBooleanStrictOrNull(),
            "fixture" to "portfolio-fixture",
            "pacer" to "noop",
            "itemCount" to measurement.itemCount,
            "elapsedNanos" to measurement.elapsedNanos,
            "itemsPerSecond" to measurement.itemsPerSecond,
            "mongoCommands" to measurement.mongoCommands,
            "redisCommands" to measurement.redisCommands,
            "solvedAcCalls" to measurement.solvedAcCalls
        )
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), payload)
        println("crawler baseline result=${outputFile.absolutePathString()}")
    }

    private fun descriptionMarker(problemId: String): String = "<p>preserve-description-$problemId</p>"

    private fun inputMarker(problemId: String): String = "<p>preserve-input-$problemId</p>"

    private fun outputMarker(problemId: String): String = "<p>preserve-output-$problemId</p>"

    private data class ExpectedMetadata(
        val title: String,
        val level: Int,
        val category: ProblemCategory,
        val tags: List<String>
    )

    private data class BaselineMeasurement(
        val scenario: String,
        val jobId: String,
        val itemCount: Int,
        val elapsedNanos: Long,
        val itemsPerSecond: Double,
        val mongoCommands: Map<String, Long>,
        val redisCommands: Map<String, Long>,
        val solvedAcCalls: Int
    )

    private class CountingSolvedAcClient(
        private val delegate: SolvedAcClient
    ) : SolvedAcClient {
        val problemFetchCount = AtomicInteger()

        override fun fetchProblem(problemId: Int): SolvedAcProblemResponse {
            problemFetchCount.incrementAndGet()
            return delegate.fetchProblem(problemId)
        }

        override fun fetchUser(bojId: BojId): SolvedAcUserResponse = delegate.fetchUser(bojId)
    }

    private object NoOpProblemCollectorPacer : ProblemCollectorPacer {
        override fun pauseMetadata() = Unit

        override fun pauseDetails() = Unit
    }

    @TestConfiguration(proxyBeanMethods = false)
    class MongoCommandCountingConfiguration {
        @Bean
        fun mongoCommandCounter(): MongoCommandCounter = MongoCommandCounter()

        @Bean
        fun crawlerBaselineMongoCustomizer(
            counter: MongoCommandCounter
        ): MongoClientSettingsBuilderCustomizer {
            return MongoClientSettingsBuilderCustomizer { builder ->
                builder.addCommandListener(counter)
            }
        }
    }

    class MongoCommandCounter : CommandListener {
        private val active = AtomicBoolean(false)
        private val counts = ConcurrentHashMap<String, LongAdder>()

        override fun commandStarted(event: CommandStartedEvent) {
            if (!active.get() || event.databaseName != BASELINE_DATABASE) {
                return
            }

            val collection = (event.command[event.commandName] as? BsonString)?.value
                ?: (event.command["collection"] as? BsonString)?.value
            if (collection != PROBLEM_COLLECTION) {
                return
            }

            counts.computeIfAbsent(event.commandName.lowercase()) { LongAdder() }.increment()
        }

        fun start() {
            counts.clear()
            active.set(true)
        }

        fun stop() {
            active.set(false)
        }

        fun isActive(): Boolean = active.get()

        fun snapshot(): Map<String, Long> {
            return counts.mapValues { (_, count) -> count.sum() }.toSortedMap()
        }

        fun stopAndSnapshot(): Map<String, Long> {
            stop()
            return snapshot()
        }
    }

    companion object {
        private const val START_PROBLEM_ID = 1000
        private const val END_PROBLEM_ID = 1005
        private const val ITEM_COUNT = END_PROBLEM_ID - START_PROBLEM_ID + 1
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val JOB_KEY_PREFIX = "problem:job:status:"
        private const val JOB_INDEX_KEY = "problem:job:index"
        private const val WARMUP_KEY = "crawler:baseline:warmup"
        private const val BASELINE_DATABASE = "didimlog-crawler-baseline"
        private const val PROBLEM_COLLECTION = "problems"

        private val PROBLEM_IDS = (START_PROBLEM_ID..END_PROBLEM_ID).map(Int::toString)

        private val EXPECTED_METADATA = mapOf(
            "1000" to ExpectedMetadata("A+B", 1, ProblemCategory.ARITHMETIC, listOf("Arithmetic")),
            "1001" to ExpectedMetadata("A-B", 1, ProblemCategory.ARITHMETIC, listOf("Arithmetic")),
            "1002" to ExpectedMetadata("터렛", 7, ProblemCategory.GEOMETRY, listOf("Geometry")),
            "1003" to ExpectedMetadata(
                "피보나치 함수",
                9,
                ProblemCategory.DP,
                listOf("Dynamic Programming", "Mathematics")
            ),
            "1004" to ExpectedMetadata(
                "어린 왕자",
                10,
                ProblemCategory.GEOMETRY,
                listOf("Geometry", "Mathematics")
            ),
            "1005" to ExpectedMetadata(
                "ACM Craft",
                13,
                ProblemCategory.GRAPH_THEORY,
                listOf("Graph Theory", "Topological Sorting", "Dynamic Programming")
            )
        )
    }
}
