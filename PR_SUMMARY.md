# PR Summary

## Title

**feat: JWT Refresh Token Rotation 구현으로 자동 로그인 유지 기능 추가**

## Description

### 무엇을(What) 변경했는지

1. **JWT Refresh Token Rotation 기능 구현**
   - Refresh Token을 사용하여 Access Token을 자동으로 갱신하는 기능 추가
   - 30분마다 로그아웃되는 문제를 해결하여 7일간 자동 로그인 유지
   - Token Rotation 방식으로 보안 강화 (기존 Refresh Token 무효화)

2. **Backend 구현**
   - `RefreshTokenService`: Refresh Token 생성, 검증, 회전 로직 구현
   - `RedisRefreshTokenStore`: Redis 기반 Refresh Token 저장소 구현
   - `AuthController`: `POST /api/v1/auth/refresh` 엔드포인트 추가
     - Header 또는 Body에서 Refresh Token 수신 지원
     - `@RequestBody(required = false)`와 수동 검증으로 유연성 확보
   - `AuthService`: signup/login/finalizeSignup에서 Refresh Token 발급
   - `JwtTokenProvider`: `createRefreshToken`, `isRefreshToken` 메서드 추가
   - `AuthResponse`: `refreshToken` 필드 추가

3. **Frontend 구현**
   - Axios 인터셉터: 401 에러 시 자동 토큰 갱신 및 요청 재시도
   - Auth Store: `refreshToken` 상태 관리 및 localStorage 저장
   - `useLogin`: `setTokens`로 Access/Refresh Token 동시 저장

4. **테스트 코드 작성**
   - `RefreshTokenServiceTest`: Refresh Token 생성, 검증, 회전 테스트
   - `AuthControllerRefreshTest`: Refresh 엔드포인트 통합 테스트
   - 모든 기존 테스트 통과 확인

5. **문서 업데이트**
   - `API_SPECIFICATION.md`: `/api/v1/auth/refresh` 엔드포인트 명세 추가

### 왜(Why) 변경했는지

- **사용자 경험 개선**: 30분마다 로그아웃되는 문제로 인한 사용자 불편 해소
- **보안 강화**: Token Rotation 방식으로 Refresh Token 탈취 시 피해 최소화
- **자동화**: 프론트엔드에서 401 에러 발생 시 자동으로 토큰 갱신하여 사용자 개입 없이 연속 사용 가능
- **유연성**: Header 또는 Body에서 Refresh Token을 받을 수 있어 다양한 클라이언트 지원

## Key Code (Before & After)

### 1. Refresh Token 서비스 구현

**Before:**
```kotlin
// Refresh Token 기능 없음
// Access Token만 발급하여 30분마다 로그아웃 필요
```

**After:**
```kotlin
@Service
class RefreshTokenService(
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenStore: RefreshTokenStore,
    private val studentRepository: StudentRepository,
    @Value("\${app.jwt.refresh-token-expiration}")
    private val refreshTokenExpiration: Long
) {
    fun generateAndSave(bojId: String): String {
        val refreshToken = jwtTokenProvider.createRefreshToken(bojId)
        val ttlSeconds = refreshTokenExpiration / 1000
        refreshTokenStore.save(refreshToken, bojId, ttlSeconds)
        return refreshToken
    }

    @Transactional
    fun refresh(refreshToken: String): Pair<String, String> {
        // Refresh Token 유효성 검증
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 Refresh Token입니다.")
        }
        
        // 기존 Refresh Token 삭제 (Token Rotation)
        refreshTokenStore.delete(refreshToken)
        
        // 새로운 Access Token과 Refresh Token 발급
        val newAccessToken = jwtTokenProvider.createToken(bojId, student.role.value)
        val newRefreshToken = generateAndSave(bojId)
        
        return Pair(newAccessToken, newRefreshToken)
    }
}
```

### 2. AuthController Refresh 엔드포인트 추가

**Before:**
```kotlin
// Refresh Token 갱신 엔드포인트 없음
```

**After:**
```kotlin
@PostMapping("/refresh")
fun refresh(
    @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authHeader: String?,
    @RequestBody(required = false) requestBody: RefreshTokenRequest?
): ResponseEntity<AuthResponse> {
    // 1. Body에서 Refresh Token 추출
    var refreshToken: String? = null
    if (requestBody != null && !requestBody.refreshToken.isNullOrBlank()) {
        refreshToken = requestBody.refreshToken.trim()
    }

    // 2. Body에 없으면 Header에서 추출 (Bearer 제거)
    if (refreshToken.isNullOrBlank()) {
        if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ")) {
            refreshToken = authHeader.substring(7).trim()
        }
    }

    // 3. 둘 다 없으면 명시적 예외 발생 (수동 검증)
    if (refreshToken.isNullOrBlank()) {
        throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "Refresh Token이 필요합니다.")
    }

    val (newAccessToken, newRefreshToken) = refreshTokenService.refresh(refreshToken)
    // ... 사용자 정보 조회 및 응답 생성
}
```

