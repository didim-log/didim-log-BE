package com.didimlog.ui.controller

import com.didimlog.application.student.StudentService
import com.didimlog.ui.dto.UpdateProfileRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Student", description = "학생 프로필 관련 API")
@RestController
@RequestMapping("/api/v1/students")
class StudentController(
    private val studentService: StudentService
) {

    @Operation(
        summary = "내 정보 수정",
        description = "학생의 닉네임 및 비밀번호를 수정합니다. JWT 토큰에서 사용자 정보를 자동으로 추출합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PatchMapping("/me")
    fun updateProfile(
        authentication: Authentication,
        @RequestBody
        @Valid
        request: UpdateProfileRequest
    ): ResponseEntity<Void> {
        val bojId = authentication.name // JWT 토큰의 subject(bojId)
        studentService.updateProfile(
            bojId = bojId,
            nickname = request.nickname,
            currentPassword = request.currentPassword,
            newPassword = request.newPassword
        )
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
