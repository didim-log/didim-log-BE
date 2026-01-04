# PR Summary: BOJ 소유권 인증 로직 개선

## 📋 개요

`POST /api/v1/auth/boj/verify` API의 검증 로직을 개선하여 정확도와 에러 처리 명확성을 향상시켰습니다.

## 🔄 변경 사항

### Before (기존)
- **검증 방식**: 백준 프로필 페이지 **본문 전체 텍스트**에서 인증 코드 검색
- **문제점**:
  - 페이지의 모든 텍스트를 검색하여 오탐 가능성 존재
  - 에러 구분이 불명확 (404가 STUDENT_NOT_FOUND로 오인될 수 있음)
  - HTML 구조 변경에 취약

### After (개선)
- **검증 방식**: 백준 프로필 **상태 메시지(`blockquote.no-mathjax`)**만 추출하여 검증
- **개선점**:
  - 상태 메시지만 검증하여 정확도 향상
  - 크롤링 결과를 명확히 구분 (404/403/파싱 실패/성공)
  - 에러 메시지 및 로그 개선으로 디버깅 용이성 향상
  - 결과 타입을 명확히 정의 (`BojProfileStatusMessageFetchResult`)

## 🏗️ 아키텍처 변경

### 신규 클래스
1. **`BojProfileStatusMessageFetchResult`** (Sealed Class)
   - `Found`: 상태 메시지 추출 성공
   - `UserNotFound`: 백준 프로필 404
   - `AccessDenied`: 백준 프로필 403
   - `StatusMessageNotFound`: 상태 메시지 파싱 실패
   - `Failed`: 기타 네트워크/예외 오류

2. **`BojVerificationCodeMatcher`**
   - 인증 코드 매칭 로직 분리
   - 단어 경계 기반 정확한 매칭 (부분 문자열 오탐 방지)

3. **`BojProfileStatusMessage`** (Value Object)
   - 상태 메시지 원시값 포장

### 수정된 클래스
- `BojOwnershipVerificationService`: 상태 메시지 기반 검증으로 변경
- `JsoupBojProfileStatusMessageClient`: 결과 타입을 `BojProfileStatusMessageFetchResult`로 반환
- `BojProfileStatusMessageClient`: 인터페이스 시그니처 변경

## 🧪 테스트

- **단위 테스트 추가**:
  - `BojOwnershipVerificationServiceTest`: 404/403/파싱 실패 케이스 추가
  - `BojVerificationCodeMatcherTest`: 코드 매칭 로직 테스트
- **네트워크 의존성 제거**: Mock을 사용하여 테스트 안정성 향상

## 📝 API 변경 사항

### 엔드포인트
- `POST /api/v1/auth/boj/verify` (변경 없음)

### Request (변경 없음)
```json
{
  "sessionId": "uuid-string",
  "bojId": "mekazon"
}
```

### Response (변경 없음)
```json
{
  "verified": true
}
```

### ⚠️ 에러 응답 변경 (중요)

#### 1. 백준 프로필을 찾을 수 없음 (404)
**Before:**
- 에러 코드: `STUDENT_NOT_FOUND` (혼동 가능)
- 메시지: "사용자를 찾을 수 없습니다. bojId=mekazon"

**After:**
- 에러 코드: `COMMON_RESOURCE_NOT_FOUND` ✅
- 메시지: "백준 프로필을 찾을 수 없습니다. BOJ ID가 올바른지 확인해주세요. bojId=mekazon"
- HTTP Status: `404`

```json
{
  "status": 404,
  "error": "Not Found",
  "code": "COMMON_RESOURCE_NOT_FOUND",
  "message": "백준 프로필을 찾을 수 없습니다. BOJ ID가 올바른지 확인해주세요. bojId=mekazon"
}
```

#### 2. 프로필 접근 거부 (403)
**Before:**
- 에러 코드: `COMMON_INTERNAL_ERROR` (혼동 가능)
- 메시지: "백준 프로필 페이지를 가져오는 중 오류가 발생했습니다."