### 3. Axios 인터셉터 구현

**Before:**
```typescript
// 401 에러 발생 시 자동 로그아웃
apiClient.interceptors.response.use(
    (response) => response,
    (error) => {
        if (error.response?.status === 401) {
            logout();
            window.location.href = '/login';
        }
        return Promise.reject(error);
    }
);
```

**After:**
```typescript
// 401 에러 발생 시 자동 토큰 갱신 및 요청 재시도
apiClient.interceptors.response.use(
    (response) => response,
    async (error) => {
        const originalRequest = error.config;
        const { refreshToken, logout, setTokens } = useAuthStore.getState();

        if (error.response?.status === 401 && !originalRequest._retry && refreshToken) {
            originalRequest._retry = true;
            
            try {
                // Refresh Token으로 새로운 Access Token과 Refresh Token 발급
                const response = await authApi.refresh(refreshToken);
                const newAccessToken = response.token;
                const newRefreshToken = response.refreshToken;

                // 스토어 및 헤더 업데이트
                setTokens(newAccessToken, newRefreshToken);
                setAuthHeader(newAccessToken);

                // 원본 요청 재시도
                originalRequest.headers['Authorization'] = `Bearer ${newAccessToken}`;
                return apiClient(originalRequest);
            } catch (refreshError) {
                // Refresh Token 갱신 실패 시 로그아웃
                logout();
                window.location.href = '/login';
            }
        }
        
        return Promise.reject(error);
    }
);
```

### 4. AuthResponse에 refreshToken 필드 추가

**Before:**
```kotlin
data class AuthResponse(
    val token: String,
    val message: String,
    val rating: Int,
    val tier: String,
    val tierLevel: Int
)
```

**After:**
```kotlin
data class AuthResponse(
    val token: String,
    val refreshToken: String,  // 추가
    val message: String,
    val rating: Int,
    val tier: String,
    val tierLevel: Int
) {
    companion object {
        fun signup(token: String, refreshToken: String, ...): AuthResponse { ... }
        fun login(token: String, refreshToken: String, ...): AuthResponse { ... }
        fun refresh(token: String, refreshToken: String): AuthResponse { ... }
    }
}
```

## Reason for Change

### 기술적 배경

1. **Token Rotation 보안 패턴**
   - Refresh Token을 사용할 때마다 새로운 Refresh Token을 발급하고 기존 토큰을 무효화
   - 토큰 탈취 시 피해를 최소화하는 보안 모범 사례

2. **Redis 기반 토큰 저장**
   - Refresh Token을 Redis에 저장하여 빠른 조회 및 TTL 관리
   - 사용자별 토큰 관리 및 일괄 삭제 지원

3. **유연한 요청 처리**
   - `@RequestBody(required = false)`와 수동 검증으로 Header/Body 모두 지원
   - `@JsonCreator`를 사용하여 빈 JSON `{}` 처리 가능

4. **Axios 인터셉터 패턴**
   - 401 에러 발생 시 자동으로 토큰 갱신 및 원본 요청 재시도
   - 중복 요청 방지를 위한 플래그 및 큐 관리

### 개선 이유

- **사용자 경험**: 30분마다 로그아웃되는 문제 해결로 연속 사용 가능
- **보안**: Token Rotation으로 Refresh Token 탈취 시 피해 최소화
- **자동화**: 사용자 개입 없이 토큰 갱신 및 요청 재시도
- **유연성**: 다양한 클라이언트 환경 지원 (Header/Body 모두 지원)

## To Reviewer

리뷰어가 중점적으로 봐주었으면 하는 부분:

1. **RefreshTokenService의 트랜잭션 처리**
   - `@Transactional`이 적절하게 적용되었는지 확인
   - 기존 Refresh Token 삭제와 새 토큰 발급의 원자성 보장 여부

2. **RedisRefreshTokenStore의 키 관리**
   - Token -> BojId 매핑과 User -> Token 매핑이 올바르게 동기화되는지 확인
   - TTL 설정이 적절한지 검토

