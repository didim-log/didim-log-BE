package com.didimlog.ui.controller

import com.didimlog.application.retrospective.RetrospectiveSearchCondition
import com.didimlog.application.retrospective.RetrospectiveService
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.ui.dto.BookmarkToggleResponse
import com.didimlog.ui.dto.RetrospectivePageResponse
import com.didimlog.ui.dto.RetrospectiveRequest
import com.didimlog.ui.dto.RetrospectiveResponse
import com.didimlog.ui.dto.TemplateResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Retrospective", description = "회고 관련 API")
@RestController
@RequestMapping("/api/v1/retrospectives")
class RetrospectiveController(
    private val retrospectiveService: RetrospectiveService
) {

    @Operation(
        summary = "회고 작성",
        description = "학생이 문제 풀이 후 회고를 작성합니다. 이미 작성한 회고가 있으면 수정됩니다."
    )
    @PostMapping
    fun writeRetrospective(
        @Parameter(description = "학생 ID", required = true)
        @RequestParam studentId: String,

        @Parameter(description = "문제 ID", required = true)
        @RequestParam problemId: String,

        @Parameter(description = "회고 내용", required = true)
        @RequestBody
        @Valid
        request: RetrospectiveRequest
    ): ResponseEntity<RetrospectiveResponse> {
        val retrospective = retrospectiveService.writeRetrospective(
            studentId = studentId,
            problemId = problemId,
            content = request.content
        )
        val response = RetrospectiveResponse.from(retrospective)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "회고 목록 조회",
        description = "검색 조건에 따라 회고 목록을 조회합니다. 키워드, 카테고리, 북마크 여부로 필터링할 수 있으며, 페이징을 지원합니다."
    )
    @GetMapping
    fun getRetrospectives(
        @Parameter(description = "검색 키워드 (제목 또는 내용)", required = false)
        @RequestParam(required = false)
        keyword: String?,

        @Parameter(description = "카테고리 필터", required = false)
        @RequestParam(required = false)
        category: String?,

        @Parameter(description = "북마크 여부 (true인 경우만 필터링)", required = false)
        @RequestParam(required = false)
        isBookmarked: Boolean?,

        @Parameter(description = "학생 ID", required = false)
        @RequestParam(required = false)
        studentId: String?,

        @Parameter(description = "페이지 번호 (0부터 시작)", required = false)
        @RequestParam(defaultValue = "0")
        page: Int,

        @Parameter(description = "페이지 크기", required = false)
        @RequestParam(defaultValue = "10")
        size: Int,

        @Parameter(description = "정렬 기준 (예: createdAt,desc 또는 createdAt,asc)", required = false)
        @RequestParam(required = false)
        sort: String?
    ): ResponseEntity<RetrospectivePageResponse> {
        val pageable = createPageable(page, size, sort)
        val condition = RetrospectiveSearchCondition(
            keyword = keyword,
            category = category?.let { ProblemCategory.valueOf(it.uppercase()) },
            isBookmarked = isBookmarked,
            studentId = studentId
        )

        val pageResult = retrospectiveService.searchRetrospectives(condition, pageable)
        val response = RetrospectivePageResponse.from(pageResult)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "회고 조회",
        description = "회고 ID로 회고를 조회합니다."
    )
    @GetMapping("/{retrospectiveId}")
    fun getRetrospective(
        @Parameter(description = "회고 ID", required = true)
        @PathVariable retrospectiveId: String
    ): ResponseEntity<RetrospectiveResponse> {
        val retrospective = retrospectiveService.getRetrospective(retrospectiveId)
        val response = RetrospectiveResponse.from(retrospective)
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "북마크 토글",
        description = "회고의 북마크 상태를 토글합니다."
    )
    @PostMapping("/{retrospectiveId}/bookmark")
    fun toggleBookmark(
        @Parameter(description = "회고 ID", required = true)
        @PathVariable retrospectiveId: String
    ): ResponseEntity<BookmarkToggleResponse> {
        val isBookmarked = retrospectiveService.toggleBookmark(retrospectiveId)
        val response = BookmarkToggleResponse(isBookmarked = isBookmarked)
        return ResponseEntity.ok(response)
    }

    private fun createPageable(page: Int, size: Int, sort: String?): Pageable {
        val sortObj = if (sort != null) {
            val parts = sort.split(",")
            if (parts.size == 2) {
                val direction = if (parts[1].lowercase() == "asc") Sort.Direction.ASC else Sort.Direction.DESC
                Sort.by(direction, parts[0])
            } else {
                Sort.by(Sort.Direction.DESC, "createdAt")
            }
        } else {
            Sort.by(Sort.Direction.DESC, "createdAt")
        }

        return PageRequest.of(page, size, sortObj)
    }

    @Operation(
        summary = "회고 삭제",
        description = "회고 ID로 회고를 삭제합니다."
    )
    @DeleteMapping("/{retrospectiveId}")
    fun deleteRetrospective(
        @Parameter(description = "회고 ID", required = true)
        @PathVariable retrospectiveId: String
    ): ResponseEntity<Void> {
        retrospectiveService.deleteRetrospective(retrospectiveId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @Operation(
        summary = "회고 템플릿 생성",
        description = "문제 정보를 바탕으로 회고 작성용 마크다운 템플릿을 생성합니다."
    )
    @GetMapping("/template")
    fun generateTemplate(
        @Parameter(description = "문제 ID", required = true)
        @RequestParam problemId: String
    ): ResponseEntity<TemplateResponse> {
        val template = retrospectiveService.generateTemplate(problemId)
        val response = TemplateResponse(template = template)
        return ResponseEntity.ok(response)
    }
}


