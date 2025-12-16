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
