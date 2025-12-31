package com.didimlog.ui.controller

import com.didimlog.application.notice.NoticeService
import com.didimlog.ui.dto.NoticeCreateRequest
import com.didimlog.ui.dto.NoticeResponse
import com.didimlog.ui.dto.NoticeUpdateRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Notice", description = "공지사항 관련 API")
@RestController
@RequestMapping("/api/v1/notices")
@Validated
class NoticeController(
    private val noticeService: NoticeService
) {

    @Operation(
        summary = "공지사항 목록 조회",
        description = "공지사항 목록을 조회합니다. 상단 고정 공지가 먼저 오고, 그 다음 최신순으로 정렬됩니다. 페이징을 지원합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 page/size 값",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping
    fun getNotices(
        @Parameter(description = "페이지 번호 (1부터 시작, 기본값: 1)", required = false)
        @RequestParam(defaultValue = "1")
        @Min(value = 1, message = "페이지 번호는 1 이상이어야 합니다.")
        page: Int,
        @Parameter(description = "페이지 크기 (기본값: 10)", required = false)
        @RequestParam(defaultValue = "10")
        @Positive(message = "페이지 크기는 1 이상이어야 합니다.")
        size: Int
    ): ResponseEntity<Page<NoticeResponse>> {
        val pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val notices = noticeService.getNotices(pageable)
        val response = notices.map { NoticeResponse.from(it) }
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "공지사항 상세 조회",
        description = "공지사항 ID로 공지사항을 조회합니다."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(
                responseCode = "404",
                description = "공지사항을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @GetMapping("/{noticeId}")
    fun getNotice(
        @Parameter(description = "공지사항 ID", required = true)
        @PathVariable noticeId: String
    ): ResponseEntity<NoticeResponse> {
        val notice = noticeService.getNotice(noticeId)
        val response = NoticeResponse.from(notice)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "공지사항 수정",
        description = "관리자가 공지사항을 수정합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 성공"),
            ApiResponse(
                responseCode = "400",
                description = "요청 값 검증 실패",
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
            ),
            ApiResponse(
                responseCode = "404",
                description = "공지사항을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{noticeId}")
    fun updateNotice(
        @Parameter(description = "공지사항 ID", required = true)
        @PathVariable noticeId: String,
        @RequestBody
        @Valid
        request: NoticeUpdateRequest
    ): ResponseEntity<NoticeResponse> {
        val notice = noticeService.updateNotice(
            noticeId = noticeId,
            title = request.title,
            content = request.content,
            isPinned = request.isPinned
        )
        val response = NoticeResponse.from(notice)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "공지사항 삭제",
        description = "관리자가 공지사항을 삭제합니다. ADMIN 권한이 필요합니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제 성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "403",
                description = "ADMIN 권한 필요",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "공지사항을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = com.didimlog.global.exception.ErrorResponse::class))]
            )
        ]
    )
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{noticeId}")
    fun deleteNotice(
        @Parameter(description = "공지사항 ID", required = true)
        @PathVariable noticeId: String
    ): ResponseEntity<Void> {
        noticeService.deleteNotice(noticeId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}

