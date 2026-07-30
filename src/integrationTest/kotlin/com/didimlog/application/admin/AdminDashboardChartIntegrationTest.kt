package com.didimlog.application.admin

import com.didimlog.domain.Retrospective
import com.didimlog.domain.Solution
import com.didimlog.domain.Solutions
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.test.context.ActiveProfiles

@DataMongoTest
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("관리자 대시보드 차트 Mongo 집계 통합 테스트")
@Import(
    AdminDashboardService::class,
    AdminDashboardChartService::class,
    AdminDashboardChartMongoCommandConfiguration::class
)
class AdminDashboardChartIntegrationTest {

    @Autowired
    private lateinit var adminDashboardService: AdminDashboardService

    @Autowired
    private lateinit var adminDashboardChartService: AdminDashboardChartService

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var retrospectiveRepository: RetrospectiveRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var commandRecorder: AdminDashboardChartMongoCommandRecorder

    @BeforeEach
    fun setUp() {
        mongoTemplate.db.drop()
        seedFixture()
        commandRecorder.reset()
    }

    @AfterEach
    fun tearDown() {
        commandRecorder.reset()
        mongoTemplate.db.drop()
    }

    @Test
    fun `해결 문제 카드는 실제 저장 경로에서 성공한 고유 문제를 센다`() {
        val rawStudent = requireNotNull(
            mongoTemplate.getCollection(STUDENT_COLLECTION)
                .find(org.bson.Document("_id", FIRST_STUDENT_ID))
                .first()
        )

        assertThat(rawStudent.get("solutions", org.bson.Document::class.java))
            .containsKey("items")

        val stats = adminDashboardService.getDashboardStats()

        assertThat(stats.totalSolvedProblems).isEqualTo(4L)
        ChartPeriod.entries.forEach { period ->
            assertThat(chart(ChartDataType.SOLUTION, period).last().value)
                .describedAs("$period 해결 문제 차트 최종 누적값")
                .isEqualTo(stats.totalSolvedProblems)
        }
    }

    @Test
    fun `해결 문제 차트는 문제별 최초 성공 시점만 누적한다`() {
        assertThat(chart(ChartDataType.SOLUTION, ChartPeriod.DAILY))
            .containsExactly(
                ChartDataPoint("2018-12-31", 1L),
                ChartDataPoint("2020-06-01", 2L),
                ChartDataPoint("2021-01-01", 3L),
                ChartDataPoint("2023-01-01", 4L)
            )
        assertThat(chart(ChartDataType.SOLUTION, ChartPeriod.MONTHLY))
            .containsExactly(
                ChartDataPoint("2018-12", 1L),
                ChartDataPoint("2020-06", 2L),
                ChartDataPoint("2021-01", 3L),
                ChartDataPoint("2023-01", 4L)
            )
    }

    @Test
    fun `주별 차트는 ISO 주차 연도를 사용한다`() {
        assertThat(chart(ChartDataType.USER, ChartPeriod.WEEKLY))
            .containsExactly(
                ChartDataPoint("2019-W01", 1L),
                ChartDataPoint("2020-W53", 2L)
            )
        assertThat(chart(ChartDataType.SOLUTION, ChartPeriod.WEEKLY))
            .containsExactly(
                ChartDataPoint("2019-W01", 1L),
                ChartDataPoint("2020-W23", 2L),
                ChartDataPoint("2020-W53", 3L),
                ChartDataPoint("2022-W52", 4L)
            )
        assertThat(chart(ChartDataType.RETROSPECTIVE, ChartPeriod.WEEKLY))
            .containsExactly(
                ChartDataPoint("2020-W01", 1L),
                ChartDataPoint("2020-W53", 2L),
                ChartDataPoint("2022-W52", 3L),
                ChartDataPoint("2023-W05", 4L)
            )
    }

    @Test
    fun `일별과 월별 회원 및 회고 차트는 누적 합계를 유지한다`() {
        assertThat(chart(ChartDataType.USER, ChartPeriod.DAILY))
            .containsExactly(
                ChartDataPoint("2018-12-31", 1L),
                ChartDataPoint("2021-01-01", 2L)
            )
        assertThat(chart(ChartDataType.USER, ChartPeriod.MONTHLY))
            .containsExactly(
                ChartDataPoint("2018-12", 1L),
                ChartDataPoint("2021-01", 2L)
            )
        assertThat(chart(ChartDataType.RETROSPECTIVE, ChartPeriod.DAILY))
            .containsExactly(
                ChartDataPoint("2019-12-30", 1L),
                ChartDataPoint("2021-01-01", 2L),
                ChartDataPoint("2023-01-01", 3L),
                ChartDataPoint("2023-02-01", 4L)
            )
        assertThat(chart(ChartDataType.RETROSPECTIVE, ChartPeriod.MONTHLY))
            .containsExactly(
                ChartDataPoint("2019-12", 1L),
                ChartDataPoint("2021-01", 2L),
                ChartDataPoint("2023-01", 3L),
                ChartDataPoint("2023-02", 4L)
            )
    }

    @Test
    fun `가입 시각이 없는 이전 회원은 조회 시점에 포함한다`() {
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").`is`(FIRST_STUDENT_ID)),
            Update().unset("createdAt"),
            Student::class.java
        )

        val result = chart(ChartDataType.USER, ChartPeriod.DAILY)

