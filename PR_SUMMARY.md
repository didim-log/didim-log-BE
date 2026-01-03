# PR Summary: Maintenance Mode 화이트리스트 로직 개선 및 프론트엔드 에러 처리

## 📋 개요

유지보수 모드가 활성화되어 있어도 사용자가 공지사항을 확인할 수 있도록 **화이트리스트 로직**을 추가하고, 프론트엔드에서 503 에러를 처리하여 Maintenance 페이지로 자동 리다이렉트하는 기능을 구현했습니다.

## 🎯 주요 변경 사항

### 1. Maintenance Mode 화이트리스트 로직 개선 (백엔드)

#### 파일
- `src/main/kotlin/com/didimlog/global/interceptor/MaintenanceModeInterceptor.kt`

#### 변경 사항
- **클린코드 원칙 준수**: `preHandle` 메서드를 단일 책임 원칙에 맞게 분리
  - `shouldAllowRequest()`: 요청 허용 여부 판단
  - `isPublicApi()`: Public API 여부 확인
  - `isAdminUser()`: ADMIN 권한 여부 확인
- **화이트리스트 로직 추가**:
  - `GET /api/v1/notices/**`: 공지사항 조회 (점검 공지 확인용)
  - `GET /api/v1/system/status`: 시스템 상태 조회
- **ADMIN 권한 사용자 접근 허용**: 유지보수 모드에서도 모든 API 접근 가능

#### 클린코드 원칙 준수
- ✅ Indent depth 1 유지
- ✅ `else` 예약어 사용 금지 (Early Return 패턴)
- ✅ 한 메서드는 한 가지 일만 수행
- ✅ 불필요한 import 없음

### 2. Maintenance Mode 503 에러 처리 및 Maintenance 페이지 추가 (프론트엔드)

#### 파일
- `src/api/client.ts`
- `src/pages/MaintenancePage.tsx` (신규)
- `src/router.tsx`

#### 변경 사항

**`client.ts` (API 클라이언트)**
- 503 에러 처리 로직 추가
- `MAINTENANCE_MODE` 에러 코드 감지 시 `/maintenance` 페이지로 자동 리다이렉트
- Public API (`/api/v1/notices`, `/api/v1/system/status`)는 리다이렉트 제외

**`MaintenancePage.tsx` (신규)**
- Sidebar와 Header 없이 간단한 레이아웃
- 공지사항 목록 표시 (인증 없이 조회 가능)
- 점검 안내 메시지 및 UI

**`router.tsx`**
- `/maintenance` 라우트 추가

### 3. API 명세서 업데이트

#### 파일
- `DOCS/API_SPECIFICATION.md`

#### 변경 사항
- **AdminSystemController** 섹션:
  - 화이트리스트 로직 설명 추가
  - Public API 접근 가능 여부 명시
  - 프론트엔드 자동 리다이렉트 동작 설명 추가
- **NoticeController** 섹션:
  - 유지보수 모드에서도 접근 가능 표시

## 🔍 기술적 세부사항

### 백엔드 구현 방식

#### 화이트리스트 로직
1. **Public API 체크**: `isPublicApi()` 메서드로 `GET /api/v1/notices` 또는 `GET /api/v1/system/status` 요청 확인
2. **ADMIN 권한 체크**: `isAdminUser()` 메서드로 `ROLE_ADMIN` 권한 확인
3. **차단 로직**: 위 조건에 해당하지 않으면 `MAINTENANCE_MODE` 예외 발생

#### 클린코드 원칙 적용
```kotlin
// Before: 하나의 메서드에 모든 로직
override fun preHandle(...): Boolean {
    // 유지보수 모드 체크
    // Public API 체크
    // ADMIN 체크
    // 예외 발생
}

// After: 단일 책임 원칙에 맞게 분리
override fun preHandle(...): Boolean {
    if (shouldAllowRequest(request)) {
        return true
    }
    throw BusinessException(...)
}

private fun shouldAllowRequest(request: HttpServletRequest): Boolean {
    if (isPublicApi(request)) return true
    if (isAdminUser()) return true
    return false
}
```

### 프론트엔드 구현 방식

#### 503 에러 처리
1. **에러 감지**: `apiClient` 응답 인터셉터에서 503 상태 코드 및 `MAINTENANCE_MODE` 코드 확인
2. **Public API 제외**: 공지사항 및 시스템 상태 API는 리다이렉트하지 않음
3. **자동 리다이렉트**: `window.location.href = '/maintenance'`로 Maintenance 페이지로 이동

#### Maintenance 페이지
- **레이아웃**: Sidebar와 Header 없이 간단한 구조
- **공지사항 조회**: `useNotices` 훅을 사용하여 인증 없이 공지사항 목록 표시
- **사용자 경험**: 점검 안내 메시지 및 공지사항을 명확하게 표시

## ✅ 테스트

- 백엔드 빌드 성공 확인 (`BUILD SUCCESSFUL`)
- 프론트엔드 빌드 성공 확인
- 모든 기존 테스트 통과

## 📝 API 변경사항

### Maintenance Mode 동작 변경

**변경 전:**
- 유지보수 모드가 활성화되면 ADMIN을 제외한 모든 사용자 접근 차단
- 공지사항 조회 불가

**변경 후:**
- 유지보수 모드가 활성화되어도 다음 API는 접근 가능:
  - `GET /api/v1/notices/**`: 공지사항 조회 (점검 공지 확인용)
  - `GET /api/v1/system/status`: 시스템 상태 조회
- ADMIN 권한이 있는 사용자는 모든 API 접근 가능
- 프론트엔드는 503 에러를 감지하면 자동으로 `/maintenance` 페이지로 리다이렉트

## 🎨 사용자 경험 개선

### 유지보수 모드 시나리오

**이전:**
- 유지보수 모드 활성화 시 모든 사용자 접근 차단
- 공지사항 확인 불가
- 사용자가 점검 사유를 알 수 없음

**이후:**
- 유지보수 모드 활성화 시에도 공지사항 조회 가능
- Maintenance 페이지에서 점검 안내 및 공지사항 확인 가능
- 사용자가 점검 사유와 예상 완료 시간을 확인할 수 있음

## 🔗 관련 이슈

- 유지보수 모드에서도 공지사항을 확인할 수 있어야 함
- 사용자가 점검 사유를 알 수 있어야 함
- 프론트엔드에서 503 에러를 적절히 처리해야 함

## 📚 참고 문서

- `DOCS/API_SPECIFICATION.md`: API 명세서 (Maintenance Mode 화이트리스트 로직 문서화)
- `DOCS/PR_GUIDE.md`: 코드 스타일 가이드
- `DOCS/COMMIT_CONVENTION.md`: 커밋 컨벤션

## 📦 변경된 파일

### 백엔드
- `src/main/kotlin/com/didimlog/global/interceptor/MaintenanceModeInterceptor.kt`
- `DOCS/API_SPECIFICATION.md`

### 프론트엔드
- `src/api/client.ts`
- `src/pages/MaintenancePage.tsx` (신규)
- `src/router.tsx`

## 🚀 배포 전 체크리스트

- [x] 모든 테스트 통과
- [x] 클린코드 원칙 준수
- [x] API 명세서 업데이트
- [x] 커밋 컨벤션 준수
