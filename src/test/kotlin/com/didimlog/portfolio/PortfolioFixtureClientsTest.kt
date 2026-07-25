package com.didimlog.portfolio

import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.infra.solvedac.ProblemCategoryMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("포트폴리오 문제 fixture")
class PortfolioFixtureClientsTest {

    private val client = PortfolioSolvedAcClient()
    private val crawler = PortfolioBojCrawler()

    @Test
    @DisplayName("1000번부터 1005번까지 결정적인 제목과 난이도를 반환한다")
    fun returnsDeterministicProblemRange() {
        val problems = (1000..1005).map(client::fetchProblem)

        assertThat(problems.map { it.problemId }).containsExactly(1000, 1001, 1002, 1003, 1004, 1005)
        assertThat(problems.map { it.titleKo })
            .containsExactly("A+B", "A-B", "터렛", "피보나치 함수", "어린 왕자", "ACM Craft")
        assertThat(problems.map { it.level }).containsExactly(1, 1, 7, 9, 10, 13)
    }

    @Test
    @DisplayName("다중 태그를 표준 카테고리로 정규화하고 첫 태그를 대표 카테고리로 사용한다")
    fun normalizesMultipleTagsAndSelectsPrimaryCategory() {
        val response = client.fetchProblem(1005)

        val normalizedTags = ProblemCategoryMapper.extractTagsToEnglish(response.tags)
        val primaryCategory = ProblemCategoryMapper.determineCategory(normalizedTags)

        assertThat(normalizedTags)
            .containsExactly("Graph Theory", "Topological Sorting", "Dynamic Programming")
        assertThat(primaryCategory).isEqualTo(ProblemCategory.GRAPH_THEORY)
    }

    @Test
    @DisplayName("1000번부터 1005번까지 메타데이터와 일치하는 문제 상세를 반환한다")
    fun returnsCoherentProblemDetails() {
        val expectedDescriptionFragments = mapOf(
            "1000" to "A+B",
            "1001" to "A-B",
            "1002" to "두 원",
            "1003" to "fibonacci",
            "1004" to "행성계",
            "1005" to "선행 관계"
        )

        expectedDescriptionFragments.forEach { (problemId, fragment) ->
            val details = crawler.crawlProblemDetails(problemId)

            assertThat(details.descriptionHtml).contains(fragment)
            assertThat(details.sampleInputs).hasSize(1)
            assertThat(details.sampleOutputs).hasSize(1)
        }
    }
}
