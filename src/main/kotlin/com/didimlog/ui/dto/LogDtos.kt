package com.didimlog.ui.dto

data class LogTemplateResponse(
    val markdown: String
)

data class AiReviewResponse(
    val review: String,
    val cached: Boolean
)


