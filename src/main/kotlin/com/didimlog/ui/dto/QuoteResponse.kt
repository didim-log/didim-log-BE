package com.didimlog.ui.dto

import com.didimlog.application.dashboard.QuoteInfo
import com.didimlog.domain.Quote

/**
 * 명언 응답 DTO
 */
data class QuoteResponse(
    val id: String,
    val content: String,
    val author: String
) {
    companion object {
        fun from(quote: Quote): QuoteResponse {
            return QuoteResponse(
                id = quote.id ?: "",
                content = quote.content,
                author = quote.author
            )
        }

        fun from(quoteInfo: QuoteInfo): QuoteResponse {
            return QuoteResponse(
                id = quoteInfo.id,
                content = quoteInfo.content,
                author = quoteInfo.author
            )
        }
    }
}

