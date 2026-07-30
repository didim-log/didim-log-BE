package com.didimlog.application.admin

import java.time.ZoneId
import org.bson.Document
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.AggregationExpression
import org.springframework.data.mongodb.core.aggregation.DateOperators
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자 대시보드 차트 데이터 서비스
 * 통계 카드 클릭 시 표시할 트렌드 차트 데이터를 제공한다.
 */
@Service
class AdminDashboardChartService(
    private val mongoTemplate: MongoTemplate
) {

    /**
     * 차트 데이터를 조회한다.
     *
     * @param dataType 데이터 타입 (USER, SOLUTION, RETROSPECTIVE)
     * @param period 기간 (DAILY, WEEKLY, MONTHLY)
     * @return 차트 데이터 리스트 (날짜, 값)
     */
    @Transactional(readOnly = true)
    fun getChartData(dataType: ChartDataType, period: ChartPeriod): List<ChartDataPoint> {
        return when (dataType) {
            ChartDataType.USER -> getUserChartData(period)
            ChartDataType.SOLUTION -> getSolutionChartData(period)
            ChartDataType.RETROSPECTIVE -> getRetrospectiveChartData(period)
        }
    }

    /**
     * 회원 수 차트 데이터를 조회한다.
     */
    private fun getUserChartData(period: ChartPeriod): List<ChartDataPoint> {
        val aggregation = Aggregation.newAggregation(
            Aggregation.project()
                .and(dateKey("createdAt", period, fallbackToNow = true))
                .`as`(DATE_FIELD),
            Aggregation.group(DATE_FIELD).count().`as`(COUNT_FIELD),
            Aggregation.sort(Sort.Direction.ASC, "_id")
        )

        return aggregateCumulative(STUDENT_COLLECTION, aggregation)
    }

    /**
     * 해결된 문제 수 차트 데이터를 조회한다.
     */
    private fun getSolutionChartData(period: ChartPeriod): List<ChartDataPoint> {
        val aggregation = Aggregation.newAggregation(
            Aggregation.unwind(SOLUTION_ITEMS_FIELD),
            Aggregation.match(
                Criteria.where(SOLUTION_RESULT_FIELD)
                    .`is`("SUCCESS")
            ),
            Aggregation.group(SOLUTION_PROBLEM_ID_FIELD)
                .min(SOLUTION_SOLVED_AT_FIELD)
                .`as`(FIRST_SOLVED_AT_FIELD),
            Aggregation.project()
                .and(dateKey(FIRST_SOLVED_AT_FIELD, period))
                .`as`(DATE_FIELD),
            Aggregation.group(DATE_FIELD).count().`as`(COUNT_FIELD),
            Aggregation.sort(Sort.Direction.ASC, "_id")
        )

        return aggregateCumulative(STUDENT_COLLECTION, aggregation)
    }

    /**
     * 회고 수 차트 데이터를 조회한다.
     */
    private fun getRetrospectiveChartData(period: ChartPeriod): List<ChartDataPoint> {
        val aggregation = Aggregation.newAggregation(
            Aggregation.project()
                .and(dateKey("createdAt", period))
                .`as`(DATE_FIELD),
            Aggregation.group(DATE_FIELD).count().`as`(COUNT_FIELD),
            Aggregation.sort(Sort.Direction.ASC, "_id")
        )

        return aggregateCumulative(RETROSPECTIVE_COLLECTION, aggregation)
    }

    private fun dateKey(
        field: String,
        period: ChartPeriod,
        fallbackToNow: Boolean = false
    ): AggregationExpression {
        val formatBuilder = if (fallbackToNow) {
            DateOperators.DateToString.dateOf(
                AggregationExpression {
                    Document(
                        "\$ifNull",
                        listOf("\$$field", "\$\$NOW")
                    )
                }
            )
        } else {
            DateOperators.DateToString.dateOf(field)
        }

        return formatBuilder
            .toString(period.mongoDateFormat)
            .withTimezone(DateOperators.Timezone.fromZone(ZoneId.systemDefault()))
    }

    private fun aggregateCumulative(
        collection: String,
        aggregation: Aggregation
    ): List<ChartDataPoint> {
        var cumulative = 0L
        return mongoTemplate.aggregate(aggregation, collection, Document::class.java)
            .mappedResults
            .map { bucket ->
                val count = (bucket[COUNT_FIELD] as Number).toLong()
                cumulative += count
                ChartDataPoint(
                    date = bucket.getString("_id"),
                    value = cumulative
                )
            }
    }

    private val ChartPeriod.mongoDateFormat: String
        get() = when (this) {
            ChartPeriod.DAILY -> "%Y-%m-%d"
            ChartPeriod.WEEKLY -> "%G-W%V"
            ChartPeriod.MONTHLY -> "%Y-%m"
        }

    companion object {
        private const val STUDENT_COLLECTION = "students"
        private const val RETROSPECTIVE_COLLECTION = "retrospectives"
        private const val SOLUTION_ITEMS_FIELD = "solutions.items"
        private const val SOLUTION_RESULT_FIELD = "$SOLUTION_ITEMS_FIELD.result"
        private const val SOLUTION_PROBLEM_ID_FIELD = "$SOLUTION_ITEMS_FIELD.problemId"
        private const val SOLUTION_SOLVED_AT_FIELD = "$SOLUTION_ITEMS_FIELD.solvedAt"
        private const val FIRST_SOLVED_AT_FIELD = "firstSolvedAt"
        private const val DATE_FIELD = "date"
        private const val COUNT_FIELD = "count"
    }
}

/**
 * 차트 데이터 타입
 */
enum class ChartDataType {
    USER,           // 회원 수
    SOLUTION,       // 해결된 문제 수
    RETROSPECTIVE   // 회고 수
}

/**
 * 차트 기간
 */
enum class ChartPeriod {
    DAILY,      // 일별
    WEEKLY,     // 주별
    MONTHLY     // 월별
}

/**
 * 차트 데이터 포인트
 */
data class ChartDataPoint(
    val date: String,   // 날짜 문자열 (형식은 period에 따라 다름)
    val value: Long     // 값
)
