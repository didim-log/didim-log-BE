package com.didimlog.infra.solvedac

import com.didimlog.student.domain.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

@DisplayName("SolvedAcWebClient 테스트")
class SolvedAcWebClientTest {

    private val requests = CopyOnWriteArrayList<URI>()

    private data class StubResponse(val status: HttpStatus, val body: String? = null)

    private fun client(vararg responses: StubResponse): SolvedAcWebClient {
        val queue = ArrayDeque(responses.toList())
        val webClient = WebClient.builder()
            .baseUrl("https://solved.ac/api/v3")
            .exchangeFunction { request ->
                requests += request.url()
                val next = if (queue.size > 1) queue.removeFirst() else queue.first()
                val builder = ClientResponse.create(next.status, ExchangeStrategies.withDefaults())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                next.body?.let(builder::body)
                Mono.just(builder.build())
            }
            .build()
        return SolvedAcWebClient(webClient)
    }

    private val problemJson = """
        {"problemId":1000,"titleKo":"A+B","level":1,
         "tags":[{"key":"math","isMeta":false,"displayNames":[{"language":"ko","name":"수학","short":"수학"}]}],
         "unknownField":"ignored"}
    """.trimIndent()

    private val userJson = """{"handle":"boj_user","rating":1650,"tier":15,"bio":"ignored"}"""

    @Test
    @DisplayName("문제 조회는 problemId 쿼리로 요청하고 응답을 변환한다")
    fun `문제 조회 성공`() {
        val client = client(StubResponse(HttpStatus.OK, problemJson))

        val problem = client.fetchProblem(1000)

        assertThat(problem.problemId).isEqualTo(1000)
        assertThat(problem.titleKo).isEqualTo("A+B")
        assertThat(problem.level).isEqualTo(1)
        assertThat(problem.tags.single().key).isEqualTo("math")
        assertThat(problem.tags.single().displayNames.single().name).isEqualTo("수학")
        assertThat(requests.single().path).isEqualTo("/api/v3/problem/show")
        assertThat(requests.single().query).isEqualTo("problemId=1000")
    }

    @Test
    @DisplayName("문제가 없으면 재시도하지 않고 PROBLEM_NOT_FOUND를 던진다")
    fun `문제 없음`() {
        val client = client(StubResponse(HttpStatus.NOT_FOUND))

        val exception = assertThrows<BusinessException> { client.fetchProblem(99999) }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.PROBLEM_NOT_FOUND)
        assertThat(requests).hasSize(1)
    }

    @Test
    @DisplayName("일시적인 서버 오류는 재시도해서 복구한다")
    fun `서버 오류 재시도 후 성공`() {
        val client = client(StubResponse(HttpStatus.BAD_GATEWAY), StubResponse(HttpStatus.OK, problemJson))

        val problem = client.fetchProblem(1000)

        assertThat(problem.problemId).isEqualTo(1000)
        assertThat(requests).hasSize(2)
    }

    @Test
    @DisplayName("재시도 후에도 서버 오류면 내부 오류로 변환한다")
    fun `서버 오류 재시도 소진`() {
        val client = client(StubResponse(HttpStatus.INTERNAL_SERVER_ERROR))

        val exception = assertThrows<BusinessException> { client.fetchProblem(1000) }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR)
        assertThat(requests).hasSize(3)
    }

    @Test
    @DisplayName("최근 조회에 성공한 문제는 장애 시 캐시된 응답으로 대체한다")
    fun `장애 시 캐시 대체`() {
        val client = client(
            StubResponse(HttpStatus.OK, problemJson),
            StubResponse(HttpStatus.SERVICE_UNAVAILABLE)
        )
        val first = client.fetchProblem(1000)

        val fallback = client.fetchProblem(1000)

        assertThat(fallback).isEqualTo(first)
        assertThat(requests).hasSize(4)
    }

    @Test
    @DisplayName("캐시가 있어도 문제가 삭제되어 404가 오면 캐시로 대체하지 않는다")
    fun `404는 캐시 대체하지 않음`() {
        val client = client(
            StubResponse(HttpStatus.OK, problemJson),
            StubResponse(HttpStatus.NOT_FOUND)
        )
        client.fetchProblem(1000)

        val exception = assertThrows<BusinessException> { client.fetchProblem(1000) }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.PROBLEM_NOT_FOUND)
    }

    @Test
    @DisplayName("응답 본문이 비어 있으면 내부 오류를 던진다")
    fun `빈 문제 응답`() {
        val client = client(StubResponse(HttpStatus.OK))

        val exception = assertThrows<BusinessException> { client.fetchProblem(1000) }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR)
        assertThat(exception.message).contains("problemId=1000")
    }

    @Test
    @DisplayName("사용자 조회는 handle 쿼리로 요청하고 rating과 tier를 변환한다")
    fun `사용자 조회 성공`() {
        val client = client(StubResponse(HttpStatus.OK, userJson))

        val user = client.fetchUser(BojId("boj_user"))

        assertThat(user).isEqualTo(SolvedAcUserResponse(handle = "boj_user", rating = 1650, tier = 15))
        assertThat(requests.single().path).isEqualTo("/api/v3/user/show")
        assertThat(requests.single().query).isEqualTo("handle=boj_user")
    }

    @Test
    @DisplayName("사용자가 없으면 재시도하지 않고 COMMON_RESOURCE_NOT_FOUND를 던진다")
    fun `사용자 없음`() {
        val client = client(StubResponse(HttpStatus.NOT_FOUND))

        val exception = assertThrows<BusinessException> { client.fetchUser(BojId("ghost")) }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        assertThat(requests).hasSize(1)
    }

    @Test
    @DisplayName("사용자 조회 서버 오류가 계속되면 재시도 후 BusinessException으로 변환한다")
    fun `사용자 조회 서버 오류`() {
        val client = client(StubResponse(HttpStatus.INTERNAL_SERVER_ERROR))

        val exception = assertThrows<BusinessException> { client.fetchUser(BojId("boj_user")) }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR)
        assertThat(requests).hasSize(3)
    }

    @Test
    @DisplayName("사용자 응답 본문이 비어 있으면 내부 오류를 던진다")
    fun `빈 사용자 응답`() {
        val client = client(StubResponse(HttpStatus.OK))

        val exception = assertThrows<BusinessException> { client.fetchUser(BojId("boj_user")) }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR)
        assertThat(exception.message).contains("bojId=boj_user")
    }
}
