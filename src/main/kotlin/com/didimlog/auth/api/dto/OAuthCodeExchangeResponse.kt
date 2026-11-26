package com.didimlog.auth.api.dto

import com.didimlog.auth.application.oauth.OAuthExchangeService

data class OAuthCodeExchangeResponse(
    val token: String,
    val refreshToken: String,
    val message: String,
    val rating: Int,
    val tier: String,
    val tierLevel: Int,
    val provider: String
) {
    companion object {
        fun from(result: OAuthExchangeService.ExchangeResult): OAuthCodeExchangeResponse {
            return OAuthCodeExchangeResponse(
                token = result.accessToken,
                refreshToken = result.refreshToken,
                message = "로그인에 성공했습니다.",
                rating = result.rating,
                tier = result.tier.name,
                tierLevel = result.tierLevel,
                provider = result.provider.value
            )
        }
    }
}