**After:**
- 에러 코드: `COMMON_INVALID_INPUT` ✅
- 메시지: "백준 프로필 페이지에 접근할 수 없습니다. 프로필이 공개되어 있는지 확인해주세요. bojId=mekazon"
- HTTP Status: `400`

```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_INVALID_INPUT",
  "message": "백준 프로필 페이지에 접근할 수 없습니다. 프로필이 공개되어 있는지 확인해주세요. bojId=mekazon"
}
```

#### 3. 상태 메시지를 찾을 수 없음
**Before:**
- 에러 코드: `COMMON_INVALID_INPUT`
- 메시지: "프로필 페이지에서 코드를 찾을 수 없습니다."

**After:**
- 에러 코드: `COMMON_INVALID_INPUT` (동일)
- 메시지: "백준 프로필 상태 메시지를 찾을 수 없습니다. 프로필 상태 메시지에 인증 코드를 입력하고 저장한 뒤 다시 시도해주세요. bojId=mekazon"
- HTTP Status: `400`

```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_INVALID_INPUT",
  "message": "백준 프로필 상태 메시지를 찾을 수 없습니다. 프로필 상태 메시지에 인증 코드를 입력하고 저장한 뒤 다시 시도해주세요. bojId=mekazon"
}
```

#### 4. 코드가 상태 메시지에 없음 (변경 없음)
- 에러 코드: `COMMON_INVALID_INPUT`
- 메시지: "프로필 페이지에서 코드를 찾을 수 없습니다. 프로필 상태 메시지에 인증 코드(DIDIM-LOG-XXXXXX)를 정확히 입력하고 저장한 후, 몇 초 대기한 뒤 다시 시도해주세요."
- HTTP Status: `400`

#### 5. DB에서 학생을 찾을 수 없음 (변경 없음)
- 에러 코드: `STUDENT_NOT_FOUND`
- 메시지: "사용자를 찾을 수 없습니다. bojId=mekazon"
- HTTP Status: `404`
- **참고**: 이 에러는 코드 검증을 통과한 후에만 발생합니다.

---

# 🎨 프론트엔드 수정 가이드

## 📌 필수 수정 사항

### 1. 에러 코드 처리 로직 업데이트

#### 변경 전
```typescript
// ❌ 기존: STUDENT_NOT_FOUND가 백준 프로필 404를 의미할 수 있었음
if (error.code === 'STUDENT_NOT_FOUND') {
  // 백준 프로필을 찾을 수 없음 또는 DB에 학생이 없음 (혼동)
}
```

#### 변경 후
```typescript
// ✅ 개선: 에러 코드가 명확히 구분됨
if (error.code === 'COMMON_RESOURCE_NOT_FOUND') {
  // 백준 프로필을 찾을 수 없음 (BOJ ID가 유효하지 않음)
  showError('백준 프로필을 찾을 수 없습니다. BOJ ID가 올바른지 확인해주세요.');
} else if (error.code === 'STUDENT_NOT_FOUND') {
  // 코드 검증은 성공했지만 DB에 학생이 없음 (드문 경우)
  showError('사용자를 찾을 수 없습니다. 회원가입을 먼저 진행해주세요.');
}
```

### 2. 에러 메시지 표시 개선

