package com.didimlog.ui.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * 저장된 템플릿 렌더링 요청 DTO
 */
data class TemplateRenderRequest(
    @field:NotNull(message = "문제 ID는 필수입니다.")
    @field:Min(value = 1, message = "문제 ID는 1 이상이어야 합니다.")
    val problemId: Long,

    /**
     * 프로그래밍 언어 코드 (선택사항)
     * 예: "JAVA", "KOTLIN", "PYTHON"
     */
    val programmingLanguage: String? = null,

    /**
     * 제출한 코드 (선택사항)
     * programmingLanguage가 없을 때 언어 자동 감지에 사용됩니다.
     */
    val code: String? = null
)
