package com.didimlog.infra.util

import org.jsoup.Jsoup

/**
 * HTML에서 텍스트만 추출하는 유틸리티
 */
object HtmlTextExtractor {

    /**
     * HTML 문자열에서 텍스트만 추출한다.
     * HTML 태그는 제거하고 순수 텍스트만 반환한다.
     *
     * @param html HTML 문자열 (nullable)
     * @return 추출된 텍스트 (null이거나 빈 문자열이면 null 반환)
     */
    fun extractText(html: String?): String? {
        if (html.isNullOrBlank()) {
            return null
        }

        return try {
            val doc = Jsoup.parse(html)
            val text = doc.text()
            text.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // 파싱 실패 시 원본 HTML 반환 (최소한의 대응)
            html
        }
    }
}