#### 권장 에러 처리 로직
```typescript
async function verifyBojOwnership(sessionId: string, bojId: string) {
  try {
    const response = await fetch('/api/v1/auth/boj/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId, bojId }),
    });

    if (!response.ok) {
      const error = await response.json();
      
      switch (error.code) {
        case 'COMMON_RESOURCE_NOT_FOUND':
          // 백준 프로필 404
          return {
            success: false,
            message: '백준 프로필을 찾을 수 없습니다. BOJ ID를 확인해주세요.',
            userAction: 'BOJ ID를 다시 확인하고 올바른 ID를 입력해주세요.',
          };
        
        case 'COMMON_INVALID_INPUT':
          // 403 접근 거부 또는 상태 메시지 파싱 실패 또는 코드 불일치
          if (error.message.includes('접근할 수 없습니다')) {
            return {
              success: false,
              message: '백준 프로필에 접근할 수 없습니다.',
              userAction: '백준 프로필이 공개되어 있는지 확인해주세요.',
            };
          } else if (error.message.includes('상태 메시지를 찾을 수 없음')) {
            return {
              success: false,
              message: '프로필 상태 메시지를 찾을 수 없습니다.',
              userAction: '프로필 상태 메시지에 인증 코드를 입력하고 저장한 뒤 다시 시도해주세요.',
            };
          } else {
            // 코드 불일치
            return {
              success: false,
              message: '인증 코드를 찾을 수 없습니다.',
              userAction: '프로필 상태 메시지에 발급된 코드를 정확히 입력하고 저장한 후, 몇 초 대기한 뒤 다시 시도해주세요.',
            };
          }
        
        case 'STUDENT_NOT_FOUND':
          // 코드 검증 성공 후 DB 조회 실패 (드문 경우)
          return {
            success: false,
            message: '사용자를 찾을 수 없습니다.',
            userAction: '회원가입을 먼저 진행해주세요.',
          };
        
        default:
          return {
            success: false,
            message: error.message || '인증 중 오류가 발생했습니다.',
            userAction: '잠시 후 다시 시도해주세요.',
          };
      }
    }

    const data = await response.json();
    return { success: true, data };
  } catch (error) {
    return {
      success: false,
      message: '네트워크 오류가 발생했습니다.',
      userAction: '인터넷 연결을 확인하고 다시 시도해주세요.',
    };
  }
}
```

### 3. 사용자 안내 메시지 개선

#### 상태별 안내 메시지 예시

```typescript
const ERROR_GUIDES = {
  COMMON_RESOURCE_NOT_FOUND: {
    title: '백준 프로필을 찾을 수 없습니다',
    description: '입력하신 BOJ ID가 존재하지 않거나 올바르지 않습니다.',
    steps: [
      '백준 웹사이트(https://www.acmicpc.net)에서 로그인하여 프로필 페이지를 확인해주세요.',
      '프로필 URL이 https://www.acmicpc.net/user/{bojId} 형식인지 확인해주세요.',
      'BOJ ID에 오타가 없는지 확인해주세요.',
    ],
  },
  ACCESS_DENIED: {
    title: '프로필에 접근할 수 없습니다',
    description: '백준 프로필이 비공개 상태일 수 있습니다.',
    steps: [
      '백준 프로필 설정에서 프로필 공개 여부를 확인해주세요.',
      '프로필이 공개되어 있는지 확인한 후 다시 시도해주세요.',
    ],
  },
  STATUS_MESSAGE_NOT_FOUND: {
    title: '상태 메시지를 찾을 수 없습니다',
    description: '프로필 상태 메시지가 비어있거나 HTML 구조가 변경되었을 수 있습니다.',
    steps: [
      '백준 프로필 페이지에서 상태 메시지가 표시되는지 확인해주세요.',
      '상태 메시지에 인증 코드를 입력하고 저장한 뒤 다시 시도해주세요.',
    ],
  },
  CODE_MISMATCH: {
    title: '인증 코드를 찾을 수 없습니다',
    description: '프로필 상태 메시지에 발급된 코드가 정확히 입력되지 않았습니다.',
    steps: [
      '발급받은 인증 코드를 복사하여 프로필 상태 메시지에 정확히 입력해주세요.',
      '코드 입력 후 저장 버튼을 눌러주세요.',
      '저장 후 몇 초 대기한 뒤 다시 시도해주세요.',
    ],
  },
};
```

## 🔍 선택적 개선 사항

