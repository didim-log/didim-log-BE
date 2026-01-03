# PR Summary: 백엔드 배포 준비 및 보안 강화

## 📋 변경 사항 요약

### 1. 보안 강화
- **Swagger UI HTTP Basic Authentication 적용**
  - `/swagger-ui/**` 및 `/v3/api-docs/**` 경로에 HTTP Basic Authentication 적용
  - 환경 변수 `SWAGGER_USERNAME`, `SWAGGER_PASSWORD`로 인증 정보 관리
  - 배포 워크플로우에 환경 변수 추가

### 2. 온보딩 기능 추가
- **Student 엔티티에 `isOnboardingFinished` 필드 추가**
  - 기본값: `false`
  - 온보딩 투어 완료 여부를 추적
- **온보딩 완료 API 추가**
  - `PATCH /api/v1/members/onboarding/complete`
  - JWT 토큰 기반 인증
  - Dashboard 응답에 `isOnboardingFinished` 필드 포함

### 3. API 개선
- **문제 추천 API 검증 완화**
  - `@Min(10)` → `@Min(1)`로 변경
  - Dashboard에서 4개 문제만 표시하는 요구사항 반영
  - 관련 테스트 수정

### 4. 설정 파일 수정
- **application.yaml 중복 키 제거**
  - `spring.security` 중복 정의 문제 해결
  - `DuplicateKeyException` 에러 수정

### 5. 테스트 수정
- **ProblemControllerTest 수정**
  - `count` 최소값 검증 테스트를 `@Min(1)`에 맞게 수정
  - 모든 테스트 통과 확인 (346 tests completed, 0 failed)

### 6. API 명세서 업데이트
- **ProblemController API 명세 업데이트**
  - `count` 파라미터 최소값: `@Min(10)` → `@Min(1)`
- **MemberController API 명세 추가**
  - `PATCH /api/v1/members/onboarding/complete` 엔드포인트 문서화
- **Swagger UI 보안 설정 문서화**
  - HTTP Basic Authentication 설정 방법 추가

## 🔧 수정된 파일

### Backend
- `src/main/kotlin/com/didimlog/domain/Student.kt`
- `src/main/kotlin/com/didimlog/application/member/MemberService.kt`
- `src/main/kotlin/com/didimlog/application/dashboard/DashboardService.kt`
- `src/main/kotlin/com/didimlog/ui/controller/MemberController.kt`
- `src/main/kotlin/com/didimlog/ui/controller/ProblemController.kt`
- `src/main/kotlin/com/didimlog/ui/dto/DashboardResponse.kt`
- `src/main/kotlin/com/didimlog/global/config/security/SecurityConfig.kt`
- `src/main/resources/application.yaml`
- `src/test/kotlin/com/didimlog/ui/controller/ProblemControllerTest.kt`
- `.github/workflows/deploy.yml`

### Documentation
- `DOCS/API_SPECIFICATION.md`

## ✅ 테스트 결과

- **전체 테스트:** 346 tests completed, 0 failed
- **빌드 상태:** BUILD SUCCESSFUL

## 🚀 배포 체크리스트

### 필수 환경 변수
- `SWAGGER_USERNAME`: Swagger UI 접근 사용자명 (기본값: `admin`)
- `SWAGGER_PASSWORD`: Swagger UI 접근 비밀번호 (기본값: `admin123`)

### 배포 워크플로우
- GitHub Secrets에 `SWAGGER_USERNAME`, `SWAGGER_PASSWORD` 추가 필요
- EC2 인스턴스 `.env` 파일에 환경 변수 자동 추가됨

## 📝 주요 변경 내용

### SecurityConfig.kt
```kotlin
// Swagger UI 경로에 HTTP Basic Authentication 적용
.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").authenticated()
.httpBasic { }
```

### ProblemController.kt
```kotlin
// count 파라미터 최소값 변경
@Min(value = 1, message = "추천 개수는 최소 1개 이상이어야 합니다.")
```

### MemberController.kt
```kotlin
// 온보딩 완료 API 추가
@PatchMapping("/onboarding/complete")
fun completeOnboarding(authentication: Authentication): ResponseEntity<Void>
```

## 🎯 다음 단계

1. 프론트엔드 온보딩 투어 구현 완료 확인
2. 배포 환경에서 Swagger UI 접근 테스트
3. 프로덕션 환경 변수 설정 확인
