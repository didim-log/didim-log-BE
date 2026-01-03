# PR Summary: 온보딩 리셋 기능, Maintenance Mode 개선, Admin Panel 개선

## 📋 개요

이번 PR은 **온보딩 투어 재시작 기능**, **Maintenance Mode에서 ADMIN 접근 허용**, **Admin Panel 공지사항 ID 표시 기능**을 추가하고, 여러 버그를 수정했습니다.

## 🎯 주요 변경 사항

### 1. 온보딩 투어 리셋 기능 추가

#### 백엔드
- **파일**: 
  - `src/main/kotlin/com/didimlog/domain/Student.kt`
  - `src/main/kotlin/com/didimlog/application/member/MemberService.kt`
  - `src/main/kotlin/com/didimlog/ui/controller/MemberController.kt`
- **기능**: 
  - `resetOnboarding()` 메서드 추가: 온보딩 완료 상태를 `false`로 리셋
  - `PATCH /api/v1/members/onboarding/reset` API 엔드포인트 추가
  - Help 버튼(?)을 눌렀을 때 온보딩을 다시 볼 수 있도록 지원

#### 프론트엔드
- **파일**: 
  - `src/api/endpoints/member.api.ts`
  - `src/components/layout/Header.tsx`
- **기능**: 
  - `resetOnboarding` API 호출 추가
  - Help 버튼 클릭 시 백엔드 상태 리셋 및 프론트엔드 상태 동기화
  - `localStorage` 제거 및 투어 상태 완전 리셋

### 2. Maintenance Mode에서 ADMIN 접근 허용

#### 백엔드
- **파일**: `src/main/kotlin/com/didimlog/global/interceptor/MaintenanceModeInterceptor.kt`
- **변경 사항**:
  - 유지보수 모드가 활성화되어 있어도 ADMIN 권한이 있으면 접근 허용
  - `authentication.authorities.any()`를 사용하여 권한 체크 개선
  - ADMIN이 아닌 사용자만 차단하도록 로직 개선

**이유**: 유지보수 모드가 활성화되어 있어도 ADMIN은 시스템 관리가 가능해야 함

### 3. Admin Panel 공지사항 ID 표시 및 복사 기능

#### 프론트엔드
- **파일**: `src/features/admin/components/NoticeManagement.tsx`
- **기능**:
  - 공지사항 목록에 MongoDB `_id` 표시
  - ID를 축약하여 표시 (예: `65a...f12`)
  - ID 옆에 복사 버튼 추가 (Copy 아이콘)
  - 복사 성공 시 Check 아이콘으로 변경 및 토스트 메시지 표시
  - `sonner` 라이브러리를 사용한 토스트 알림

**이유**: Admin이 공지사항 ID를 확인하여 배너/태그에 연결할 수 있도록 함

### 4. 온보딩 투어 버그 수정

#### 프론트엔드
- **파일**: `src/components/onboarding/AppTour.tsx`
- **수정 사항**:
  - **Step 4번(랭킹 페이지)에만 스크롤 애니메이션 추가**: `disableScrolling: false` 설정
  - **프로필 페이지에서 온보딩이 남아있는 버그 해결**: 완료 체크 로직 강화
  - **React 렌더링 중 상태 업데이트 경고 수정**: `stopTour()` 호출을 `useEffect`로 이동
  - **Help 버튼 클릭 시 투어 시작 안 되는 버그 수정**: 완료 체크 로직 개선

## 🔍 기술적 세부사항

### 클린코드 원칙 준수

#### 백엔드
- ✅ Indent Depth 1 이하 유지
- ✅ `else` 예약어 사용 금지 (Early Return 패턴)
- ✅ 한 메서드는 한 가지 일만 수행
- ✅ 중복 코드 제거: `MemberService`의 `completeOnboarding`과 `resetOnboarding`에서 공통 로직을 `findStudentByBojIdOrThrow` 메서드로 추출
- ✅ 불필요한 import 제거: `MaintenanceModeInterceptor`에서 사용하지 않는 `SimpleGrantedAuthority` import 제거

#### 프론트엔드
- ✅ 모든 import가 사용되고 있음 확인
- ✅ Early Return 패턴 사용
- ✅ 컴포넌트가 한 가지 일만 수행