### 1. 에러 로깅 개선
프론트엔드에서도 에러를 더 상세히 로깅하여 디버깅을 용이하게 할 수 있습니다.

```typescript
function logVerificationError(error: any, bojId: string) {
  console.error('[BOJ Verification Error]', {
    code: error.code,
    message: error.message,
    bojId,
    timestamp: new Date().toISOString(),
  });
  
  // 에러 추적 서비스에 전송 (선택적)
  // errorTrackingService.track('boj_verification_error', { ... });
}
```

### 2. 재시도 로직 개선
상태 메시지 파싱 실패나 코드 불일치의 경우, 사용자에게 재시도 안내를 제공할 수 있습니다.

```typescript
async function verifyWithRetry(sessionId: string, bojId: string, maxRetries = 3) {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    const result = await verifyBojOwnership(sessionId, bojId);
    
    if (result.success) {
      return result;
    }
    
    // 코드 불일치나 상태 메시지 파싱 실패인 경우에만 재시도
    if (
      result.errorCode === 'COMMON_INVALID_INPUT' &&
      attempt < maxRetries
    ) {
      await new Promise(resolve => setTimeout(resolve, 2000 * attempt)); // 지수 백오프
      continue;
    }
    
    return result;
  }
}
```

### 3. 사용자 가이드 UI 개선
에러 발생 시 단계별 안내를 제공하는 UI를 추가할 수 있습니다.

```tsx
function BojVerificationErrorGuide({ errorCode, bojId }: Props) {
  const guide = ERROR_GUIDES[errorCode];
  
  if (!guide) return null;
  
  return (
    <div className="error-guide">
      <h3>{guide.title}</h3>
      <p>{guide.description}</p>
      <ol>
        {guide.steps.map((step, index) => (
          <li key={index}>{step}</li>
        ))}
      </ol>
      {errorCode === 'CODE_MISMATCH' && (
        <div className="code-reminder">
          <p>발급된 코드를 다시 확인하세요:</p>
          <code>{/* 발급된 코드 표시 */}</code>
        </div>
      )}
    </div>
  );
}
```

## 📊 에러 코드 매핑 테이블

| 상황 | 에러 코드 | HTTP Status | 프론트엔드 처리 |
|------|----------|-------------|----------------|
| 백준 프로필 404 | `COMMON_RESOURCE_NOT_FOUND` | 404 | BOJ ID 확인 안내 |
| 백준 프로필 403 | `COMMON_INVALID_INPUT` | 400 | 프로필 공개 확인 안내 |
| 상태 메시지 파싱 실패 | `COMMON_INVALID_INPUT` | 400 | 상태 메시지 입력 안내 |
| 코드 불일치 | `COMMON_INVALID_INPUT` | 400 | 코드 재입력 안내 |
| DB에 학생 없음 | `STUDENT_NOT_FOUND` | 404 | 회원가입 안내 |
| 네트워크 오류 | `COMMON_INTERNAL_ERROR` | 500 | 재시도 안내 |

## ✅ 체크리스트

프론트엔드 개발 시 다음 사항을 확인하세요:

- [ ] `COMMON_RESOURCE_NOT_FOUND` 에러 코드 처리 추가
- [ ] `STUDENT_NOT_FOUND`와 `COMMON_RESOURCE_NOT_FOUND` 구분 처리
- [ ] 에러 메시지에 따른 사용자 안내 개선
- [ ] 상태 메시지 관련 에러 처리 추가
- [ ] 에러 로깅 개선 (선택적)
- [ ] 재시도 로직 개선 (선택적)
- [ ] 사용자 가이드 UI 추가 (선택적)

## 🔗 관련 문서

- [API 명세서](./API_SPECIFICATION.md#authcontroller) - `POST /api/v1/auth/boj/verify` 섹션
- [에러 코드 목록](./API_SPECIFICATION.md#에러-응답-형식) - 공통 에러 코드 참조


