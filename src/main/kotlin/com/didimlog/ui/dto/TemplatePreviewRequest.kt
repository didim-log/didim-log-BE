package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * 템플릿 미리보기 요청 DTO
 */
data class TemplatePreviewRequest(
    @field:NotBlank(message = "템플릿 내용은 필수입니다.")
    val templateContent: String,

    @field:NotNull(message = "문제 ID는 필수입니다.")
    val problemId: Long,

    /**
     * 프로그래밍 언어 코드 (선택사항)
     * 코드 블록의 언어 태그로 사용됩니다.
     * 예: "JAVA", "KOTLIN", "PYTHON", "CPP" 등
     * 제공되지 않으면 code 필드에서 자동 감지됩니다.
     */
    val programmingLanguage: String? = null,

    /**
     * 제출한 코드 (선택사항)
     * programmingLanguage가 제공되지 않을 때 언어 자동 감지에 사용됩니다.
     * CodeLanguageDetector를 사용하여 가중치 기반 언어 감지를 수행합니다.
     */
    val code: String? = null
)
