package com.didimlog.ui.controller

import com.didimlog.application.member.MemberService
import com.didimlog.ui.dto.UpdateMyNicknameRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@Tag(name = "Member", description = "회원 관련 API")
@RestController
@RequestMapping("/api/v1/members")
@Validated
class MemberController(
    private val memberService: MemberService
) {

    @Operation(summary = "닉네임 사용 가능 여부 확인", description = "닉네임의 유효성(정규식/예약어/길이)과 중복 여부를 확인합니다.")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 요청 파라미터",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/check-nickname")
    fun checkNickname(
        @Parameter(description = "닉네임", required = true)
        @RequestParam
        @NotBlank(message = "닉네임은 필수입니다.")
        nickname: String
    ): ResponseEntity<Boolean> {
        return ResponseEntity.ok(memberService.isNicknameAvailable(nickname))
    }

    @Operation(
        summary = "내 닉네임 변경",
        description = "로그인한 사용자의 닉네임을 변경합니다. 변경 시 유효성 및 중복 검사를 수행합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "변경 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효성 검사 실패 또는 중복 닉네임",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PatchMapping("/me/nickname")
    fun updateMyNickname(
        principal: Principal?,
        @RequestBody
        @Valid
        request: UpdateMyNicknameRequest
    ): ResponseEntity<Void> {
        val memberId = principal?.name ?: throw IllegalArgumentException("인증이 필요합니다.")
        memberService.updateMyNickname(memberId, request.nickname)
        return ResponseEntity.noContent().build()
    }
}


