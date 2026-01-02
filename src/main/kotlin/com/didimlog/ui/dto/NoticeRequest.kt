package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 공지사항 작성 요청 DTO
 */
data class NoticeCreateRequest(
    @field:NotBlank(message = "제목은 필수입니다.")
    @field:Size(max = 200, message = "제목은 200자 이하여야 합니다.")
    val title: String,
    
    @field:NotBlank(message = "내용은 필수입니다.")
    @field:Size(min = 1, max = 10000, message = "내용은 1자 이상 10000자 이하여야 합니다.")
    val content: String,
    
    val isPinned: Boolean = false
)

/**
 * 공지사항 수정 요청 DTO
 */
data class NoticeUpdateRequest(
    @field:Size(max = 200, message = "제목은 200자 이하여야 합니다.")
    val title: String? = null,
    
    @field:Size(max = 10000, message = "내용은 10000자 이하여야 합니다.")
    val content: String? = null,
    
    val isPinned: Boolean? = null
)

