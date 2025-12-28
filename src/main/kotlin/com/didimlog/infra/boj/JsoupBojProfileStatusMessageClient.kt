package com.didimlog.infra.boj

import com.didimlog.application.auth.boj.BojProfileStatusMessageClient
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class JsoupBojProfileStatusMessageClient : BojProfileStatusMessageClient {

    private val log = LoggerFactory.getLogger(JsoupBojProfileStatusMessageClient::class.java)

    override fun fetchStatusMessage(bojId: String): String? {
        val url = "https://www.acmicpc.net/user/$bojId"
        return try {
            val document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(10000)
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
                    
                    // blockquote의 경우 내부 div 태그를 제외한 텍스트만 추출
                    val text = if (selector.startsWith("blockquote")) {
                        // blockquote 내부의 첫 번째 텍스트 노드만 추출 (div.user-menu 전의 텍스트)
                        val cloned = element.clone()
                        cloned.select("div").remove() // div 태그 제거
                        cloned.text().trim()
                    } else {
                        element.text().trim()
                    }
                    if (text.isNotBlank()) text else null
                }
                .firstOrNull { it.isNotBlank() }

            candidate ?: run {
                log.warn("BOJ 프로필 상태 메시지를 찾을 수 없음: bojId=$bojId")
                null
            }
        } catch (e: org.jsoup.HttpStatusException) {
            when (e.statusCode) {
                404 -> {
                    log.warn("BOJ 프로필을 찾을 수 없음: bojId=$bojId, status=404")
                }
                403 -> {
                    log.warn("BOJ 프로필 접근 거부: bojId=$bojId, status=403")
                }
                else -> {
                    log.warn("BOJ 프로필 상태 메시지 조회 실패: bojId=$bojId, status=${e.statusCode}, message=${e.message}")
                }
            }
            null
        } catch (e: Exception) {
            log.warn("BOJ 프로필 상태 메시지 조회 실패: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}", e)
            null
        }
    }
}

