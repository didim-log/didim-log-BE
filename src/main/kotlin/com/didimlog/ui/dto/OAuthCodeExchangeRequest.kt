package com.didimlog.ui.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class OAuthCodeExchangeRequest(
    @field:NotBlank(message = "code는 필수입니다.")
    @field:Size(max = 128, message = "code는 128자를 초과할 수 없습니다.")
    val code: String
)
