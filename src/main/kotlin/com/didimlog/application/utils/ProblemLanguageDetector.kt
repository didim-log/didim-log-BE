package com.didimlog.application.utils

import com.didimlog.domain.Problem
import org.jsoup.Jsoup

/**
 * 문제 본문/제목 기반의 경량 언어 판별기.
 *
 * - 반환값: "ko" | "en" | null(판별 불가)
 * - null은 입력 텍스트가 부족하거나 신뢰도가 낮은 경우를 의미한다.
 */
object ProblemLanguageDetector {

    fun detect(problem: Problem): String? {
        val texts = listOf(
            problem.title,
            problem.descriptionHtml,
            problem.inputDescriptionHtml,
            problem.outputDescriptionHtml,
            problem.sampleInputs?.joinToString("\n"),
            problem.sampleOutputs?.joinToString("\n")
        )

        return detectFromTexts(texts)
    }

    fun detectFromTexts(texts: List<String?>): String? {
        val mergedText = texts
            .asSequence()
            .mapNotNull { normalizeText(it) }
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")

        if (mergedText.isBlank()) {
            return null
        }

        var koreanCount = 0
        var englishCount = 0

        for (char in mergedText) {
            when {
                char in '가'..'힣' -> koreanCount++
                char in 'a'..'z' || char in 'A'..'Z' -> englishCount++
            }
        }

        if (koreanCount >= 5 && koreanCount >= englishCount) {
            return "ko"
        }

        if (englishCount >= 10 && englishCount > koreanCount * 2) {
            return "en"
        }

        if (koreanCount > 0 && englishCount == 0) {
            return "ko"
        }

        if (englishCount > 0 && koreanCount == 0) {
            return "en"
        }

        return null
    }

    private fun normalizeText(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return Jsoup.parse(raw).text()
    }
}
