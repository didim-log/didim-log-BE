# JWT 토큰 용도 분리

Access Token과 Refresh Token을 JWT claim으로 구분하고, 인증 필터가 Access Token만 받도록 바꿨습니다. Refresh Token으로 보호 API를 호출할 수 없습니다.

## 이전과 현재

| 상황 | 이전 | 현재 |
| --- | --- | --- |
| Refresh Token으로 `/api/v1/feedback` 호출 | 201 (요청 성공) | 401 `UNAUTHORIZED` |
| Redis에서 폐기한 Refresh Token으로 보호 API 호출 | JWT 만료 전까지 통과 | 401 |
| role 없는 토큰 | `USER`로 간주 | 401 |
| `/api/v1/auth/refresh`의 Refresh Token 갱신 | 200 | 200 |

## 문제

인증 필터는 JWT 서명과 만료만 확인했습니다. Refresh Token에는 `role`이 없는데 필터가 이를 `USER`로 바꿔 인증 정보를 만들었고, Redis 저장 여부도 보지 않아서 폐기한 Refresh Token도 만료 전까지 보호 API에 쓸 수 있었습니다. 실제 Refresh Token으로 `/api/v1/feedback`을 호출한 재현 테스트는 401이 아니라 201을 받았습니다.

## 바꾼 내용

| 토큰 | `type` | `role` | 쓰는 곳 |
| --- | --- | --- | --- |
| Access Token | `access` | `USER` 또는 `ADMIN` | 보호 API |
| Refresh Token | `refresh` | 없음 | `/api/v1/auth/refresh` |

인증 필터는 서명·만료가 유효하고, `type=access`이고, subject가 비어 있지 않고, role이 `USER`나 `ADMIN`일 때만 인증 정보를 만듭니다. 그 밖의 토큰은 인증 없이 다음 필터로 넘겨서 보호 API에서 401이 됩니다. 쓰이지 않던 role 없는 Access Token 생성 메서드는 지웠고, k6와 로컬 점검 스크립트가 만드는 JWT에도 `type=access`를 넣었습니다.

기존에 발급된 Access Token에는 `type`이 없어서 배포 뒤 거절됩니다. 사용자는 저장된 Refresh Token으로 새 Access Token을 받습니다. 구형 토큰을 임시로 받아 주는 예외는 두지 않았습니다.

## 확인한 방법

- 변경 전 재현 테스트가 201로 실패했고, 변경 뒤 같은 요청이 401을 받습니다. Refresh Token 갱신 경로는 200을 유지합니다.
- 필터 단위 테스트가 Refresh, type 없음, role 없음, GUEST, 등록되지 않은 role, 빈 subject를 모두 거절하는지 확인합니다.
- `./gradlew clean check`가 통과했습니다(단위 518개, 통합 86개 중 79개 통과·조건부 7개 제외).

```bash
./gradlew test --tests '*JwtTokenProviderTest' --tests '*JwtAuthenticationFilterTest'
./gradlew integrationTest --tests '*SecurityIntegrationTest' --tests '*JwtTokenProviderIntegrationTest'
```

## 남은 한계

- `app.jwt.expiration`과 `access-token-expiration` 설정 이름이 아직 둘로 나뉘어 있습니다.
