package com.didimlog.application.admin

import com.didimlog.domain.Quote
import com.didimlog.domain.repository.QuoteRepository
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

@DisplayName("QuoteAdminService 테스트")
class QuoteAdminServiceTest {

    private val quoteRepository: QuoteRepository = mockk()
    private val service = QuoteAdminService(quoteRepository)

    @Test
    @DisplayName("명언 목록을 페이지 단위로 조회한다")
    fun `명언 목록 조회`() {
        val pageable = PageRequest.of(0, 10)
        val page = PageImpl(listOf(Quote(id = "q1", content = "꾸준함", author = "익명")))
        every { quoteRepository.findAll(pageable) } returns page

        assertThat(service.getAllQuotes(pageable)).isSameAs(page)
    }

    @Test
    @DisplayName("저자명이 비어 있으면 Unknown으로 저장한다")
    fun `저자 없는 명언 생성`() {
        val saved = slot<Quote>()
        every { quoteRepository.save(capture(saved)) } answers { saved.captured.copy(id = "q1") }

        val result = service.createQuote("꾸준함이 답이다", "  ")

        assertThat(saved.captured.author).isEqualTo("Unknown")
        assertThat(result.id).isEqualTo("q1")
        assertThat(result.content).isEqualTo("꾸준함이 답이다")
    }

    @Test
    @DisplayName("저자명이 있으면 그대로 저장한다")
    fun `저자 있는 명언 생성`() {
        val saved = slot<Quote>()
        every { quoteRepository.save(capture(saved)) } answers { saved.captured }

        service.createQuote("꾸준함이 답이다", "디딤")

        assertThat(saved.captured.author).isEqualTo("디딤")
    }

    @Test
    @DisplayName("존재하는 명언만 삭제한다")
    fun `명언 삭제`() {
        val quote = Quote(id = "q1", content = "꾸준함", author = "익명")
        every { quoteRepository.findById("q1") } returns Optional.of(quote)
        every { quoteRepository.findById("missing") } returns Optional.empty()
        every { quoteRepository.delete(quote) } just runs

        service.deleteQuote("q1")
        val exception = assertThrows<BusinessException> { service.deleteQuote("missing") }

        verify(exactly = 1) { quoteRepository.delete(quote) }
        assertThat(exception.errorCode).isEqualTo(ErrorCode.COMMON_RESOURCE_NOT_FOUND)
        assertThat(exception.message).contains("id=missing")
    }
}
