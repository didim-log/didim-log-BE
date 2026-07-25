package com.didimlog.portfolio

import com.didimlog.application.auth.boj.BojProfileStatusMessage
import com.didimlog.application.auth.boj.BojProfileStatusMessageClient
import com.didimlog.application.auth.boj.BojProfileStatusMessageFetchResult
import com.didimlog.domain.valueobject.BojId
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.crawler.ProblemDetails
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcProblemResponse
import com.didimlog.infra.solvedac.SolvedAcTag
import com.didimlog.infra.solvedac.SolvedAcTagDisplayName
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("portfolio-fixture & !prod")
class PortfolioSolvedAcClient : SolvedAcClient {
    override fun fetchProblem(problemId: Int): SolvedAcProblemResponse {
        val fixture = problemFixtures[problemId] ?: ProblemFixture(
            title = "포트폴리오 문제 $problemId",
            level = 1,
            tags = listOf(FixtureTag("implementation", "구현"))
        )
        return SolvedAcProblemResponse(
            problemId = problemId,
            titleKo = fixture.title,
            level = fixture.level,
            tags = fixture.tags.map { tag ->
                SolvedAcTag(
                    key = tag.key,
                    displayNames = listOf(SolvedAcTagDisplayName("ko", tag.koreanName))
                )
            }
        )
    }

    override fun fetchUser(bojId: BojId): SolvedAcUserResponse =
        SolvedAcUserResponse(
            handle = if (bojId.value.endsWith("admin")) "pAdmin" else "pDemo",
            rating = 800,
            tier = 8
        )

    private data class ProblemFixture(
        val title: String,
        val level: Int,
        val tags: List<FixtureTag>
    )

    private data class FixtureTag(
        val key: String,
        val koreanName: String
    )

    private companion object {
        val problemFixtures = mapOf(
            1000 to ProblemFixture(
                title = "A+B",
                level = 1,
                tags = listOf(FixtureTag("arithmetic", "사칙연산"))
            ),
            1001 to ProblemFixture(
                title = "A-B",
                level = 1,
                tags = listOf(FixtureTag("arithmetic", "사칙연산"))
            ),
            1002 to ProblemFixture(
                title = "터렛",
                level = 7,
                tags = listOf(FixtureTag("geometry", "기하학"))
            ),
            1003 to ProblemFixture(
                title = "피보나치 함수",
                level = 9,
                tags = listOf(
                    FixtureTag("dynamic_programming", "다이나믹 프로그래밍"),
                    FixtureTag("mathematics", "수학")
                )
            ),
            1004 to ProblemFixture(
                title = "어린 왕자",
                level = 10,
                tags = listOf(
                    FixtureTag("geometry", "기하학"),
                    FixtureTag("mathematics", "수학")
                )
            ),
            1005 to ProblemFixture(
                title = "ACM Craft",
                level = 13,
                tags = listOf(
                    FixtureTag("graph_theory", "그래프 이론"),
                    FixtureTag("topological_sorting", "위상 정렬"),
                    FixtureTag("dynamic_programming", "다이나믹 프로그래밍")
                )
            )
        )
    }
}

@Component
@Profile("portfolio-fixture & !prod")
class PortfolioBojProfileStatusMessageClient : BojProfileStatusMessageClient {
    override fun fetchStatusMessage(bojId: String): BojProfileStatusMessageFetchResult =
        BojProfileStatusMessageFetchResult.Found(BojProfileStatusMessage(FIXTURE_CODE))

    private companion object {
        const val FIXTURE_CODE = "DIDIM-LOG-DEMO42"
    }
}

@Component
@Profile("portfolio-fixture & !prod")
class PortfolioBojCrawler : BojCrawler() {
    override fun crawlProblemDetails(problemId: String): ProblemDetails =
        when (problemId) {
            "1000" -> details(
                description = "두 정수 A와 B를 입력받은 다음, A+B를 출력하는 프로그램을 작성하시오.",
                input = "첫째 줄에 A와 B가 주어진다. (0 &lt; A, B &lt; 10)",
                output = "첫째 줄에 A+B를 출력한다.",
                sampleInput = "1 2",
                sampleOutput = "3"
            )
            "1001" -> details(
                description = "두 정수 A와 B를 입력받은 다음, A-B를 출력하는 프로그램을 작성하시오.",
                input = "첫째 줄에 A와 B가 주어진다.",
                output = "첫째 줄에 A-B를 출력한다.",
                sampleInput = "3 2",
                sampleOutput = "1"
            )
            "1002" -> details(
                description = "두 원의 중심과 반지름이 주어질 때 두 원이 만나는 점의 개수를 구한다.",
                input = "테스트 케이스마다 두 원의 중심 좌표와 반지름이 주어진다.",
                output = "두 원이 만나는 점의 개수를 출력한다.",
                sampleInput = "1\n0 0 13 40 0 37",
                sampleOutput = "2"
            )
            "1003" -> details(
                description = "fibonacci(N)을 호출할 때 0과 1이 각각 몇 번 출력되는지 구한다.",
                input = "첫째 줄에 테스트 케이스 수가, 다음 줄부터 N이 주어진다.",
                output = "각 테스트 케이스마다 0과 1의 출력 횟수를 출력한다.",
                sampleInput = "1\n3",
                sampleOutput = "1 2"
            )
            "1004" -> details(
                description = "출발점에서 도착점까지 이동할 때 통과해야 하는 행성계 경계의 최소 횟수를 구한다.",
                input = "테스트 케이스마다 출발점, 도착점과 행성계의 중심·반지름이 주어진다.",
                output = "각 테스트 케이스의 최소 진입·이탈 횟수를 출력한다.",
                sampleInput = "1\n-5 1 12 1\n1\n1 1 8",
                sampleOutput = "1"
            )
            "1005" -> details(
                description = "건물 사이의 선행 관계와 건설 시간을 바탕으로 목표 건물의 최소 완성 시간을 계산한다.",
                input = "테스트 케이스마다 건물 수, 건설 규칙, 각 건물의 시간과 목표 건물이 주어진다.",
                output = "각 테스트 케이스의 목표 건물 완성 시간을 출력한다.",
                sampleInput = "1\n4 4\n10 1 100 10\n1 2\n1 3\n2 4\n3 4\n4",
                sampleOutput = "120"
            )
            else -> details(
                description = "포트폴리오 fixture 문제 ${problemId}의 구현 요구사항을 확인한다.",
                input = "문제에서 요구하는 값을 입력한다.",
                output = "구현 결과를 출력한다.",
                sampleInput = "1",
                sampleOutput = "1"
            )
        }

    private fun details(
        description: String,
        input: String,
        output: String,
        sampleInput: String,
        sampleOutput: String
    ): ProblemDetails =
        ProblemDetails(
            descriptionHtml = "<p>$description</p>",
            inputDescriptionHtml = "<p>$input</p>",
            outputDescriptionHtml = "<p>$output</p>",
            sampleInputs = listOf(sampleInput),
            sampleOutputs = listOf(sampleOutput)
        )
}
