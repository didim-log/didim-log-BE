package com.didimlog.ui.controller

import com.didimlog.application.auth.AuthService
import com.didimlog.application.auth.FindAccountService
import com.didimlog.application.auth.RefreshTokenService
import com.didimlog.application.auth.boj.BojOwnershipVerificationService
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
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
import com.didimlog.ui.dto.SignupRequest
import com.didimlog.ui.dto.RefreshTokenRequest
import com.didimlog.ui.dto.SignupFinalizeRequest
import com.didimlog.ui.dto.SuperAdminRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
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
    private val jwtTokenProvider: JwtTokenProvider,
    private val studentRepository: StudentRepository,
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
        request: SignupRequest
    ): ResponseEntity<AuthResponse> {
        val result = authService.signup(request.bojId, request.password, request.email)
        val response = AuthResponse.signup(
            token = result.token,
            refreshToken = result.refreshToken,
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
        request: LoginRequest
    ): ResponseEntity<AuthResponse> {
        val result = authService.login(request.bojId, request.password)
        val response = AuthResponse.login(
            token = result.token,
            refreshToken = result.refreshToken,
            rating = result.rating,
            tier = result.tier.name,
            tierLevel = result.tier.value
        )
        return ResponseEntity.ok(response)
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
            refreshToken = result.refreshToken,
            rating = result.rating,
            tier = result.tier.name,
            tierLevel = result.tier.value
        )
        return ResponseEntity.ok(response)
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
        description = "이메일과 BOJ ID를 입력받아 일치하는 계정이 있으면 비밀번호 재설정 코드(8자리 영문+숫자 조합)를 생성하여 Redis에 저장하고 이메일로 전송합니다. 코드는 30분간 유효합니다."
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
        summary = "BOJ 소유권 인증 코드 발급",
        description = "백준 프로필 상태 메시지 인증에 사용할 코드를 발급하고, sessionId와 함께 일정 시간 저장합니다."
    )
    @PostMapping("/boj/code")
    fun issueBojVerificationCode(authentication: Authentication?): ResponseEntity<BojCodeIssueResponse> {
        val identifier = authentication?.name
        val issued = bojOwnershipVerificationService.issueVerificationCode(identifier)
        return ResponseEntity.ok(
            BojCodeIssueResponse(
                sessionId = issued.sessionId,
                code = issued.code,
                expiresInSeconds = issued.expiresInSeconds
            )
        )
    }

    @Operation(
        summary = "BOJ 소유권 인증 확인",
        description = "백준 프로필 상태 메시지에 발급된 인증 코드가 포함되어 있는지 확인합니다. 인증 성공 시 인증된 BOJ ID를 반환하며, 이후 회원가입 마무리(/api/v1/auth/signup/finalize)에서 이 BOJ ID를 사용하여 계정을 생성합니다."
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

        val (newAccessToken, newRefreshToken) = refreshTokenService.refresh(refreshToken)

        val bojId = jwtTokenProvider.getSubject(newAccessToken)
        val student = studentRepository.findByBojId(BojId(bojId))
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "사용자를 찾을 수 없습니다. bojId=$bojId")
            }

        val response = AuthResponse.login(
            token = newAccessToken,
            refreshToken = newRefreshToken,
            rating = student.rating,
            tier = student.tier().name,
            tierLevel = student.tier().value
        )
        return ResponseEntity.ok(response)
    }
}
