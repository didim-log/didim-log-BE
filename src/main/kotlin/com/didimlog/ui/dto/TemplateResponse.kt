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
    val isDefaultSuccess: Boolean,
    val isDefaultFail: Boolean,
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
                isDefaultSuccess = template.isDefaultSuccess,
                isDefaultFail = template.isDefaultFail,
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

/**
 * 템플릿 섹션 프리셋 응답 DTO
 */
data class TemplatePresetResponse(
    val label: String,
    val markdownContent: String,
    val category: String,
    val tooltip: String,
    val contentGuide: String?
) {
    companion object {
        fun from(preset: com.didimlog.domain.template.SectionPreset): TemplatePresetResponse {
            return TemplatePresetResponse(
                label = preset.title,
                markdownContent = preset.markdownContent,
                category = preset.category.name,
                tooltip = preset.guide,
                contentGuide = preset.contentGuide
            )
        }
    }
}




