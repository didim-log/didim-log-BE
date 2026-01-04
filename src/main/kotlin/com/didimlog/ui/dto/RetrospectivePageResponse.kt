package com.didimlog.ui.dto

import com.didimlog.domain.Retrospective
import org.springframework.data.domain.Page

/**
 * 회고 페이지 응답 DTO
 */
data class RetrospectivePageResponse(
    val content: List<RetrospectiveResponse>,
    val totalElements: Long,
    val totalPages: Int,
    val currentPage: Int,
    val size: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
) {
    companion object {
        fun from(page: Page<Retrospective>): RetrospectivePageResponse {
            return RetrospectivePageResponse(
                content = page.content.map { RetrospectiveResponse.from(it) },
                totalElements = page.totalElements,
                totalPages = page.totalPages,
                currentPage = page.number,
                size = page.size,
                hasNext = page.hasNext(),
                hasPrevious = page.hasPrevious()
            )
        }
    }
}










