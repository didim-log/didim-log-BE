package com.didimlog.ui.controller

import com.didimlog.application.ProblemCollectorService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Problem Collector", description = "문제 데이터 수집 관련 API (관리자용)")
@RestController
@RequestMapping("/api/v1/admin/problems")
@Validated
class ProblemCollectorController(
    private val problemCollectorService: ProblemCollectorService
) {

    @Operation(
        summary = "문제 메타데이터 수집",
        description = "Solved.ac API를 통해 지정된 범위의 문제 메타데이터를 수집하여 DB에 저장합니다. (Upsert 방식)",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PostMapping("/collect-metadata")
    fun collectMetadata(
        authentication: Authentication,
        @Parameter(description = "시작 문제 ID", required = true)
        @RequestParam
        @Positive(message = "시작 문제 ID는 1 이상이어야 합니다.")
        start: Int,
        @Parameter(description = "종료 문제 ID (포함)", required = true)
        @RequestParam
        @Positive(message = "종료 문제 ID는 1 이상이어야 합니다.")
        end: Int
    ): ResponseEntity<Map<String, String>> {
        problemCollectorService.collectMetadata(start, end)
        return ResponseEntity.ok(
            mapOf(
                "message" to "문제 메타데이터 수집이 완료되었습니다.",
                "range" to "$start-$end"
            )
        )
    }

    @Operation(
        summary = "문제 상세 정보 크롤링",
        description = "DB에서 description이 null인 문제들의 상세 정보를 BOJ 사이트에서 크롤링하여 업데이트합니다. Rate Limit을 준수하기 위해 각 요청 사이에 2~4초 간격을 둡니다.",
        security = [SecurityRequirement(name = "Authorization")]
    )
    @PostMapping("/collect-details")
    fun collectDetails(
        authentication: Authentication
    ): ResponseEntity<Map<String, String>> {
        problemCollectorService.collectDetailsBatch()
        return ResponseEntity.ok(
            mapOf(
                "message" to "문제 상세 정보 크롤링이 완료되었습니다."
            )
        )
    }
}


