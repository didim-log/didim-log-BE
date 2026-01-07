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

            // 언어 감지: '다국어' 라벨 확인 후 언어 판별
            val isMultilingual = checkMultilingualLabel(doc)
            val combinedText = (descriptionHtml ?: "") + (inputDescriptionHtml ?: "") + (outputDescriptionHtml ?: "")
            val detectedLanguage = if (!isMultilingual) {
                "ko" // 라벨 없으면 토종 한국어 문제
            } else {
                // 라벨 있으면 상세 언어(영어/일어 등) 분석
                detectDetailLanguage(combinedText)
            }

            ProblemDetails(
                descriptionHtml = descriptionHtml,
                inputDescriptionHtml = inputDescriptionHtml,
                outputDescriptionHtml = outputDescriptionHtml,
                sampleInputs = sampleInputs,
                sampleOutputs = sampleOutputs,
                language = detectedLanguage
            )
        } catch (e: Exception) {
            log.warn("BOJ 크롤링 실패: problemId=$problemId, error=${e.message}", e)
            null
        }
    }

    /**
     * BOJ 페이지에서 '다국어' 라벨이 존재하는지 확인한다.
     *
     * @param doc 파싱된 HTML 문서
     * @return '다국어' 라벨이 있으면 true, 없으면 false
     */
    private fun checkMultilingualLabel(doc: Document): Boolean {
        // 다양한 selector로 라벨 요소 확인
        val labelSelectors = listOf(
            ".page-header .label-info",
            ".problem-label-multilingual",
            "span.label",
            ".label-info",
            "#problem-title ~ .label",
            ".page-header span",
            "h1 ~ span"
        )

        // 각 selector로 찾은 요소들의 텍스트에서 '다국어' 확인
        for (selector in labelSelectors) {
            val elements = doc.select(selector)
            for (element in elements) {
                val text = element.text()
                if (text.contains("다국어")) {
                    return true
                }
            }
        }

        // 추가로 페이지 헤더 영역에서 '다국어' 텍스트 검색
        val headerElements = doc.select(".page-header, #problem-title, h1")
        for (element in headerElements) {
            val text = element.text()
            if (text.contains("다국어")) {
                return true
            }
        }

        // 마지막으로 페이지 전체에서 '다국어' 텍스트 검색 (최후의 수단)
        val pageText = doc.text()
        return pageText.contains("다국어")
    }

    /**
     * 텍스트에서 상세 언어를 감지한다.
     * '다국어' 라벨이 있는 경우에만 호출되며, 다양한 언어(영어, 일본어, 중국어 등)를 정확하게 판별한다.
     *
     * @param text 분석할 텍스트
     * @return "ko" (한국어), "en" (영어), "ja" (일본어), "zh" (중국어), "other" (기타)
     */
    private fun detectDetailLanguage(text: String): String {
        if (text.isBlank()) {
            return "ko" // 기본값
        }

        var koreanCount = 0      // 한글: AC00 (가) ~ D7A3 (힣)
        var japaneseCount = 0    // 히라가나: 3040~309F, 가타카나: 30A0~30FF
        var chineseCount = 0     // 중국어 한자: 4E00~9FFF
        var englishCount = 0     // 영어: A-Z, a-z
        var totalLetterCount = 0

        text.forEach { char ->
            if (!char.isLetter()) return@forEach
            totalLetterCount++

            val codePoint = char.code

            // 한글 판별
            if (codePoint in 0xAC00..0xD7A3) {
                koreanCount++
                return@forEach
            }

            // 일본어 판별 (히라가나, 가타카나)
            if (codePoint in 0x3040..0x309F || codePoint in 0x30A0..0x30FF) {
                japaneseCount++
                return@forEach
            }

            // 한자 판별 (일본어/중국어 공통)
            if (codePoint in 0x4E00..0x9FFF) {
                // 한자는 일본어와 중국어 모두에서 사용되므로 별도 카운트
                return@forEach
            }

            // 영어 판별
            if (codePoint in 0x0041..0x005A || codePoint in 0x0061..0x007A) {
                englishCount++
                return@forEach
            }
        }

        // 한자 개수 계산 (일본어/중국어 구분용)
        val kanjiCount = text.count { char ->
            val codePoint = char.code
            codePoint in 0x4E00..0x9FFF
        }

        // 한자가 있고 히라가나/가타카나가 있으면 일본어로 간주
        if (kanjiCount > 0 && japaneseCount > 0) {
            japaneseCount += kanjiCount
        } else if (kanjiCount > 0) {
            // 한자만 있고 히라가나/가타카나가 없으면 중국어로 간주
            chineseCount = kanjiCount
        }

        if (totalLetterCount == 0) {
            return "ko" // 기본값
        }

        // 각 언어별 비율 계산
        val koreanRatio = koreanCount.toDouble() / totalLetterCount
        val japaneseRatio = japaneseCount.toDouble() / totalLetterCount
        val chineseRatio = chineseCount.toDouble() / totalLetterCount
        val englishRatio = englishCount.toDouble() / totalLetterCount

        // 가장 높은 비율의 언어로 판별 (최소 10% 이상이어야 함)
        val threshold = 0.1

        // 한국어 우선: 한글이 5개 이상이면 무조건 "ko"
        if (koreanCount >= 5) {
            return "ko"
        }

        return when {
            koreanRatio >= threshold && koreanRatio >= japaneseRatio &&
                koreanRatio >= chineseRatio && koreanRatio >= englishRatio -> "ko"
            japaneseRatio >= threshold && japaneseRatio >= chineseRatio &&
                japaneseRatio >= englishRatio -> "ja"
            chineseRatio >= threshold && chineseRatio >= englishRatio -> "zh"
            englishRatio >= threshold -> "en"
            else -> "other" // 기타 언어 또는 판별 불가
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
    val sampleOutputs: List<String>,
    val language: String = "ko"
)
