package com.didimlog.ui.dto

import com.didimlog.domain.template.Template
import java.time.LocalDateTime

/**
 * 템플릿 응답 DTO
 */
data class TemplateResponse(
    val id: String,
    val studentId: String?,
    val title: String,
    val content: String,
    val type: String, // SYSTEM, CUSTOM
    val isDefault: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(template: Template): TemplateResponse {
            return TemplateResponse(
                id = template.id ?: "",
                studentId = template.studentId,
                title = template.title,
                content = template.content,
                type = template.type.name,
                isDefault = template.isDefault,
                createdAt = template.createdAt,
                updatedAt = template.updatedAt
            )
        }
    }
}

/**
 * 템플릿 렌더링 응답 DTO
 */
data class TemplateRenderResponse(
    val renderedContent: String
)














