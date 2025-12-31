package com.didimlog.ui.dto

import com.didimlog.domain.Notice
import java.time.LocalDateTime

/**
 * 공지사항 응답 DTO
 */
data class NoticeResponse(
    val id: String,
    val title: String,
    val content: String,
    val isPinned: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(notice: Notice): NoticeResponse {
            return NoticeResponse(
                id = notice.id ?: "",
                title = notice.title,
                content = notice.content,
                isPinned = notice.isPinned,
                createdAt = notice.createdAt,
                updatedAt = notice.updatedAt
            )
        }
    }
}

