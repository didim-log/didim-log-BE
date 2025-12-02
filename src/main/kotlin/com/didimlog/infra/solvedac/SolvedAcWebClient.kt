package com.didimlog.infra.solvedac

import com.didimlog.domain.valueobject.BojId
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

@Component
class SolvedAcWebClient(
    private val solvedAcClient: WebClient
) : SolvedAcClient {

    private val log = LoggerFactory.getLogger(SolvedAcWebClient::class.java)

    override fun fetchProblem(problemId: Int): SolvedAcProblemResponse {
        return try {
            solvedAcClient.get()
                .uri { uriBuilder ->
                    uriBuilder
                        .path("/problem/show")
                        .queryParam("problemId", problemId)
                        .build()
                }
                .retrieve()
                .onStatus({ it == HttpStatus.NOT_FOUND }) { response ->
                    log.warn("Solved.ac에서 문제를 찾을 수 없음: problemId=$problemId, status=404")
                    throw IllegalStateException("Solved.ac에서 문제를 찾을 수 없습니다. problemId=$problemId")
                }
                .onStatus({ it.isError }) { response ->
                    log.error("Solved.ac API 에러 응답: problemId=$problemId, status=${response.statusCode()}")
                    throw IllegalStateException("Solved.ac API 호출에 실패했습니다. problemId=$problemId, status=${response.statusCode()}")
                }
                .bodyToMono(SolvedAcProblemResponse::class.java)
                .block()
                ?: throw IllegalStateException("Solved.ac 문제 정보를 가져오지 못했습니다. problemId=$problemId")
        } catch (e: WebClientResponseException) {
            log.error("Solved.ac API 호출 실패 (문제 조회): problemId=$problemId, status=${e.statusCode}, message=${e.message}", e)
            throw IllegalStateException("Solved.ac API 호출에 실패했습니다. problemId=$problemId, status=${e.statusCode}")
        } catch (e: IllegalStateException) {
            // 이미 처리된 예외는 그대로 재발생
            throw e
        } catch (e: Exception) {
            log.error("Solved.ac 문제 정보 조회 중 예상치 못한 예외 발생: problemId=$problemId", e)
            throw IllegalStateException("Solved.ac 문제 정보를 가져오지 못했습니다. problemId=$problemId", e)
        }
    }

    override fun fetchUser(bojId: BojId): SolvedAcUserResponse {
        return try {
            solvedAcClient.get()
                .uri { uriBuilder ->
                    uriBuilder
                        .path("/user/show")
                        .queryParam("handle", bojId.value)
                        .build()
                }
                .retrieve()
                .onStatus({ it == HttpStatus.NOT_FOUND }) { response ->
                    log.warn("Solved.ac에서 사용자를 찾을 수 없음: bojId=${bojId.value}, status=404")
                    throw IllegalStateException("Solved.ac에서 사용자를 찾을 수 없습니다. bojId=${bojId.value}")
                }
                .onStatus({ it.isError }) { response ->
                    log.error("Solved.ac API 에러 응답: bojId=${bojId.value}, status=${response.statusCode()}")
                    throw IllegalStateException("Solved.ac API 호출에 실패했습니다. bojId=${bojId.value}, status=${response.statusCode()}")
                }
                .bodyToMono(SolvedAcUserResponse::class.java)
                .block()
                ?: throw IllegalStateException("Solved.ac 사용자 정보를 가져오지 못했습니다. bojId=${bojId.value}")
        } catch (e: WebClientResponseException) {
            log.error("Solved.ac API 호출 실패 (사용자 조회): bojId=${bojId.value}, status=${e.statusCode}, message=${e.message}", e)
            when (e.statusCode) {
                HttpStatus.NOT_FOUND -> throw IllegalStateException("Solved.ac에서 사용자를 찾을 수 없습니다. bojId=${bojId.value}")
                else -> throw IllegalStateException("Solved.ac API 호출에 실패했습니다. bojId=${bojId.value}, status=${e.statusCode}")
            }
        } catch (e: IllegalStateException) {
            // 이미 처리된 예외는 그대로 재발생
            throw e
        } catch (e: Exception) {
            log.error("Solved.ac 사용자 정보 조회 중 예상치 못한 예외 발생: bojId=${bojId.value}", e)
            throw IllegalStateException("Solved.ac 사용자 정보를 가져오지 못했습니다. bojId=${bojId.value}", e)
        }
    }
}



