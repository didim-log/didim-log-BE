package com.didimlog.ui.controller

import com.didimlog.application.member.AdminMemberService
import com.didimlog.ui.dto.AdminMemberUpdateRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin", description = "관리자 관련 API (ADMIN 권한 필요)")
@RestController
@RequestMapping("/api/v1/admin/members")
class AdminMemberController(
    private val adminMemberService: AdminMemberService
) {

    @Operation(
        summary = "회원 정보 수정(관리자)",
        description = "관리자가 특정 회원의 nickname/password를 수정합니다. nickname 변경 시 유효성/중복 검사를 수행합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "수정 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효성 검사 실패 또는 중복 닉네임",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{memberId}")
    fun updateMember(
        @Parameter(description = "회원 ID", required = true)
        @PathVariable memberId: String,
        @RequestBody
        @Valid
        request: AdminMemberUpdateRequest
    ): ResponseEntity<Void> {
        adminMemberService.updateMember(memberId, request.nickname, request.password)
        return ResponseEntity.noContent().build()
    }
}


