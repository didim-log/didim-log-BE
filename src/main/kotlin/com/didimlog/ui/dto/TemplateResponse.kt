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
        fun from(
            template: Template,
            defaultSuccessTemplateId: String? = null,
            defaultFailTemplateId: String? = null
        ): TemplateResponse {
            val templateId = template.id ?: ""
            return TemplateResponse(
                id = templateId,
                studentId = template.studentId,
                title = template.title,
                content = template.content,
                type = template.type.name,
                // 기본 템플릿 상태는 Student 엔티티의 default*TemplateId를 Source of Truth로 사용한다.
                isDefaultSuccess = defaultSuccessTemplateId != null && defaultSuccessTemplateId == templateId,
                isDefaultFail = defaultFailTemplateId != null && defaultFailTemplateId == templateId,
                createdAt = template.createdAt,
                updatedAt = template.updatedAt
            )
        }
    }
}

/**
 * 템플릿 목록 요약 응답 DTO
 * 목록 화면의 초기 로딩 성능을 위해 content 필드를 제외한다.
 */
data class TemplateSummaryResponse(
    val id: String,
    val studentId: String?,
    val title: String,
    val type: String, // SYSTEM, CUSTOM
    val isDefaultSuccess: Boolean,
    val isDefaultFail: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(
            template: Template,
            defaultSuccessTemplateId: String? = null,
            defaultFailTemplateId: String? = null
        ): TemplateSummaryResponse {
            val templateId = template.id ?: ""
            return TemplateSummaryResponse(
                id = templateId,
                studentId = template.studentId,
                title = template.title,
                type = template.type.name,
                isDefaultSuccess = defaultSuccessTemplateId != null && defaultSuccessTemplateId == templateId,
                isDefaultFail = defaultFailTemplateId != null && defaultFailTemplateId == templateId,
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
    val renderedContent: String,
    val fallbackUsed: Boolean = false,
    val fallbackReason: String? = null
)

/**
 * 템플릿 섹션 프리셋 응답 DTO
 * 프론트엔드와의 호환성을 위해 API 명세서 기준 필드명 사용
 */
data class TemplatePresetResponse(
    val title: String,
    val guide: String,
    val category: String,
    val markdownContent: String,
    val contentGuide: String?
) {
    companion object {
        fun from(preset: com.didimlog.domain.template.SectionPreset): TemplatePresetResponse {
            return TemplatePresetResponse(
                title = preset.title,
                guide = preset.guide,
                category = preset.category.name,
                markdownContent = preset.markdownContent,
                contentGuide = preset.contentGuide
            )
        }
    }
}

