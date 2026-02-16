package com.didimlog.infra.solvedac

import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Component
class SolvedAcWebClient(
    private val solvedAcClient: WebClient
) : SolvedAcClient {

    private val log = LoggerFactory.getLogger(SolvedAcWebClient::class.java)
    private val problemCache = ConcurrentHashMap<Int, CachedProblem>()

    companion object {
        private const val PROBLEM_CACHE_TTL_MINUTES = 30L
        private const val RETRY_COUNT = 2L
    }

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
                .onStatus({ it == HttpStatus.NOT_FOUND }) {
                    log.warn("Solved.ac에서 문제를 찾을 수 없음: problemId=$problemId, status=404")
                    Mono.error(
                        BusinessException(
                            ErrorCode.PROBLEM_NOT_FOUND,
                            "Solved.ac에서 문제를 찾을 수 없습니다. problemId=$problemId"
                        )
                    )
                }
                .onStatus({ it.isError }) { response ->
                    log.error("Solved.ac API 에러 응답: problemId=$problemId, status=${response.statusCode()}")
                    Mono.error(
                        BusinessException(
                            ErrorCode.COMMON_INTERNAL_ERROR,
                            "Solved.ac API 호출에 실패했습니다. problemId=$problemId, status=${response.statusCode()}"
                        )
                    )
                }
                .bodyToMono(SolvedAcProblemResponse::class.java)
                .retryWhen(
                    Retry.fixedDelay(RETRY_COUNT, Duration.ofMillis(300))
                        .filter { shouldRetry(it) }
                )
                .doOnNext { problemCache[problemId] = CachedProblem(it) }
                .block()
                ?: throw BusinessException(ErrorCode.COMMON_INTERNAL_ERROR, "Solved.ac 문제 정보를 가져오지 못했습니다. problemId=$problemId")
        } catch (e: WebClientResponseException) {
            log.error("Solved.ac API 호출 실패 (문제 조회): problemId=$problemId, status=${e.statusCode}, message=${e.message}", e)
            val cached = getCachedProblem(problemId)
            if (cached != null) {
                log.warn("Solved.ac 문제 조회 실패 - 캐시된 데이터로 대체: problemId=$problemId")
                return cached
            }
            throw BusinessException(
                ErrorCode.COMMON_INTERNAL_ERROR,
                "Solved.ac API 호출에 실패했습니다. problemId=$problemId, status=${e.statusCode}"
            )
        } catch (e: BusinessException) {
            val cached = getCachedProblem(problemId)
            if (cached != null && e.errorCode != ErrorCode.PROBLEM_NOT_FOUND) {
                log.warn("Solved.ac 문제 조회 BusinessException - 캐시된 데이터로 대체: problemId=$problemId")
                return cached
            }
            throw e
        } catch (e: Exception) {
            log.error("Solved.ac 문제 정보 조회 중 예상치 못한 예외 발생: problemId=$problemId", e)
            val cached = getCachedProblem(problemId)
            if (cached != null) {
                log.warn("Solved.ac 문제 조회 예외 - 캐시된 데이터로 대체: problemId=$problemId")
                return cached
            }
            throw BusinessException(ErrorCode.COMMON_INTERNAL_ERROR, "Solved.ac 문제 정보를 가져오지 못했습니다. problemId=$problemId")
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
                .onStatus({ it == HttpStatus.NOT_FOUND }) {
                    log.warn("Solved.ac에서 사용자를 찾을 수 없음: bojId=${bojId.value}, status=404")
                    Mono.error(
                        BusinessException(
                            ErrorCode.COMMON_RESOURCE_NOT_FOUND,
                            "Solved.ac에서 사용자를 찾을 수 없습니다. bojId=${bojId.value}"
                        )
                    )
                }
                .onStatus({ it.isError }) { response ->
                    log.error("Solved.ac API 에러 응답: bojId=${bojId.value}, status=${response.statusCode()}")
                    Mono.error(
                        BusinessException(
                            ErrorCode.COMMON_INTERNAL_ERROR,
                            "Solved.ac API 호출에 실패했습니다. bojId=${bojId.value}, status=${response.statusCode()}"
                        )
                    )
                }
                .bodyToMono(SolvedAcUserResponse::class.java)
                .retryWhen(
                    Retry.fixedDelay(RETRY_COUNT, Duration.ofMillis(300))
                        .filter { shouldRetry(it) }
                )
                .block()
                ?: throw BusinessException(
                    ErrorCode.COMMON_INTERNAL_ERROR,
                    "Solved.ac 사용자 정보를 가져오지 못했습니다. bojId=${bojId.value}"
                )
        } catch (e: WebClientResponseException) {
            log.error("Solved.ac API 호출 실패 (사용자 조회): bojId=${bojId.value}, status=${e.statusCode}, message=${e.message}", e)
            if (e.statusCode == HttpStatus.NOT_FOUND) {
                throw BusinessException(
                    ErrorCode.COMMON_RESOURCE_NOT_FOUND,
                    "Solved.ac에서 사용자를 찾을 수 없습니다. bojId=${bojId.value}"
                )
            }
            throw BusinessException(
                ErrorCode.COMMON_INTERNAL_ERROR,
                "Solved.ac API 호출에 실패했습니다. bojId=${bojId.value}, status=${e.statusCode}"
            )
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error("Solved.ac 사용자 정보 조회 중 예상치 못한 예외 발생: bojId=${bojId.value}", e)
            throw BusinessException(
                ErrorCode.COMMON_INTERNAL_ERROR,
                "Solved.ac 사용자 정보를 가져오지 못했습니다. bojId=${bojId.value}"
            )
        }
    }

    private fun shouldRetry(throwable: Throwable): Boolean {
        if (throwable is BusinessException) {
            return throwable.errorCode != ErrorCode.PROBLEM_NOT_FOUND &&
                throwable.errorCode != ErrorCode.COMMON_RESOURCE_NOT_FOUND
        }
        return true
    }

    private fun getCachedProblem(problemId: Int): SolvedAcProblemResponse? {
        val cached = problemCache[problemId] ?: return null
        val age = Duration.between(cached.cachedAt, java.time.LocalDateTime.now())
        if (age.toMinutes() > PROBLEM_CACHE_TTL_MINUTES) {
            problemCache.remove(problemId)
            return null
        }
        return cached.value
    }

    private data class CachedProblem(
        val value: SolvedAcProblemResponse,
        val cachedAt: java.time.LocalDateTime = java.time.LocalDateTime.now()
    )
}
