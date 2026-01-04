# PR Summary: 로그인 Rate Limiting 개선

## 📋 개요

로그인 API에 Rate Limiting 정보를 포함하여 사용자에게 남은 시도 횟수와 잠금 해제 시간을 알려주고, 로그인 성공 시 Rate Limit을 초기화하도록 개선했습니다. 또한 관리자 계정은 Rate Limiting에서 제외하여 관리 작업의 편의성을 높였습니다.

## 🔄 변경 사항

### Before (기존)
- 로그인 실패 시 단순히 401/400 에러만 반환
- 남은 시도 횟수 정보 없음
- 로그인 성공 시에도 Rate Limit 카운트 유지

### After (개선)
- 로그인 실패 시 남은 시도 횟수를 헤더와 바디에 포함
- 로그인 성공 시 Rate Limit 초기화 (정상 사용자임을 증명했으므로)
- Rate Limit 초과 시 한국시간으로 잠금 해제 시간 제공
- 관리자 계정은 Rate Limiting에서 제외
- 프론트엔드에서 남은 횟수와 잠금 해제 시간을 표시하여 사용자 경험 개선

## 🏗️ 구현 내용

### 1. ErrorResponse 확장
- `remainingAttempts` 필드 추가: Rate Limit 남은 횟수
- `unlockTime` 필드 추가: 한국시간으로 잠금 해제 시간 (ISO 8601 형식)
- 선택적 필드로 기존 API와 호환성 유지

### 2. AuthController.login() 수정
- 로그인 성공 시: `rateLimitService.reset()` 호출하여 Rate Limit 초기화
- 로그인 실패 시: 
  - `X-Rate-Limit-Remaining` 헤더 추가
  - `X-Rate-Limit-Limit` 헤더 추가
  - 응답 바디에 `remainingAttempts` 필드 포함

### 3. RateLimitInterceptor 개선
- 관리자 계정(ADMIN 권한)은 Rate Limiting에서 제외
- Rate Limit 초과 시 한국시간(Asia/Seoul)으로 잠금 해제 시간 계산
- `RateLimitService.getTtlSeconds()` 메서드 추가: Redis TTL 조회

### 4. Rate Limiting 설정
- 최대 시도 횟수: 10회
- 시간 윈도우: 1시간
- 로그인 성공 시 즉시 초기화
- 관리자 계정 예외 처리

## 📝 API 변경 사항

### 엔드포인트
- `POST /api/v1/auth/login` (변경됨)

### Request (변경 없음)
```json
{
  "bojId": "user123",
  "password": "password123"
}
```

### Response (성공 시 - 변경 없음)
```json
{
  "token": "eyJhbGci...",
  "refreshToken": "eyJhbGci...",
  "message": "로그인에 성공했습니다.",
  "rating": 1223,
  "tier": "GOLD",
  "tierLevel": 13
}
```