### 구현 방식

#### 온보딩 리셋
1. **백엔드**: `Student.resetOnboarding()` 메서드로 `isOnboardingFinished`를 `false`로 변경
2. **프론트엔드**: Help 버튼 클릭 시 `resetOnboarding` API 호출 후 상태 동기화
3. **상태 관리**: `localStorage` 제거 및 Zustand 상태 리셋

#### Maintenance Mode ADMIN 접근
1. **권한 체크**: `SecurityContextHolder`에서 현재 사용자의 권한 확인
2. **조건부 허용**: ADMIN 권한이 있으면 유지보수 모드에서도 접근 허용
3. **차단 로직**: ADMIN이 아니거나 인증되지 않은 사용자는 `MAINTENANCE_MODE` 예외 발생

## ✅ 테스트

- 모든 기존 테스트 통과 확인 (`BUILD SUCCESSFUL`)
- `MemberService` 테스트 통과
- 프론트엔드 빌드 성공 (`✓ built`)

## 📝 API 변경사항

### PATCH `/api/v1/members/onboarding/reset` (신규)

**기능**: 사용자의 온보딩 완료 상태를 리셋합니다.

**Request:**
```http
PATCH /api/v1/members/onboarding/reset
Authorization: Bearer {token}
```

**Response:**
```http
204 No Content
```

**에러 응답:**
```json
{
  "status": 404,
  "error": "Not Found",
  "code": "STUDENT_NOT_FOUND",
  "message": "학생을 찾을 수 없습니다. bojId=non-existent"
}
```

### Maintenance Mode 동작 변경

**변경 전:**
- 유지보수 모드가 활성화되면 모든 사용자(ADMIN 포함) 접근 차단

**변경 후:**
- 유지보수 모드가 활성화되어도 ADMIN 권한이 있으면 접근 허용
- ADMIN이 아닌 사용자만 차단

## 🎨 사용자 경험 개선

### 온보딩 투어
- **이전**: Help 버튼(?)을 눌러도 온보딩이 재시작되지 않음
- **이후**: Help 버튼을 누르면 온보딩이 처음부터 다시 시작됨

### Maintenance Mode
- **이전**: 유지보수 모드가 활성화되면 ADMIN도 접근 불가
- **이후**: 유지보수 모드가 활성화되어도 ADMIN은 시스템 관리 가능

### Admin Panel
- **이전**: 공지사항 ID를 확인하기 어려움
- **이후**: 공지사항 목록에서 ID를 바로 확인하고 복사 가능

## 🔗 관련 이슈

- Help 버튼(?)을 눌러도 온보딩이 재시작되지 않는 문제 해결
- Maintenance Mode에서 ADMIN 접근 차단 문제 해결
- Admin Panel에서 공지사항 ID 확인 불가 문제 해결
- 온보딩 투어의 여러 버그 수정 (Zombie Tour, React 경고, 프로필 페이지 버그)

## 📚 참고 문서

- `DOCS/API_SPECIFICATION.md`: API 명세서 (resetOnboarding API 추가됨)
- `DOCS/PR_GUIDE.md`: 코드 스타일 가이드
- `DOCS/COMMIT_CONVENTION.md`: 커밋 컨벤션

## 📦 변경된 파일

### 백엔드
- `src/main/kotlin/com/didimlog/domain/Student.kt`
- `src/main/kotlin/com/didimlog/application/member/MemberService.kt`
- `src/main/kotlin/com/didimlog/ui/controller/MemberController.kt`
- `src/main/kotlin/com/didimlog/global/interceptor/MaintenanceModeInterceptor.kt`
- `DOCS/API_SPECIFICATION.md`

### 프론트엔드
- `src/api/endpoints/member.api.ts`
- `src/components/layout/Header.tsx`
- `src/components/onboarding/AppTour.tsx`
- `src/features/admin/components/NoticeManagement.tsx`

## 🚀 배포 전 체크리스트

- [x] 모든 테스트 통과
- [x] 클린코드 원칙 준수
- [x] API 명세서 업데이트
- [x] Swagger 태그 확인 (이미 통합됨)
- [x] 커밋 컨벤션 준수
