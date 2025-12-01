package com.didimlog.infra.solvedac

import com.didimlog.domain.valueobject.BojId
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

@Component
class SolvedAcWebClient(
    private val solvedAcClient: WebClient
) : SolvedAcClient {

    override fun fetchProblem(problemId: Int): SolvedAcProblemResponse {
        return solvedAcClient.get()
            .uri { uriBuilder ->
                uriBuilder
                    .path("/problem/show")
                    .queryParam("problemId", problemId)
                    .build()
            }
            .retrieve()
            .bodyToMono(SolvedAcProblemResponse::class.java)
            .block()
            ?: throw IllegalStateException("Solved.ac 문제 정보를 가져오지 못했습니다. problemId=$problemId")
    }

    override fun fetchUser(bojId: BojId): SolvedAcUserResponse {
        return solvedAcClient.get()
            .uri { uriBuilder ->
                uriBuilder
                    .path("/user/show")
                    .queryParam("handle", bojId.value)
                    .build()
            }
            .retrieve()
            .bodyToMono(SolvedAcUserResponse::class.java)
            .block()
            ?: throw IllegalStateException("Solved.ac 사용자 정보를 가져오지 못했습니다. bojId=${bojId.value}")
    }
}


