package com.didimlog.infra.boj

import com.didimlog.application.auth.boj.BojProfileStatusMessageClient
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class JsoupBojProfileStatusMessageClient : BojProfileStatusMessageClient {

    private val log = LoggerFactory.getLogger(JsoupBojProfileStatusMessageClient::class.java)

    override fun fetchStatusMessage(bojId: String): String? {
        val url = "https://acmicpc.net/user/$bojId"
        return try {
            val document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(5000)
                .get()

            // BOJ 프로필의 상태 메시지 영역은 구조가 바뀔 수 있어 다중 셀렉터를 시도한다.
            val selectors = listOf(
                "#status-message",
                ".status-message",
                ".user-status-message",
                "#problem-header .user-status",
                ".profile-status"
            )

            val candidate = selectors.asSequence()
                .mapNotNull { selector -> document.selectFirst(selector)?.text()?.trim() }
                .firstOrNull { it.isNotBlank() }

            candidate ?: run {
                // 마지막 fallback: 페이지 텍스트에서 상태 메시지 키워드 영역을 찾기 어려우면 null
                null
            }
        } catch (e: Exception) {
            log.warn("BOJ 프로필 상태 메시지 조회 실패: bojId=$bojId, message=${e.message}")
            null
        }
    }
}

