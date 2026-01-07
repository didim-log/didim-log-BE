package com.didimlog.infra.crawler

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BojCrawler 언어 판별 테스트")
class BojCrawlerTest {

    private val bojCrawler = BojCrawler()

    @Test
    @DisplayName("한글 텍스트는 'ko'로 판별된다")
    fun `한글 텍스트는 ko로 판별`() {
        // given
        val koreanText = "이 문제는 두 수의 합을 구하는 문제입니다."

        // when
        val result = testDetectLanguage(koreanText)

        // then
        assertThat(result).isEqualTo("ko")
    }

    @Test
    @DisplayName("영어 텍스트는 'en'으로 판별된다")
    fun `영어 텍스트는 en으로 판별`() {
        // given
        val englishText = "This problem asks you to find the sum of two numbers."

        // when
        val result = testDetectLanguage(englishText)

        // then
        assertThat(result).isEqualTo("en")
    }

    @Test
    @DisplayName("일본어 텍스트(히라가나 포함)는 'ja'로 판별된다")
    fun `일본어 텍스트는 ja로 판별`() {
        // given
        val japaneseText = "この問題は二つの数の和を求める問題です。"

        // when
        val result = testDetectLanguage(japaneseText)

        // then
        assertThat(result).isEqualTo("ja")
    }

    @Test
    @DisplayName("중국어 텍스트(한자만)는 'zh'로 판별된다")
    fun `중국어 텍스트는 zh로 판별`() {
        // given
        val chineseText = "这个问题要求你找到两个数字的总和。"

        // when
        val result = testDetectLanguage(chineseText)

        // then
        assertThat(result).isEqualTo("zh")
    }

    @Test
    @DisplayName("한글이 5자 이상이면 무조건 'ko'로 판별된다")
    fun `한글 5자 이상은 무조건 ko`() {
        // given
        val mixedText = "한글영어English문제입니다Mixed"

        // when
        val result = testDetectLanguage(mixedText)

        // then
        assertThat(result).isEqualTo("ko")
    }

    @Test
    @DisplayName("빈 문자열은 기본값 'ko'를 반환한다")
    fun `빈 문자열은 기본값 ko`() {
        // given
        val emptyText = ""

        // when
        val result = testDetectLanguage(emptyText)

        // then
        assertThat(result).isEqualTo("ko")
    }

    @Test
    @DisplayName("한글이 적고 영어가 많은 경우 'en'으로 판별된다")
    fun `한글 적고 영어 많으면 en`() {
        // given
        val text = "This is an English problem with 한글."

        // when
        val result = testDetectLanguage(text)

        // then
        assertThat(result).isEqualTo("en")
    }

    @Test
    @DisplayName("일본어와 한자가 함께 있으면 'ja'로 판별된다")
    fun `일본어와 한자 함께 있으면 ja`() {
        // given
        val text = "この問題は数学の問題です。"

        // when
        val result = testDetectLanguage(text)

        // then
        assertThat(result).isEqualTo("ja")
    }

    @Test
    @DisplayName("한자만 있고 히라가나/가타카나가 없으면 'zh'로 판별된다")
    fun `한자만 있으면 zh`() {
        // given
        val text = "这是一个数学问题"

        // when
        val result = testDetectLanguage(text)

        // then
        assertThat(result).isEqualTo("zh")
    }

    @Test
    @DisplayName("문자가 없으면 기본값 'ko'를 반환한다")
    fun `문자 없으면 기본값 ko`() {
        // given
        val text = "1234567890"

        // when
        val result = testDetectLanguage(text)

        // then
        // 숫자만 있으면 totalLetterCount가 0이 되어 기본값 "ko" 반환
        assertThat(result).isEqualTo("ko")
    }

    @Test
    @DisplayName("다국어 라벨이 없으면 'ko'로 판별된다")
    fun `다국어 라벨 없으면 ko`() {
        // given
        val koreanText = "이 문제는 한국어로 작성된 문제입니다."

        // when
        val result = testDetectDetailLanguage(koreanText)

        // then
        assertThat(result).isEqualTo("ko")
    }

    /**
     * 리플렉션을 사용하여 private detectDetailLanguage 메서드를 테스트합니다.
     */
    private fun testDetectLanguage(text: String): String {
        // detectDetailLanguage는 다국어 라벨이 있을 때만 호출되므로,
        // 테스트에서는 detectDetailLanguage를 직접 호출합니다.
        return testDetectDetailLanguage(text)
    }

    private fun testDetectDetailLanguage(text: String): String {
        val method = BojCrawler::class.java.getDeclaredMethod("detectDetailLanguage", String::class.java)
        method.isAccessible = true
        return method.invoke(bojCrawler, text) as String
    }
}

