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

            val detectedLanguage = detectLanguage(doc, descriptionHtml, inputDescriptionHtml, outputDescriptionHtml)

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

    private fun detectLanguage(
        doc: Document,
        descriptionHtml: String?,
        inputDescriptionHtml: String?,
        outputDescriptionHtml: String?
    ): String {
        val isMultilingual = checkMultilingualLabel(doc)
        
        if (!isMultilingual) {
            return "ko"
        }

        val combinedText = (descriptionHtml ?: "") + (inputDescriptionHtml ?: "") + (outputDescriptionHtml ?: "")
        return detectDetailLanguage(combinedText)
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
            return "ko"
        }

        val languageCounts = countLanguageCharacters(text)
        val kanjiCount = countKanji(text)
        
        adjustLanguageCounts(languageCounts, kanjiCount)

        if (languageCounts.totalLetterCount == 0) {
            return "ko"
        }

        if (languageCounts.koreanCount >= 5) {
            return "ko"
        }

        return determineLanguageByRatio(languageCounts)
    }

    private data class LanguageCounts(
        var koreanCount: Int = 0,
        var japaneseCount: Int = 0,
        var chineseCount: Int = 0,
        var englishCount: Int = 0,
        var totalLetterCount: Int = 0
    )

    private fun countLanguageCharacters(text: String): LanguageCounts {
        val counts = LanguageCounts()

        text.forEach { char ->
            if (!char.isLetter()) {
                return@forEach
            }
            counts.totalLetterCount++

            val codePoint = char.code

            when {
                codePoint in 0xAC00..0xD7A3 -> counts.koreanCount++
                codePoint in 0x3040..0x309F || codePoint in 0x30A0..0x30FF -> counts.japaneseCount++
                codePoint in 0x0041..0x005A || codePoint in 0x0061..0x007A -> counts.englishCount++
            }
        }

        return counts
    }

    private fun countKanji(text: String): Int {
        return text.count { char ->
            val codePoint = char.code
            codePoint in 0x4E00..0x9FFF
        }
    }

    private fun adjustLanguageCounts(counts: LanguageCounts, kanjiCount: Int) {
        if (kanjiCount == 0) {
            return
        }

        if (counts.japaneseCount > 0) {
            counts.japaneseCount += kanjiCount
            return
        }

        counts.chineseCount = kanjiCount
    }

    private fun determineLanguageByRatio(counts: LanguageCounts): String {
        val threshold = 0.1
        val total = counts.totalLetterCount.toDouble()

        val koreanRatio = counts.koreanCount.toDouble() / total
        val japaneseRatio = counts.japaneseCount.toDouble() / total
        val chineseRatio = counts.chineseCount.toDouble() / total
        val englishRatio = counts.englishCount.toDouble() / total

        when {
            koreanRatio >= threshold && koreanRatio >= japaneseRatio &&
                koreanRatio >= chineseRatio && koreanRatio >= englishRatio -> return "ko"
            japaneseRatio >= threshold && japaneseRatio >= chineseRatio &&
                japaneseRatio >= englishRatio -> return "ja"
            chineseRatio >= threshold && chineseRatio >= englishRatio -> return "zh"
            englishRatio >= threshold -> return "en"
        }

        return "other"
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
