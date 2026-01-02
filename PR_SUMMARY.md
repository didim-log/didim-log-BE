# PR Summary

## Title

**feat: 로그 데이터 정리 기능 추가 및 정적 템플릿 개선**

## Description

### 무엇을(What) 변경했는지

1. **로그 데이터 정리 기능 추가**
   - 관리자가 오래된 로그를 수동으로 삭제할 수 있는 API 엔드포인트 추가
   - 매일 새벽 3시에 60일 이상 된 로그를 자동으로 삭제하는 스케줄러 구현

2. **정적 템플릿 개선**
   - 회고 템플릿에 풀이 시간(solveTime) 표시 기능 추가
   - 코드 블록의 들여쓰기 문제 수정

3. **클린 코드 원칙 준수**
   - `else` 키워드 제거 및 Early Return 패턴 적용

### 왜(Why) 변경했는지

- **데이터베이스 건강성 유지**: AI 리뷰 로그가 계속 누적되면서 데이터베이스 용량이 증가하는 문제를 해결하기 위해 오래된 로그를 자동/수동으로 정리할 수 있는 기능이 필요했습니다.
- **사용자 경험 개선**: 회고 템플릿에 풀이 시간을 표시하여 사용자가 자신의 문제 해결 시간을 명확하게 확인할 수 있도록 개선했습니다.
- **코드 품질 향상**: 클린 코드 원칙(else 예약어 사용 금지)을 준수하여 코드 가독성과 유지보수성을 향상시켰습니다.

## Key Code (Before & After)

### 1. 로그 정리 기능 추가

**Before:**
```kotlin
// 로그 정리 기능 없음
```

**After:**
```kotlin
@Service
class LogCleanupService(
    private val logRepository: LogRepository
) {
    @Transactional
    fun cleanupLogs(olderThanDays: Int): Long {
        val cutoffDate = LocalDateTime.now().minusDays(olderThanDays.toLong())
        val deletedCount = logRepository.countByCreatedAtBefore(cutoffDate)
        if (deletedCount > 0) {
            logRepository.deleteByCreatedAtBefore(cutoffDate)
        }
        return deletedCount
    }

    @Scheduled(cron = "0 0 3 * * *") // 매일 새벽 3시
    fun autoCleanupOldLogs() {
        val olderThanDays = 60
        val deletedCount = cleanupLogs(olderThanDays)
        log.info("Auto-cleanup: Deleted {} logs older than {} days.", deletedCount, olderThanDays)
    }
}
```

### 2. 정적 템플릿 풀이 시간 추가

**Before:**
```kotlin
return buildString {
    appendLine("# 🏆 $title 해결 회고")
    appendLine()
    appendLine("## 🔑 학습 키워드")
    // ...
    appendLine("## 2. 복잡도 분석 (Complexity)")
    appendLine()
    appendLine("- 시간 복잡도: O(?)")
    appendLine("- 공간 복잡도: O(?)")
    if (solveTime != null && solveTime.isNotBlank()) {
        appendLine("- 풀이 소요 시간: $solveTime")
    }
}
```

**After:**
```kotlin
return buildString {
    appendLine("# 🏆 $title 해결 회고")
    appendLine()
    if (solveTime != null && solveTime.isNotBlank()) {
        appendLine("⏱️ **풀이 소요 시간:** $solveTime")
        appendLine()
    }
    appendLine("## 🔑 학습 키워드")
    // ...
    appendLine("## 2. 복잡도 분석 (Complexity)")
    appendLine()
    appendLine("- 시간 복잡도: O(?)")
    appendLine("- 공간 복잡도: O(?)")
}
```

### 3. 코드 블록 들여쓰기 수정

