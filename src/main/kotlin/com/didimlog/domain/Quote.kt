package com.didimlog.domain

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 동기부여 명언을 표현하는 도메인 객체
 * 사용자에게 랜덤으로 제공되어 학습 동기를 부여한다.
 */
@Document(collection = "quotes")
data class Quote(
    @Id
    val id: String? = null,
    val content: String,
    val author: String = "Unknown"
) {
    init {
        require(content.isNotBlank()) { "명언 내용은 필수입니다." }
        require(author.isNotBlank()) { "저자명은 필수입니다." }
    }
}