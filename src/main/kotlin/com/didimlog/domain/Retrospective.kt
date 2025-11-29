package com.didimlog.domain

import java.time.LocalDateTime
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 문제 풀이 후 작성하는 회고를 표현하는 도메인 객체
 * 대용량 텍스트이므로 별도 컬렉션으로 분리한다.
 */
@Document(collection = "retrospectives")
data class Retrospective(
    @Id
    val id: String? = null,
    val studentId: String,
    val problemId: String,
    val content: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
) {

    init {
        validateContent(content)
    }

    fun updateContent(newContent: String): Retrospective {
        validateContent(newContent)
        return copy(content = newContent)
    }

    private fun validateContent(target: String) {
        require(target.length >= 10) { "회고 내용은 10자 이상이어야 합니다." }
    }
}


