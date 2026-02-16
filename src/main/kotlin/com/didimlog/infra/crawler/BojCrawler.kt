package com.didimlog.infra.crawler

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 백준 온라인 저지(BOJ) 사이트를 크롤링하여 문제 상세 정보를 수집하는 컴포넌트
 * Rate Limit을 준수하기 위해 사용하는 쪽에서 지연 시간을 두어야 합니다.
 */
@Component
class BojCrawler {

    private val log = LoggerFactory.getLogger(BojCrawler::class.java)

    /**
     * BOJ 문제 페이지를 크롤링하여 상세 정보를 가져온다.
     *
     * @param problemId 문제 ID
     * @return 문제 상세 정보 (크롤링 실패 시 null)
     */
    fun crawlProblemDetails(problemId: String): ProblemDetails? {
        return try {
            val url = "https://www.acmicpc.net/problem/$problemId"
            val doc = fetchDocument(url)

            val descriptionHtml = extractDescription(doc)
            val inputDescriptionHtml = extractInputDescription(doc)
            val outputDescriptionHtml = extractOutputDescription(doc)
            val (sampleInputs, sampleOutputs) = extractSampleData(doc)

            ProblemDetails(
                descriptionHtml = descriptionHtml,
                inputDescriptionHtml = inputDescriptionHtml,
                outputDescriptionHtml = outputDescriptionHtml,
                sampleInputs = sampleInputs,
                sampleOutputs = sampleOutputs
            )
        } catch (e: Exception) {
            log.warn("BOJ 크롤링 실패: problemId=$problemId, error=${e.message}", e)
            null
        }
    }

    private fun fetchDocument(url: String): Document {
        return Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .timeout(10000)
            .get()
    }

    private fun extractDescription(doc: Document): String? {
        val element = doc.selectFirst("#problem_description")
        return element?.html()
    }

    private fun extractInputDescription(doc: Document): String? {
        val element = doc.selectFirst("#problem_input")
        return element?.html()
    }

    private fun extractOutputDescription(doc: Document): String? {
        val element = doc.selectFirst("#problem_output")
        return element?.html()
    }

    private fun extractSampleData(doc: Document): Pair<List<String>, List<String>> {
        val sampleInputs = mutableListOf<String>()
        val sampleOutputs = mutableListOf<String>()

        val inputElements = doc.select("#sample-input-1, #sample-input-2, #sample-input-3, #sample-input-4, #sample-input-5")
        val outputElements = doc.select("#sample-output-1, #sample-output-2, #sample-output-3, #sample-output-4, #sample-output-5")

        val maxSize = minOf(inputElements.size, outputElements.size)
        for (i in 0 until maxSize) {
            sampleInputs.add(inputElements[i].text())
            sampleOutputs.add(outputElements[i].text())
        }

        return Pair(sampleInputs, sampleOutputs)
    }

    /**
     * 문제 상세 본문의 언어를 판별한다.
     *
     * 우선순위:
     * 1) 한글 5자 이상이면 한국어(ko)
     * 2) 일본어 가나가 포함되면 일본어(ja)
     * 3) 한자만 존재하면 중국어(zh)
     * 4) 영문 비중이 가장 높으면 영어(en)
     * 5) 그 외 기본값 한국어(ko)
     */
    @Suppress("unused")
    private fun detectDetailLanguage(text: String): String {
        if (text.isBlank()) {
            return "ko"
        }

        var koreanCount = 0
        var englishCount = 0
        var japaneseKanaCount = 0
        var cjkHanCount = 0

        for (char in text) {
            when {
                char in '가'..'힣' -> koreanCount++
                char in 'a'..'z' || char in 'A'..'Z' -> englishCount++
                char in '\u3040'..'\u30ff' -> japaneseKanaCount++
                char in '\u4e00'..'\u9fff' -> cjkHanCount++
            }
        }

        if (koreanCount >= 5) {
            return "ko"
        }
        if (japaneseKanaCount > 0) {
            return "ja"
        }
        if (cjkHanCount > 0 && koreanCount == 0) {
            return "zh"
        }

        val totalLetterCount = koreanCount + englishCount + japaneseKanaCount + cjkHanCount
        if (totalLetterCount == 0) {
            return "ko"
        }
        if (englishCount > koreanCount && englishCount > cjkHanCount) {
            return "en"
        }
        if (koreanCount > 0) {
            return "ko"
        }
        if (englishCount > 0) {
            return "en"
        }
        if (cjkHanCount > 0) {
            return "zh"
        }
        return "ko"
    }
}

/**
 * BOJ 크롤링으로 수집한 문제 상세 정보
 */
data class ProblemDetails(
    val descriptionHtml: String?,
    val inputDescriptionHtml: String?,
    val outputDescriptionHtml: String?,
    val sampleInputs: List<String>,
    val sampleOutputs: List<String>
)