### Response (실패 시 - 변경됨)
**응답 바디:**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "COMMON_INVALID_INPUT",
  "message": "비밀번호가 일치하지 않습니다.",
  "remainingAttempts": 9
}
```

**응답 헤더:**
```
X-Rate-Limit-Remaining: 9
X-Rate-Limit-Limit: 10
```

### Response (Rate Limit 초과 시 - 변경됨)
**응답 바디:**
```json
{
  "status": 429,
  "error": "Too Many Requests",
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "로그인 요청이 너무 많습니다. 1시간 후 다시 시도해주세요.",
  "unlockTime": "2024-01-15T14:30:00+09:00"
}
```

**unlockTime 필드:**
- 한국시간(Asia/Seoul, UTC+9)으로 표시된 잠금 해제 시간
- ISO 8601 형식 (예: "2024-01-15T14:30:00+09:00")
- 프론트엔드에서 "2024년 1월 15일 14시 30분에 다시 시도 가능합니다" 같은 메시지 표시 가능

## 🧪 테스트

- 로그인 성공 시 Rate Limit 초기화 검증
- 로그인 실패 시 남은 횟수 정보 포함 검증
- 응답 헤더 검증

## 💡 사용자 경험 개선

### Before
- 사용자가 로그인 실패 시 남은 횟수를 알 수 없음
- 10회 실패 후 갑자기 차단되어 혼란
- Rate Limit 초과 시 언제 다시 시도할 수 있는지 알 수 없음
- 관리자도 Rate Limit에 걸려 관리 작업이 불편함

### After
- 로그인 실패 시 남은 횟수를 명확히 표시
- Rate Limit 초과 시 한국시간으로 잠금 해제 시간 제공
- 로그인 성공 시 Rate Limit이 초기화되어 정상 사용자는 제한 없이 사용 가능
- 관리자 계정은 Rate Limiting에서 제외되어 관리 작업 편의성 향상
- 프론트엔드에서 "남은 시도 횟수: 9회" 또는 "2024년 1월 15일 14시 30분에 다시 시도 가능합니다" 같은 안내 메시지 표시 가능

## 🔒 보안 고려사항

- 로그인 성공 시에만 Rate Limit 초기화 (정상 사용자 확인 후)
- 로그인 실패 시에는 카운트 유지 (무차별 대입 공격 방지)
- IP 기반 Rate Limiting으로 동일 IP에서의 반복 시도 제한
- 관리자 계정은 Rate Limiting에서 제외 (관리 작업 편의성)
- 잠금 해제 시간은 한국시간으로 제공하여 사용자 혼란 최소화

## 📚 프론트엔드 수정 가이드

### 1. 로그인 실패 시 남은 횟수 표시

```typescript
async function login(bojId: string, password: string) {
  try {
    const response = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ bojId, password }),
    });

    if (!response.ok) {
      const error = await response.json();
      const remainingAttempts = error.remainingAttempts;
      const headerRemaining = response.headers.get('X-Rate-Limit-Remaining');
      
      // 바디 또는 헤더에서 남은 횟수 확인
      const remaining = remainingAttempts ?? parseInt(headerRemaining ?? '0', 10);
      
      if (remaining > 0) {
        showError(`비밀번호가 일치하지 않습니다. 남은 시도 횟수: ${remaining}회`);
      } else {
        showError('로그인 시도 횟수를 초과했습니다. 1시간 후 다시 시도해주세요.');
      }
      return;
    }

    const data = await response.json();
    // 로그인 성공 처리
  } catch (error) {
    showError('로그인 중 오류가 발생했습니다.');
  }
}
```

### 2. Rate Limit 헤더 확인

```typescript
const remaining = response.headers.get('X-Rate-Limit-Remaining');
const limit = response.headers.get('X-Rate-Limit-Limit');

if (remaining !== null && limit !== null) {
  console.log(`Rate Limit: ${remaining}/${limit}`);
}
```

### 3. Rate Limit 초과 시 잠금 해제 시간 표시

```typescript
async function login(bojId: string, password: string) {
  try {
    const response = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ bojId, password }),
    });

    if (response.status === 429) {
      const error = await response.json();
      const unlockTime = error.unlockTime; // "2024-01-15T14:30:00+09:00"
      
      if (unlockTime) {
        const unlockDate = new Date(unlockTime);
        const formattedTime = unlockDate.toLocaleString('ko-KR', {
          year: 'numeric',
          month: 'long',
          day: 'numeric',
          hour: '2-digit',
          minute: '2-digit',
        });
        showError(`요청이 너무 많습니다. ${formattedTime}에 다시 시도해주세요.`);
      } else {
        showError('요청이 너무 많습니다. 1시간 후 다시 시도해주세요.');
      }
      return;
    }

    // 로그인 성공 처리
  } catch (error) {
    showError('로그인 중 오류가 발생했습니다.');
  }
}
```

## ✅ 체크리스트

- [x] ErrorResponse에 remainingAttempts 필드 추가
- [x] ErrorResponse에 unlockTime 필드 추가 (한국시간)
- [x] 로그인 성공 시 Rate Limit 초기화 로직 추가
- [x] 로그인 실패 시 Rate Limit 정보 포함
- [x] Rate Limit 초과 시 잠금 해제 시간 계산 및 포함
- [x] 관리자 계정 Rate Limiting 제외
- [x] 응답 헤더에 Rate Limit 정보 추가
- [x] 단위 테스트 작성 및 검증
- [x] API 명세서 업데이트
- [x] PR 요약 문서 작성

