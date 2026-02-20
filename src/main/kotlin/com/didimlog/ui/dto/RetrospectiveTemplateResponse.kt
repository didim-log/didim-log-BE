package com.didimlog.ui.dto

/**
 * 회고 템플릿 응답 DTO
 */
data class RetrospectiveTemplateResponse(
    val template: String,
    val fallbackUsed: Boolean = false,
    val fallbackReason: String? = null
)
