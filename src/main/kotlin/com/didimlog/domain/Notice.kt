package com.didimlog.domain

import java.time.LocalDateTime
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 공지사항을 표현하는 도메인 객체
 */
@Document(collection = "notices")
data class Notice(
    @Id
    val id: String? = null,
    val title: String,
    val content: String,
    val isPinned: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
) {
    init {
        validateTitle(title)
        validateContent(content)
    }

    fun update(title: String? = null, content: String? = null, isPinned: Boolean? = null): Notice {
        val newTitle = title ?: this.title
        val newContent = content ?: this.content
        val newIsPinned = isPinned ?: this.isPinned

        if (title != null) {
            validateTitle(newTitle)
        }
        if (content != null) {
            validateContent(newContent)
        }

        return copy(
            title = newTitle,
            content = newContent,
            isPinned = newIsPinned,
            updatedAt = LocalDateTime.now()
        )
    }

    private fun validateTitle(title: String) {
        require(title.isNotBlank()) { "공지사항 제목은 필수입니다." }
        require(title.length <= 200) { "공지사항 제목은 200자 이하여야 합니다." }
    }

    private fun validateContent(content: String) {
        require(content.isNotBlank()) { "공지사항 내용은 필수입니다." }
        require(content.length <= 10000) { "공지사항 내용은 10000자 이하여야 합니다." }
    }
}

