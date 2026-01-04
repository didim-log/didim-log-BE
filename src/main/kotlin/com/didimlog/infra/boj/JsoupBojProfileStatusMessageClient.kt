package com.didimlog.infra.boj

import com.didimlog.application.auth.boj.BojProfileStatusMessageClient
import com.didimlog.application.auth.boj.BojProfileStatusMessageFetchResult
import com.didimlog.application.auth.boj.BojProfileStatusMessage
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class JsoupBojProfileStatusMessageClient : BojProfileStatusMessageClient {

    private val log = LoggerFactory.getLogger(JsoupBojProfileStatusMessageClient::class.java)
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TIMEOUT_MILLIS = 10_000
        private const val REFERRER = "https://www.acmicpc.net"
        private const val ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
    }

    override fun fetchStatusMessage(bojId: String): BojProfileStatusMessageFetchResult {
        val url = "https://www.acmicpc.net/user/$bojId"
        return try {
            val document = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .referrer(REFERRER)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .timeout(TIMEOUT_MILLIS)
                .get()

            // BOJ 프로필의 상태 메시지는 blockquote.no-mathjax 태그 안에 있습니다.
            // 구조가 바뀔 수 있어 다중 셀렉터를 시도합니다.
            val selectors = listOf(
                "blockquote.no-mathjax",  // 실제 BOJ 프로필 페이지의 상태 메시지 셀렉터
                "blockquote",  // fallback
                "#status-message",
                ".status-message",
                ".user-status-message",
                "#problem-header .user-status",
                ".profile-status"
            )

            val candidate = selectors.asSequence()
                .mapNotNull { selector ->
                    val element = document.selectFirst(selector)
                    if (element == null) return@mapNotNull null

                    extractStatusMessageText(element, selector)
                }
                .firstOrNull { it.isNotBlank() }

            candidate?.let { BojProfileStatusMessageFetchResult.Found(BojProfileStatusMessage(it)) }
                ?: run {
                    log.warn("BOJ 프로필 상태 메시지를 찾을 수 없음: bojId=$bojId")
                    BojProfileStatusMessageFetchResult.StatusMessageNotFound
                }
        } catch (e: org.jsoup.HttpStatusException) {
            toResultByHttpStatus(bojId, e)
        } catch (e: Exception) {
            log.warn("BOJ 프로필 상태 메시지 조회 실패: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}", e)
            BojProfileStatusMessageFetchResult.Failed("exceptionType=${e.javaClass.simpleName}, message=${e.message}")
        }
    }

    private fun extractStatusMessageText(element: org.jsoup.nodes.Element, selector: String): String? {
        if (!selector.startsWith("blockquote")) {
            return element.text().trim().takeIf { it.isNotBlank() }
        }

        // blockquote 내부 div 태그를 제거한 뒤 텍스트만 추출
        val cloned = element.clone()
        cloned.select("div").remove()
        return cloned.text().trim().takeIf { it.isNotBlank() }
    }

    private fun toResultByHttpStatus(
        bojId: String,
        e: org.jsoup.HttpStatusException
    ): BojProfileStatusMessageFetchResult {
        if (e.statusCode == 404) {
            log.warn("BOJ 프로필을 찾을 수 없음: bojId=$bojId, status=404")
            return BojProfileStatusMessageFetchResult.UserNotFound
        }
        if (e.statusCode == 403) {
            log.warn("BOJ 프로필 접근 거부: bojId=$bojId, status=403")
            return BojProfileStatusMessageFetchResult.AccessDenied
        }
        log.warn("BOJ 프로필 상태 메시지 조회 실패: bojId=$bojId, status=${e.statusCode}, message=${e.message}")
        return BojProfileStatusMessageFetchResult.Failed("status=${e.statusCode}, message=${e.message}")
    }
}

