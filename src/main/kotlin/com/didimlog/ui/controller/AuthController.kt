package com.didimlog.ui.controller

import com.didimlog.application.auth.AuthService
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.ui.dto.AuthRequest
import com.didimlog.ui.dto.AuthResponse
import com.didimlog.ui.dto.SignupFinalizeRequest
import com.didimlog.ui.dto.SuperAdminRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Auth", description = "인증 관련 API")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    @Value("\${app.admin.secret-key}")
    private val adminSecretKey: String
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

    @Operation(
        summary = "슈퍼 관리자 계정 생성",
        description = "관리자 키(adminKey)를 입력받아 검증 후 ADMIN 권한으로 계정을 생성하고 JWT 토큰을 발급합니다. 이 API는 초기 관리자 생성을 위해 permitAll로 열려있습니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "슈퍼 관리자 계정 생성 성공"),
            ApiResponse(
                responseCode = "400",
                description = "관리자 키 불일치, 비밀번호 정책 위반 또는 유효하지 않은 입력",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "유효하지 않은 BOJ ID",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/super-admin")
    fun createSuperAdmin(
        @RequestBody
        @Valid
        request: SuperAdminRequest
    ): ResponseEntity<AuthResponse> {
        // 관리자 키 검증
        if (request.adminKey != adminSecretKey) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "관리자 키가 일치하지 않습니다.")
        }

        val result = authService.createSuperAdmin(request.bojId, request.password, request.adminKey)
        val response = AuthResponse.signup(
            token = result.token,
            rating = result.rating,
            tier = result.tier.name,
            tierLevel = result.tier.value
        )
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "가입 마무리",
        description = "소셜 로그인 후 약관 동의 및 닉네임 설정을 완료합니다. 신규 유저의 경우 Student 엔티티를 생성하고, 약관 동의가 완료되면 GUEST에서 USER로 역할이 변경되고 정식 Access Token이 발급됩니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "가입 마무리 성공"),
            ApiResponse(
                responseCode = "400",
                description = "약관 동의 미완료, 닉네임 중복 또는 유효하지 않은 입력",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "사용자를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/signup/finalize")
    fun finalizeSignup(
        @RequestBody
        @Valid
        request: SignupFinalizeRequest
    ): ResponseEntity<AuthResponse> {
        val result = authService.finalizeSignup(
            email = request.email,
            provider = request.provider,
            providerId = request.providerId,
            nickname = request.nickname,
            bojId = request.bojId,
            termsAgreed = request.termsAgreed
        )
        val response = AuthResponse.signup(
            token = result.token,
            rating = result.rating,
            tier = result.tier.name,
            tierLevel = result.tier.value
        )
        return ResponseEntity.ok(response)
    }
}
