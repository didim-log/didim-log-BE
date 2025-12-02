package com.didimlog.ui.controller

import com.didimlog.application.auth.AuthService
import com.didimlog.ui.dto.AuthRequest
import com.didimlog.ui.dto.AuthResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Auth", description = "인증 관련 API")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService
) {

    @Operation(
        summary = "회원가입",
        description = "BOJ ID와 비밀번호를 입력받아 Solved.ac API로 검증 후 회원가입을 진행하고 JWT 토큰을 발급합니다. 비밀번호는 BCrypt로 암호화되어 저장됩니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "회원가입 성공"),
            ApiResponse(
                responseCode = "400",
                description = "비밀번호 정책 위반 또는 유효하지 않은 입력",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "유효하지 않은 BOJ ID",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/signup")
    fun signup(
        @RequestBody
        @Valid
        request: AuthRequest
    ): ResponseEntity<AuthResponse> {
        val result = authService.signup(request.bojId, request.password)
        val response = AuthResponse.signup(
            token = result.token,
            rating = result.rating,
            tier = result.tier.name,
            tierLevel = result.tier.value
        )
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "로그인",
        description = "BOJ ID와 비밀번호로 로그인하고 JWT 토큰을 발급합니다. 비밀번호가 일치하지 않으면 에러가 발생합니다."
    )
    @PostMapping("/login")
    fun login(
        @RequestBody
        @Valid
        request: AuthRequest
    ): ResponseEntity<AuthResponse> {
        val result = authService.login(request.bojId, request.password)
        val response = AuthResponse.login(
            token = result.token,
            rating = result.rating,
            tier = result.tier.name,
            tierLevel = result.tier.value
        )
        return ResponseEntity.ok(response)
    }
}

