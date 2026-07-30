# Phase 2C-1 — JWT 토큰 용도 분리

## 문제

기존 인증 필터는 JWT의 서명과 만료만 확인했다. Refresh Token에는 `role`이 없지만
필터가 이를 `USER`로 바꿔 인증 정보를 만들었다.

```text
Refresh Token
→ 서명·만료 검증 통과
→ role 없음
→ USER 기본값 적용
→ 보호 API 접근
```

필터는 Redis의 Refresh Token 저장 여부를 확인하지 않는다. 따라서 Redis에서 폐기한
Refresh Token도 JWT 자체가 만료되기 전까지 보호 API의 Bearer Token으로 사용할 수
있었다.

실제 Refresh Token을 `/api/v1/feedback`에 전달한 재현 테스트에서는 수정 전 응답이
`401`이 아니라 `201`이었다.

## 변경

Access Token과 Refresh Token의 용도를 JWT claim으로 구분한다.

| 토큰 | type | role | 허용 범위 |
| --- | --- | --- | --- |
| Access Token | `access` | `USER` 또는 `ADMIN` | 보호 API 인증 |
| Refresh Token | `refresh` | 없음 | `/api/v1/auth/refresh`에서 토큰 갱신 |

인증 필터는 다음 조건을 모두 만족할 때만 SecurityContext에 인증 정보를 넣는다.

```text
서명·만료 유효
→ type=access
→ subject가 빈 값이 아님
→ role이 USER 또는 ADMIN
```

Refresh Token, type이 없는 기존 토큰, role이 없는 토큰과 GUEST Token은 인증 정보
없이 다음 필터로 전달한다. 보호 API에서는 기존 인증 진입점이 `401`을 반환한다.
`/api/v1/auth/refresh`는 공개 경로이므로 Authorization 헤더로 전달된 Refresh Token을
그대로 갱신 서비스에서 처리할 수 있다.

운영 코드에서 사용하지 않던 role 없는 Access Token 생성 메서드는 제거했다. k6와 로컬
사전 검증 스크립트가 직접 생성하는 JWT에도 `type=access`를 추가했다.

## 호환성

기존에 발급된 Access Token에는 type claim이 없으므로 변경 적용 뒤 보호 API에서
거절된다. 일반 로그인 사용자는 저장된 Refresh Token으로 새 Access Token을 발급받을
수 있다. 구형 토큰을 Access Token으로 임시 인정하는 예외는 두지 않았다.

FE는 JWT claim을 인증 판단에 사용하지 않고 토큰 문자열을 구분해 저장하므로 응답 DTO는
변경하지 않았다.

## 검증

수정 전 재현 테스트는 Refresh Token을 보호 API에 전달했을 때 `401`을 기대했지만
실제 `201`을 반환하며 실패했다. 수정 후 같은 요청은 `401`과 `UNAUTHORIZED`를
반환했다. Authorization 헤더로 Refresh Token을 전달하는 갱신 경로는 `200`을
유지했다.

```bash
./gradlew test \
  --tests 'com.didimlog.global.auth.JwtTokenProviderTest' \
  --tests 'com.didimlog.global.auth.JwtAuthenticationFilterTest' \
  --no-daemon

./gradlew integrationTest \
  --tests 'com.didimlog.global.security.SecurityIntegrationTest' \
  --tests 'com.didimlog.global.security.JwtTokenProviderIntegrationTest' \
  --no-daemon

./gradlew clean check \
  jacocoMergedReport \
  jacocoFullMergedReport \
  jacocoCoreCoverageVerification \
  jacocoFullCoverageVerification \
  --no-daemon
```

| 항목 | 결과 |
| --- | --- |
| 단위 테스트 | 518개 통과 |
| 통합 테스트 | 86개 중 79개 통과, 조건부 테스트 7개 제외 |
| JWT 필터 거부 조건 | Refresh, type 누락, role 누락, GUEST, 미등록 role, 빈 subject |
| core-v1 JaCoCo | Line 81.20%, Branch 59.58%, Class 91.37% |
| full-v1 JaCoCo | Line 64.63%, Branch 46.08%, Class 75.76% |

직전 단계와 비교해 core-v1은 Line `+0.07%p`, Branch `+0.09%p`, Class
`+0.00%p`, full-v1은 Line `+0.06%p`, Branch `+0.25%p`, Class `+0.00%p`
변했다. 정수 하한은 바뀌지 않아 JaCoCo gate는 올리지 않았다. 이 단계는 인증 경계의
정확성을 수정한 작업이므로 성능 향상률은 기록하지 않는다.

## 남은 범위

- OAuth2 성공 응답의 Access/Refresh Token 계약 정리
- 완료: [Phase 2E — 비밀번호 변경과 토큰 소유자 고정](./PHASE_2E_PASSWORD_CHANGE_SESSION_REVOCATION.md)
- `app.jwt.expiration`과 `access-token-expiration` 설정 이름 통일
- 인증 API Rate Limit 원자화
