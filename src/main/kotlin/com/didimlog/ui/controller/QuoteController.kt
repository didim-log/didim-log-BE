package com.didimlog.ui.controller

import com.didimlog.application.quote.QuoteService
import com.didimlog.ui.dto.QuoteResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Quote", description = "명언 관련 API")
@RestController
@RequestMapping("/api/v1/quotes")
class QuoteController(
    private val quoteService: QuoteService
) {

    @Operation(
        summary = "랜덤 명언 조회",
        description = "DB에 저장된 명언 중 하나를 무작위로 반환합니다."
    )
    @GetMapping("/random")
    fun getRandomQuote(): ResponseEntity<QuoteResponse> {
        val quote = quoteService.getRandomQuote()
            ?: return ResponseEntity.noContent().build()

        val response = QuoteResponse.from(quote)
        return ResponseEntity.ok(response)
    }
}