        assertThat(result.last())
            .isEqualTo(ChartDataPoint(LocalDate.now().toString(), 2L))
    }

    @Test
    fun `각 차트 조회는 전체 문서 find 없이 aggregate 한 번만 실행한다`() {
        listOf(
            ChartDataType.USER to STUDENT_COLLECTION,
            ChartDataType.SOLUTION to STUDENT_COLLECTION,
            ChartDataType.RETROSPECTIVE to RETROSPECTIVE_COLLECTION
        ).forEach { (dataType, collection) ->
            commandRecorder.reset()

            chart(dataType, ChartPeriod.MONTHLY)

            assertThat(commandRecorder.count("find", collection))
                .describedAs("$dataType find command")
                .isZero()
            assertThat(commandRecorder.count("aggregate", collection))
                .describedAs("$dataType aggregate command")
                .isEqualTo(1)
        }
    }

    private fun chart(dataType: ChartDataType, period: ChartPeriod): List<ChartDataPoint> {
        return adminDashboardChartService.getChartData(dataType, period)
    }

    private fun seedFixture() {
        studentRepository.saveAll(
            listOf(
                student(
                    id = FIRST_STUDENT_ID,
                    nickname = "chartone",
                    createdAt = LocalDateTime.of(2018, 12, 31, 12, 0),
                    solutions = listOf(
                        solution("problem-100", ProblemResult.SUCCESS, LocalDateTime.of(2018, 12, 31, 13, 0)),
                        solution("problem-100", ProblemResult.SUCCESS, LocalDateTime.of(2019, 2, 1, 13, 0)),
                        solution("problem-200", ProblemResult.FAIL, LocalDateTime.of(2019, 1, 15, 13, 0)),
                        solution("problem-300", ProblemResult.SUCCESS, LocalDateTime.of(2021, 1, 1, 13, 0))
                    )
                ),
                student(
                    id = SECOND_STUDENT_ID,
                    nickname = "charttwo",
                    createdAt = LocalDateTime.of(2021, 1, 1, 12, 0),
                    solutions = listOf(
                        solution("problem-100", ProblemResult.SUCCESS, LocalDateTime.of(2022, 3, 1, 13, 0)),
                        solution("problem-200", ProblemResult.SUCCESS, LocalDateTime.of(2020, 6, 1, 13, 0)),
                        solution("problem-400", ProblemResult.SUCCESS, LocalDateTime.of(2023, 1, 1, 13, 0)),
                        solution("problem-500", ProblemResult.TIME_OVER, LocalDateTime.of(2023, 2, 1, 13, 0))
                    )
                )
            )
        )
        retrospectiveRepository.saveAll(
            listOf(
                retrospective(FIRST_STUDENT_ID, "retro-1", LocalDateTime.of(2019, 12, 30, 12, 0)),
                retrospective(FIRST_STUDENT_ID, "retro-2", LocalDateTime.of(2021, 1, 1, 12, 0)),
                retrospective(SECOND_STUDENT_ID, "retro-3", LocalDateTime.of(2023, 1, 1, 12, 0)),
                retrospective(SECOND_STUDENT_ID, "retro-1", LocalDateTime.of(2023, 2, 1, 12, 0))
            )
        )
    }

    private fun student(
        id: String,
        nickname: String,
        createdAt: LocalDateTime,
        solutions: List<Solution>
    ): Student {
        return Student(
            id = id,
            nickname = Nickname(nickname),
            provider = Provider.BOJ,
            providerId = "$id-provider",
            currentTier = Tier.BRONZE,
            role = Role.USER,
            solutions = Solutions(solutions.toMutableList()),
            createdAt = createdAt
        )
    }

    private fun solution(
        problemId: String,
        result: ProblemResult,
        solvedAt: LocalDateTime
    ): Solution {
        return Solution(
            problemId = ProblemId(problemId),
            timeTaken = TimeTakenSeconds(60),
            result = result,
            solvedAt = solvedAt
        )
    }

    private fun retrospective(
        studentId: String,
        problemId: String,
        createdAt: LocalDateTime
    ): Retrospective {
        return Retrospective(
            studentId = studentId,
            problemId = problemId,
            content = "관리자 차트 통합 테스트를 위한 회고 내용입니다.",
            createdAt = createdAt
        )
    }

    companion object {
        private const val FIRST_STUDENT_ID = "admin-chart-student-1"
        private const val SECOND_STUDENT_ID = "admin-chart-student-2"
        private const val STUDENT_COLLECTION = "students"
        private const val RETROSPECTIVE_COLLECTION = "retrospectives"
    }
}

@TestConfiguration
class AdminDashboardChartMongoCommandConfiguration {

    @Bean
    fun adminDashboardChartMongoCommandRecorder(): AdminDashboardChartMongoCommandRecorder {
        return AdminDashboardChartMongoCommandRecorder()
    }

    @Bean
    fun adminDashboardChartMongoClientCustomizer(
        recorder: AdminDashboardChartMongoCommandRecorder
    ): MongoClientSettingsBuilderCustomizer {
        return MongoClientSettingsBuilderCustomizer { builder ->
            builder.addCommandListener(recorder)
        }
    }
}

class AdminDashboardChartMongoCommandRecorder : CommandListener {
    private val commands = ConcurrentLinkedQueue<AdminDashboardMongoCommand>()

    override fun commandStarted(event: CommandStartedEvent) {
        val collection = when (event.commandName) {
            "find" -> event.command["find"]
            "aggregate" -> event.command["aggregate"]
            else -> null
        } as? BsonString ?: return

        commands.add(
            AdminDashboardMongoCommand(
                name = event.commandName,
                collection = collection.value
            )
        )
    }

    fun reset() {
        commands.clear()
    }

    fun count(name: String, collection: String): Long {
        return commands.count { command ->
            command.name == name && command.collection == collection
        }.toLong()
    }
}

data class AdminDashboardMongoCommand(
    val name: String,
    val collection: String
)
