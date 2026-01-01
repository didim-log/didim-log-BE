package com.didimlog.ui.dto

/**
 * 인증 응답 DTO
 */
data class AuthResponse(
    val token: String,
    val refreshToken: String,
    val message: String,
    val rating: Int,
    val tier: String,
    val tierLevel: Int
) {
    companion object {
        fun signup(token: String, refreshToken: String, rating: Int, tier: String, tierLevel: Int): AuthResponse {
            return AuthResponse(
                token = token,
                refreshToken = refreshToken,
                message = "회원가입이 완료되었습니다.",
                rating = rating,
                tier = tier,
                tierLevel = tierLevel
            )
        }

        fun login(token: String, refreshToken: String, rating: Int, tier: String, tierLevel: Int): AuthResponse {
            return AuthResponse(
                token = token,
                refreshToken = refreshToken,
                message = "로그인에 성공했습니다.",
                rating = rating,
                tier = tier,
                tierLevel = tierLevel
            )
        }

    }
}