**Before:**
```kotlin
appendLine("```$markdownLanguage")
appendLine(code)  // code가 여러 줄일 때 각 줄이 들여쓰기됨
appendLine("```")
```

**After:**
```kotlin
appendLine("```$markdownLanguage")
append(code)  // 원본 코드 포맷 유지
appendLine()
appendLine("```")
```

### 4. else 키워드 제거

**Before:**
```kotlin
val promptText = if (resultContext.isNotBlank()) {
    "${resultContext}이 $language 코드를 분석하고 $reviewFocus 반드시 한국어로 응답하세요."
} else {
    "이 $language 코드를 분석하고 $reviewFocus 반드시 한국어로 응답하세요."
}
```

**After:**
```kotlin
private fun buildPromptText(resultContext: String, language: String, reviewFocus: String): String {
    if (resultContext.isNotBlank()) {
        return "${resultContext}이 $language 코드를 분석하고 $reviewFocus 반드시 한국어로 응답하세요."
    }
    return "이 $language 코드를 분석하고 $reviewFocus 반드시 한국어로 응답하세요."
}
```

## Reason for Change

### 기술적 배경

1. **MongoDB Repository 메서드 활용**
   - `deleteByCreatedAtBefore`와 `countByCreatedAtBefore`를 사용하여 날짜 기반 삭제 및 카운트 기능 구현
   - 트랜잭션을 통해 삭제 전 개수를 확인하여 안전하게 처리

2. **Spring Scheduling 활용**
   - `@Scheduled` 어노테이션을 사용하여 정기적인 작업 실행
   - `@EnableScheduling`을 메인 애플리케이션 클래스에 추가하여 스케줄러 활성화

3. **Early Return 패턴**
   - 클린 코드 원칙에 따라 `else` 키워드 사용을 금지하고 Early Return 패턴을 적용
   - 메서드 분리를 통해 가독성 향상

### 개선 이유

- **데이터베이스 관리**: 오래된 로그를 자동으로 정리하여 데이터베이스 용량을 관리하고 성능을 유지
- **관리자 편의성**: 관리자가 필요에 따라 수동으로 로그를 정리할 수 있는 기능 제공
- **사용자 경험**: 회고 템플릿에 풀이 시간을 명확하게 표시하여 사용자가 자신의 성장을 추적할 수 있도록 지원
- **코드 품질**: 클린 코드 원칙을 준수하여 유지보수성 향상

## To Reviewer

리뷰어가 중점적으로 봐주었으면 하는 부분:

1. **LogCleanupService의 트랜잭션 처리**
   - 삭제 전 개수를 확인하는 로직이 적절한지 확인
   - 동시성 문제가 발생할 수 있는지 검토

2. **스케줄러 설정**
   - 매일 새벽 3시 실행이 적절한지 확인
   - 60일 보관 기간이 적절한지 검토

3. **API 엔드포인트 보안**
   - ADMIN 권한 체크가 올바르게 작동하는지 확인
   - `@Positive` 유효성 검사가 적절한지 검토

4. **정적 템플릿 포맷**
   - 풀이 시간 표시 위치와 형식이 적절한지 확인
   - 코드 블록 들여쓰기 수정이 모든 케이스에서 올바르게 작동하는지 검토

## 변경된 파일 목록

### 신규 파일
- `src/main/kotlin/com/didimlog/application/admin/LogCleanupService.kt`
- `src/main/kotlin/com/didimlog/ui/dto/LogCleanupResponse.kt`
- `src/test/kotlin/com/didimlog/application/admin/LogCleanupServiceTest.kt`
- `src/test/kotlin/com/didimlog/ui/controller/AdminLogControllerTest.kt`

### 수정된 파일
- `src/main/kotlin/com/didimlog/DidimLogApplication.kt` - `@EnableScheduling` 추가
- `src/main/kotlin/com/didimlog/domain/repository/LogRepository.kt` - 삭제 및 카운트 메서드 추가
- `src/main/kotlin/com/didimlog/ui/controller/AdminLogController.kt` - DELETE 엔드포인트 추가
- `src/main/kotlin/com/didimlog/application/template/StaticTemplateService.kt` - 풀이 시간 추가 및 들여쓰기 수정
- `src/main/kotlin/com/didimlog/application/log/AiReviewService.kt` - else 제거
- `src/main/kotlin/com/didimlog/ui/dto/AdminDashboardStatsResponse.kt` - else 제거
- `src/main/kotlin/com/didimlog/application/admin/AdminLogService.kt` - 코드 정리
- `src/test/kotlin/com/didimlog/application/template/StaticTemplateServiceTest.kt` - 풀이 시간 테스트 추가
- `DOCS/API_SPECIFICATION.md` - API 명세서 업데이트

## 테스트

- ✅ 모든 단위 테스트 통과
- ✅ 통합 테스트 통과
- ✅ 컴파일 성공

## 관련 이슈

- 데이터베이스 용량 관리 필요
- 회고 템플릿 개선 요청
- 클린 코드 원칙 준수

