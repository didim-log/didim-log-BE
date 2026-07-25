package com.didimlog.domain.repository

import com.didimlog.domain.enums.TemplateOwnershipType
import org.springframework.data.annotation.Id
import java.time.LocalDateTime

/**
 * 템플릿 목록 요약 조회용 projection.
 * content 필드를 제외해 초기 목록 조회 비용을 줄인다.
 */
data class TemplateSummaryView(
    @Id
    val id: String? = null,
    val studentId: String? = null,
    val title: String,
    val type: TemplateOwnershipType,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
