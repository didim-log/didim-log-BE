package com.didimlog.ui.controller

import com.didimlog.application.auth.AuthService
import com.didimlog.application.auth.FindAccountService
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.application.auth.boj.BojOwnershipVerificationService
import com.didimlog.application.auth.oauth.OAuthExchangeService
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.exception.ErrorResponse
import com.didimlog.global.ratelimit.RateLimitDecision
import com.didimlog.global.ratelimit.RateLimitInterceptor
import com.didimlog.global.util.HttpRequestUtil
import com.didimlog.ui.dto.AuthResponse
import com.didimlog.ui.dto.BojIdDuplicateCheckResponse
import com.didimlog.ui.dto.BojCodeIssueResponse
import com.didimlog.ui.dto.BojVerifyRequest
import com.didimlog.ui.dto.BojVerifyResponse
import com.didimlog.ui.dto.FindAccountRequest
import com.didimlog.ui.dto.FindAccountResponse
import com.didimlog.ui.dto.FindIdRequest
import com.didimlog.ui.dto.FindIdPasswordResponse
import com.didimlog.ui.dto.FindPasswordRequest
import com.didimlog.ui.dto.LoginRequest
import com.didimlog.ui.dto.OAuthCodeExchangeRequest
import com.didimlog.ui.dto.OAuthCodeExchangeResponse
import com.didimlog.ui.dto.SignupRequest
import com.didimlog.ui.dto.RefreshTokenRequest
import com.didimlog.ui.dto.ResetPasswordRequest
import com.didimlog.ui.dto.SuperAdminRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Auth", description = "인증 관련 API")
@RestController
@RequestMapping("/api/v1/auth")
@Validated
class AuthController(
    private val authService: AuthService,
    private val findAccountService: FindAccountService,
    private val bojOwnershipVerificationService: BojOwnershipVerificationService,
    private val refreshTokenService: RefreshTokenService,
    private val oAuthExchangeService: OAuthExchangeService,
    @Value("\${app.admin.secret-key:}")
    private val adminSecretKey: String
) {

    @Operation(
        summary = "OAuth 로그인 코드 교환",
        description = "OAuth 콜백에서 받은 일회용 코드를 Access Token과 Refresh Token으로 교환합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "로그인 성공"),
            ApiResponse(
                responseCode = "400",
                description = "코드가 없거나 만료되었거나 이미 사용됨",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/oauth/exchange")
    fun exchangeOAuthCode(
        @RequestBody
        @Valid
        request: OAuthCodeExchangeRequest
    ): ResponseEntity<OAuthCodeExchangeResponse> {
        val response = OAuthCodeExchangeResponse.from(
            oAuthExchangeService.exchange(request.code)
        )
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(response)
    }

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
        request: SignupRequest
    ): ResponseEntity<AuthResponse> {
        val result = authService.signup(
            request.bojId,
            request.password,
            request.email,
            request.verificationSessionId
        )
        val response = AuthResponse.signup(
            token = result.token,
            refreshToken = result.refreshToken,
            rating = result.rating,
            tier = result.tier.name,
            tierLevel = result.tierLevel
        )
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "로그인",
        description = "BOJ ID와 비밀번호로 로그인하고 JWT 토큰을 발급합니다. 비밀번호가 일치하지 않으면 에러가 발생하며, 로그인 실패 시 남은 시도 횟수가 응답 헤더와 바디에 포함됩니다."
    )
    @PostMapping("/login")
    fun login(
        @RequestBody
        @Valid
        request: LoginRequest,
        httpRequest: jakarta.servlet.http.HttpServletRequest,
        httpResponse: jakarta.servlet.http.HttpServletResponse
    ): ResponseEntity<*> {
        return try {
            val result = authService.login(request.bojId, request.password)

            val response = AuthResponse.login(
                token = result.token,
                refreshToken = result.refreshToken,
                rating = result.rating,
                tier = result.tier.name,
                tierLevel = result.tierLevel
            )
            ResponseEntity.ok(response)
        } catch (e: BusinessException) {
            val rateLimitDecision = httpRequest.getAttribute(
                RateLimitInterceptor.RATE_LIMIT_DECISION_ATTRIBUTE
            ) as? RateLimitDecision
            rateLimitDecision?.let {
                httpResponse.setHeader("X-Rate-Limit-Remaining", it.remainingRequests.toString())
                httpResponse.setHeader("X-Rate-Limit-Limit", it.limit.toString())
            }

            val errorResponse = rateLimitDecision?.let {
                ErrorResponse.of(
                    errorCode = e.errorCode,
                    customMessage = e.message ?: e.errorCode.message,
                    remainingAttempts = it.remainingRequests
                )
            } ?: ErrorResponse.of(
                errorCode = e.errorCode,
                customMessage = e.message ?: e.errorCode.message
            )
            ResponseEntity.status(e.errorCode.status).body(errorResponse)
        }
    }

    @Operation(
        summary = "BOJ ID 중복 체크",
        description = "회원가입 전 BOJ ID가 이미 가입된 계정인지 확인합니다."
    )
    @GetMapping("/check-duplicate")
    fun checkDuplicateBojId(
        @RequestParam
        @NotBlank(message = "bojId는 필수입니다.")
        bojId: String
    ): ResponseEntity<BojIdDuplicateCheckResponse> {
        val isDuplicate = authService.checkBojIdDuplicate(bojId)
        val message = when (isDuplicate) {
            true -> "이미 가입된 BOJ ID입니다."
            false -> "사용 가능한 BOJ ID입니다."
        }
        return ResponseEntity.ok(BojIdDuplicateCheckResponse(isDuplicate = isDuplicate, message = message))
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

        val result = authService.createSuperAdmin(request.bojId, request.password, request.email, request.adminKey)
        val response = AuthResponse.signup(
            token = result.token,
            refreshToken = result.refreshToken,
            rating = result.rating,
            tier = result.tier.name,
            tierLevel = result.tierLevel
        )
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "가입 마무리(중단됨)",
        description = "현재 제공하지 않는 경로입니다. BOJ 인증을 마친 뒤 /api/v1/auth/signup을 사용해주세요."
    )
    @ApiResponse(responseCode = "410", description = "더 이상 제공하지 않는 경로")
    @PostMapping("/signup/finalize")
    fun finalizeSignup(): ResponseEntity<Void> {
        return ResponseEntity.status(HttpStatus.GONE).build()
    }

    @Operation(
        summary = "계정 찾기",
        description = "이메일을 입력받아 가입된 소셜 제공자(Provider)를 반환합니다. (OAuth-only 환경용)"
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 이메일 형식",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "가입 정보 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/find-account")
    fun findAccount(
        @RequestBody
        @Valid
        request: FindAccountRequest
    ): ResponseEntity<FindAccountResponse> {
        val result = findAccountService.findAccount(request.email)
        return ResponseEntity.ok(
            FindAccountResponse(
                provider = result.provider,
                message = result.message
            )
        )
    }

    @Operation(
        summary = "아이디 찾기",
        description = "이메일을 입력받아 해당 이메일로 가입된 계정의 BOJ ID를 이메일로 전송합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "이메일 전송 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 이메일 형식 또는 BOJ ID가 등록되지 않은 계정",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "해당 이메일로 가입된 계정을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/find-id")
    fun findId(
        @RequestBody
        @Valid
        request: FindIdRequest
    ): ResponseEntity<FindIdPasswordResponse> {
        authService.findId(request.email)
        return ResponseEntity.ok(
            FindIdPasswordResponse(
                message = "이메일로 아이디가 전송되었습니다."
            )
        )
    }

    @Operation(
        summary = "비밀번호 찾기",
        description = "이메일과 BOJ ID를 입력받아 일치하는 계정이 있으면 비밀번호 재설정 코드(8자리 영문+숫자 조합)를 생성하여 MongoDB에 저장하고 이메일로 전송합니다. 코드는 30분간 유효합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "이메일 전송 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 이메일 형식, 이메일과 BOJ ID 불일치, 또는 소셜 로그인 계정",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "해당 이메일로 가입된 계정을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/find-password")
    fun findPassword(
        @RequestBody
        @Valid
        request: FindPasswordRequest
    ): ResponseEntity<FindIdPasswordResponse> {
        authService.findPassword(request.email, request.bojId)
        return ResponseEntity.ok(
            FindIdPasswordResponse(
                message = "이메일로 비밀번호 재설정 코드가 전송되었습니다."
            )
        )
    }

    @Operation(
        summary = "비밀번호 재설정",
        description = "비밀번호 재설정 코드와 새 비밀번호를 입력받아 비밀번호를 변경합니다. 재설정 코드는 1회성으로 사용되며 성공 시 즉시 폐기됩니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "비밀번호 재설정 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 코드 또는 비밀번호 정책 위반",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "재설정 코드에 해당하는 사용자를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/reset-password")
    fun resetPassword(
        @RequestBody
        @Valid
        request: ResetPasswordRequest
    ): ResponseEntity<FindIdPasswordResponse> {
        authService.resetPassword(request.resetCode, request.newPassword)
        return ResponseEntity.ok(
            FindIdPasswordResponse(
                message = "비밀번호가 재설정되었습니다."
            )
        )
    }

    @Operation(
        summary = "BOJ 소유권 인증 코드 발급",
        description = "백준 프로필 상태 메시지 인증에 사용할 코드를 발급하고, sessionId와 함께 일정 시간 저장합니다."
    )
    @PostMapping("/boj/code")
    fun issueBojVerificationCode(httpRequest: HttpServletRequest): ResponseEntity<BojCodeIssueResponse> {
        val clientIp = HttpRequestUtil.getClientIpAddress(httpRequest)
        val issued = bojOwnershipVerificationService.issueVerificationCode(clientIp)
        return ResponseEntity.ok()
            .header("X-Rate-Limit-Limit", issued.rateLimitDecision.limit.toString())
            .header("X-Rate-Limit-Remaining", issued.rateLimitDecision.remainingRequests.toString())
            .body(
                BojCodeIssueResponse(
                    sessionId = issued.sessionId,
                    code = issued.code,
                    expiresInSeconds = issued.expiresInSeconds
                )
            )
    }

    @Operation(
        summary = "BOJ 소유권 인증 확인",
        description = "백준 프로필 상태 메시지에 발급된 인증 코드가 포함되어 있는지 확인합니다. 인증 성공 시 인증된 BOJ ID를 반환하며, 이후 /api/v1/auth/signup에서 인증 세션과 BOJ ID를 사용하여 계정을 생성합니다."
    )
    @PostMapping("/boj/verify")
    fun verifyBojOwnership(
        @RequestBody
        @Valid
        request: BojVerifyRequest
    ): ResponseEntity<BojVerifyResponse> {
        val verifiedBojId = bojOwnershipVerificationService.verifyOwnership(
            sessionId = request.sessionId,
            bojId = request.bojId
        )
        return ResponseEntity.ok(BojVerifyResponse(verifiedBojId = verifiedBojId))
    }

    @Operation(
        summary = "토큰 갱신",
        description = "Refresh Token을 사용하여 새로운 Access Token과 Refresh Token을 발급합니다. 기존 Refresh Token은 무효화됩니다 (Token Rotation)."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "토큰 갱신 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 Refresh Token",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "Refresh Token이 만료되었거나 존재하지 않음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PostMapping("/refresh")
    fun refresh(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authHeader: String?,
        @RequestBody(required = false) requestBody: RefreshTokenRequest?
    ): ResponseEntity<AuthResponse> {
        // 1. Body에서 Refresh Token 추출
        var refreshToken: String? = null
        if (requestBody != null && !requestBody.refreshToken.isNullOrBlank()) {
            refreshToken = requestBody.refreshToken.trim()
        }

        // 2. Body에 없으면 Header에서 추출 (Bearer 제거)
        if (refreshToken.isNullOrBlank()) {
            if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ")) {
                refreshToken = authHeader.substring(7).trim()
            }
        }

        // 3. 둘 다 없으면 명시적 예외 발생 (수동 검증)
        if (refreshToken.isNullOrBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Refresh Token이 필요합니다.")
        }

        val result = refreshTokenService.refresh(refreshToken)

        val response = AuthResponse.login(
            token = result.accessToken,
            refreshToken = result.refreshToken,
            rating = result.rating,
            tier = result.tier.name,
            tierLevel = result.tierLevel
        )
        return ResponseEntity.ok(response)
    }
}
