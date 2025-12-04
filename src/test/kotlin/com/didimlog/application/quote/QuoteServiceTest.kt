package com.didimlog.application.quote

import com.didimlog.domain.Quote
import com.didimlog.domain.repository.QuoteRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("QuoteService 테스트")
class QuoteServiceTest {

    private val quoteRepository: QuoteRepository = mockk()

    private val quoteService = QuoteService(quoteRepository)

    @Test
    @DisplayName("랜덤 명언을 조회할 수 있다")
    fun `랜덤 명언 조회 성공`() {
        // given
        val quotes = listOf(
            Quote(content = "명언 1", author = "작가 1"),
            Quote(content = "명언 2", author = "작가 2"),
            Quote(content = "명언 3", author = "작가 3")
        )

        every { quoteRepository.count() } returns 3L
        every { quoteRepository.findAll() } returns quotes

        // when
        val result = quoteService.getRandomQuote()

        // then
        assertThat(result).isNotNull()
        assertThat(result?.content).isIn(quotes.map { it.content })
        verify(exactly = 1) { quoteRepository.count() }
        verify(exactly = 1) { quoteRepository.findAll() }
    }

    @Test
    @DisplayName("DB에 명언이 없으면 null을 반환한다")
    fun `명언 없음 시 null 반환`() {
        // given
        every { quoteRepository.count() } returns 0L

        // when
        val result = quoteService.getRandomQuote()

        // then
        assertThat(result).isNull()
        verify(exactly = 1) { quoteRepository.count() }
    }

    @Test
    @DisplayName("시딩 로직은 DB에 명언이 있으면 건너뛴다")
    fun `시딩 로직 건너뛰기`() {
        // given
        every { quoteRepository.count() } returns 1L

        // when
        quoteService.seedQuotes()

        // then
        verify(exactly = 1) { quoteRepository.count() }
        verify(exactly = 0) { quoteRepository.saveAll(any<List<Quote>>()) }
    }

    @Test
    @DisplayName("시딩 로직은 DB에 명언이 없으면 초기 데이터를 저장한다")
    fun `시딩 로직 데이터 저장`() {
        // given
        every { quoteRepository.count() } returns 0L
        every { quoteRepository.saveAll(any<List<Quote>>()) } returns emptyList()

        // when
        quoteService.seedQuotes()

        // then
        verify(exactly = 1) { quoteRepository.count() }
        verify(exactly = 1) { quoteRepository.saveAll(any<List<Quote>>()) }
    }
}