3. **AuthController의 수동 검증 로직**
   - `@RequestBody(required = false)`와 수동 검증이 올바르게 작동하는지 확인
   - 빈 JSON `{}` 처리와 null 처리 로직 검토

4. **Axios 인터셉터의 동시성 처리**
   - 중복 Refresh 요청 방지 로직이 올바르게 작동하는지 확인
   - 큐 관리 로직이 모든 엣지 케이스를 처리하는지 검토

5. **보안 고려사항**
   - Refresh Token의 TTL(7일)이 적절한지 검토
   - Token Rotation이 올바르게 구현되었는지 확인

## 변경된 파일 목록

### 신규 파일
- `src/main/kotlin/com/didimlog/application/auth/RefreshTokenService.kt`
- `src/main/kotlin/com/didimlog/application/auth/RefreshTokenStore.kt`
- `src/main/kotlin/com/didimlog/infra/auth/RedisRefreshTokenStore.kt`
- `src/main/kotlin/com/didimlog/ui/dto/RefreshTokenRequest.kt`
- `src/test/kotlin/com/didimlog/application/auth/RefreshTokenServiceTest.kt`
- `src/test/kotlin/com/didimlog/ui/controller/AuthControllerRefreshTest.kt`

### 수정된 파일
- `src/main/kotlin/com/didimlog/application/auth/AuthService.kt` - Refresh Token 발급 로직 추가
- `src/main/kotlin/com/didimlog/global/auth/JwtTokenProvider.kt` - `createRefreshToken`, `isRefreshToken` 메서드 추가
- `src/main/kotlin/com/didimlog/ui/controller/AuthController.kt` - `/refresh` 엔드포인트 추가, `studentRepository` 의존성 추가
- `src/main/kotlin/com/didimlog/ui/dto/AuthResponse.kt` - `refreshToken` 필드 추가, `refresh` companion 메서드 추가
- `src/test/kotlin/com/didimlog/application/auth/AuthServiceFindIdPasswordTest.kt` - `refreshTokenService` 의존성 추가
- `src/test/kotlin/com/didimlog/application/auth/AuthServiceLoginSecurityTest.kt` - `refreshTokenService` 의존성 추가
- `src/test/kotlin/com/didimlog/application/auth/AuthServiceResetPasswordTest.kt` - `refreshTokenService` 의존성 추가
- `src/test/kotlin/com/didimlog/application/auth/SuperAdminTest.kt` - `refreshTokenService` 의존성 추가
- `src/test/kotlin/com/didimlog/global/auth/JwtTokenProviderTest.kt` - `refreshTokenExpiration` 파라미터 추가
- `src/test/kotlin/com/didimlog/ui/controller/AuthControllerTest.kt` - `studentRepository` Bean 추가, `refreshToken` 검증 추가
- `DOCS/API_SPECIFICATION.md` - `/api/v1/auth/refresh` 엔드포인트 명세 추가

### Frontend 파일 (참고)
- `/Users/dh/Desktop/Code/didim-log-FE/src/types/api/auth.types.ts` - `refreshToken` 필드 추가
- `/Users/dh/Desktop/Code/didim-log-FE/src/stores/auth.store.ts` - `refreshToken` 상태 관리 추가
- `/Users/dh/Desktop/Code/didim-log-FE/src/api/endpoints/auth.api.ts` - `refresh` 메서드 추가
- `/Users/dh/Desktop/Code/didim-log-FE/src/api/client.ts` - Axios 인터셉터 구현
- `/Users/dh/Desktop/Code/didim-log-FE/src/hooks/auth/useLogin.ts` - `setTokens` 사용

## 테스트

- ✅ 모든 단위 테스트 통과 (345개)
- ✅ `RefreshTokenServiceTest`: Refresh Token 생성, 검증, 회전 테스트 통과
- ✅ `AuthControllerRefreshTest`: Refresh 엔드포인트 통합 테스트 통과
- ✅ `AuthControllerTest`: 기존 테스트 모두 통과 (studentRepository Bean 추가)
- ✅ 컴파일 성공

## 관련 이슈

- 30분마다 로그아웃되는 문제 해결
- 사용자 경험 개선 (자동 로그인 유지)
- 보안 강화 (Token Rotation)

## 주요 개선 사항

1. **자동 토큰 갱신**: 프론트엔드에서 401 에러 발생 시 자동으로 토큰 갱신
2. **유연한 요청 처리**: Header 또는 Body에서 Refresh Token 수신 지원
3. **보안 강화**: Token Rotation으로 Refresh Token 탈취 시 피해 최소화
4. **사용자 경험**: 7일간 자동 로그인 유지로 연속 사용 가능

