package com.didimlog.ui.dto

import com.didimlog.domain.enums.ProblemResult
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 회고 작성 요청 DTO
 */
data class RetrospectiveRequest(
    @field:NotBlank(message = "회고 내용은 필수입니다.")
    @field:Size(min = 10, max = 2000, message = "회고 내용은 10자 이상 2000자 이하여야 합니다.")
    val content: String,
    
    @field:NotBlank(message = "한 줄 요약은 필수입니다.")
    @field:Size(max = 200, message = "한 줄 요약은 200자 이하여야 합니다.")
    val summary: String, // 한 줄 요약 (필수)
    
    /**
     * 풀이 결과 타입 (SUCCESS/FAIL)
     * 사용자가 직접 선택한 결과임을 명시한다.
     */
    val resultType: ProblemResult? = null,
    
    /**
     * 사용자가 직접 선택한 풀이 전략(알고리즘) 태그
     * 예: "BruteForce", "Greedy" 등
     */
    @field:Size(max = 50, message = "풀이 전략 태그는 50자 이하여야 합니다.")
    val solvedCategory: String? = null,
    
    /**
     * 풀이 소요 시간 (예: "15m 30s" 또는 초 단위 문자열)
     */
    @field:Size(max = 50, message = "풀이 시간은 50자 이하여야 합니다.")
    val solveTime: String? = null
)
