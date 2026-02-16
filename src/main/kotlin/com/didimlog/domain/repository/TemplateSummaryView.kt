package com.didimlog.domain.repository

import com.didimlog.domain.enums.TemplateOwnershipType
import java.time.LocalDateTime

/**
 * 템플릿 목록 요약 조회용 projection.
 * content 필드를 제외해 초기 목록 조회 비용을 줄인다.
 */
interface TemplateSummaryView {
    val id: String?
    val studentId: String?
    val title: String
    val type: TemplateOwnershipType
    val createdAt: LocalDateTime
    val updatedAt: LocalDateTime
}

