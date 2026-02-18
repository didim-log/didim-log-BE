package com.didimlog.application.quote

import com.didimlog.domain.Quote
import com.didimlog.domain.repository.QuoteRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
    @DisplayName("시딩 로직은 누락된 큐레이션 명언을 보강한다")
    fun `시딩 로직 데이터 보강`() {
        every { quoteRepository.findAll() } returns emptyList()
        every { quoteRepository.saveAll(any<List<Quote>>()) } returns emptyList()
        every { quoteRepository.count() } returns 35L

        quoteService.seedQuotes()

        val quoteListSlot = slot<List<Quote>>()
        verify(atLeast = 1) { quoteRepository.saveAll(capture(quoteListSlot)) }
        assertThat(quoteListSlot.captured).isNotEmpty()
    }

    @Test
    @DisplayName("시딩 로직은 Unknown 저자를 큐레이션 저자로 교정한다")
    fun `시딩 로직 저자 교정`() {
        val existingUnknown = Quote(
            id = "q1",
            content = "말은 쉽다. 코드를 보여줘라.",
            author = "Unknown"
        )
        every { quoteRepository.findAll() } returns listOf(existingUnknown)
        every { quoteRepository.saveAll(any<List<Quote>>()) } returns emptyList()
        every { quoteRepository.count() } returns 36L

        quoteService.seedQuotes()

        verify(atLeast = 1) { quoteRepository.saveAll(any<List<Quote>>()) }
    }
}
