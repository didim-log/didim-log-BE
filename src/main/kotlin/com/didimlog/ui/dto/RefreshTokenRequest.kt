package com.didimlog.ui.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Refresh Token 요청 DTO
 * refreshToken은 Header 또는 Body에서 제공될 수 있으므로 nullable로 처리
 * 검증은 Controller에서 수동으로 수행
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RefreshTokenRequest @JsonCreator constructor(
    @JsonProperty("refreshToken")
    val refreshToken: String? = null
)

