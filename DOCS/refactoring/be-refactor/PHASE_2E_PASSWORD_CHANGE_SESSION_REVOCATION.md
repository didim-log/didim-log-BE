# 비밀번호 변경과 토큰 소유자 고정

비밀번호를 바꾸면 기존 로그인 세션을 모두 끊고, 토큰 주인을 바뀔 수 있는 BOJ ID 대신 바뀌지 않는 학생 ID로 고정했습니다. 다른 회원이 예전 BOJ ID를 쓰더라도 이전 회원의 토큰이 새 회원을 가리키지 않습니다.

## 이전과 현재

| 상황 | 이전 | 현재 |
| --- | --- | --- |
| 비밀번호 재설정·변경 뒤 기존 Refresh Token | 회전 가능, 새 비밀번호 없이 Access Token 발급 | 모두 거절 |
| 비밀번호·권한·BOJ ID 변경 전 Access Token | 만료 전까지 통과 | 즉시 401 |
| A가 BOJ ID를 바꾼 뒤 B가 같은 ID로 가입 | A의 토큰이 B로 인증됨 | A의 토큰 401·회전 거절 |
| 같은 BOJ ID 새 회원의 과거 로그 접근 | 접근 가능 | 403 |
| 로그인이 solved.ac를 기다리는 사이 비밀번호 재설정 | 구 비밀번호로 토큰 발급 | 재확인 실패, 새 Refresh Token 0개 |
| 닉네임·주 언어만 변경 | - | 토큰 유지 |
| Swagger Basic 계정으로 사용자 API 호출 | 가능 | 401 |

로컬 MongoDB 7.0.16과 Redis 7.2.5에서 확인했습니다. 보안 경계를 바꾼 작업이라 응답 시간은 측정하지 않았습니다.

## 문제

- 비밀번호를 재설정하거나 바꿔도 Redis의 Refresh Token은 남아 있어, 토큰을 가진 사람은 새 비밀번호 없이 계속 로그인 상태를 유지했습니다.
- Refresh Token 주인은 바뀔 수 있는 BOJ ID로만 저장했습니다. 회원이 BOJ ID를 바꾸거나 탈퇴한 뒤 다른 회원이 같은 ID를 쓰면 과거 토큰이 새 회원을 가리켰습니다.
- Access Token도 BOJ ID와 권한만 서명했고, 인증 필터는 실제 학생을 확인하지 않았습니다.

## 바꾼 내용

두 토큰에 바뀌지 않는 `studentId`와 발급 시점의 `credentialVersion`을 서명하고, 비밀번호·권한·BOJ ID가 바뀔 때마다 `credentialVersion`을 올립니다.

```text
보호 API
Access Token 검증 → studentId로 최신 학생 조회 → BOJ ID·자격 버전·권한 비교 → studentId를 principal로 전달

비밀번호 변경
학생별 Redis 잠금 → 재설정 코드 원자 소비·최신 학생 재조회
→ 예상 자격 버전 조건으로 비밀번호 갱신 → 그 학생의 Refresh Token 전체 폐기 → 잠금 해제
```

| 경계 | 처리 |
| --- | --- |
| 인증 principal | BOJ ID 대신 `studentId`, 프로필·풀이·회고·로그·통계 등 모든 보호 API에 적용 |
| Refresh Token 저장 | Redis 값과 사용자별 Set 모두 `studentId` 기준 |
| 전체 폐기 | `RefreshTokenService.revokeAllForStudent`, Lua로 한 번에 삭제 |
| 학생별 잠금 | 30초 TTL, 10초마다 갱신, 충돌 409, Redis 장애 503 |
| 오래된 요청 | 조회 때의 자격 버전·BOJ ID가 다르면 409 |
| Student 전체 저장 | `documentVersion` 낙관적 잠금, 부분 갱신도 버전 증가 |
| 재설정 코드 | 발급 시점 `studentId`·`credentialVersion`·BOJ ID를 저장하고 셋이 같을 때만 소비 |
| OAuth 교환 코드 | 학생 ID·BOJ ID·자격 버전·권한이 그대로일 때만 토큰 발급 |
| 코딩 로그·AI 리뷰 | 로그 주인을 `studentId`로 저장하고, AI 일일 사용량도 `studentId`로 분리 |
| Swagger Basic | Swagger 경로에만 적용, 계정 환경변수가 없으면 시작 실패 |

잠금은 동시 요청을 줄이는 장치이고, 최종 정합성은 MongoDB의 자격 버전 조건 갱신이 맡습니다. 잠금 임대가 끊겨도 오래된 비밀번호 저장은 거절됩니다. Redis 폐기가 실패해도 이미 올라간 자격 버전이 기존 토큰을 막습니다.

유효한 Access Token 요청마다 최신 학생을 확인하는 MongoDB 조회가 1회 늘어납니다.

## 확인한 방법

- 재설정과 프로필 비밀번호 변경 전에 Refresh Token 2개를 발급하고, 변경 뒤 두 토큰의 회전이 모두 거절되는지 확인합니다.
- 구 비밀번호 로그인을 solved.ac 조회에서 멈춘 뒤 비밀번호를 재설정하고, 로그인을 재개해 토큰이 0개인지 확인합니다.
- BOJ ID 변경·탈퇴 뒤 같은 ID로 다른 학생이 가입해도 기존 Access Token이 401이고 과거 로그 접근이 403인지 확인합니다.
- 실제 Redis에서 잠금 보유 중 두 번째 요청 409, Redis 장애 503, 409·503일 때 재설정 코드 보존을 확인합니다.
- 비밀번호 부분 갱신 뒤 예전 Student 전체 저장이 거절되고 새 비밀번호가 남는지 확인합니다.
- `./gradlew clean check`가 통과했습니다(단위 634개, 통합 136개 중 129개 통과·조건부 7개 제외).

## 남은 한계

- 기존 `Log` 문서에는 `studentId`가 없습니다. 과거 BOJ ID 변경 이력을 증명할 수 없어 자동 이관하지 않고, 보존이 필요하면 배포 전에 별도 회원 매핑으로 채워야 합니다.
- 탈퇴와 회고·피드백 저장이 겹치면 고아 문서가 생길 수 있습니다.
- 관리자가 BOJ ID를 바꿀 때 `providerId`와 rating·tier 처리 정책은 정하지 않았습니다.
- Redis와 MongoDB를 묶는 분산 트랜잭션은 쓰지 않습니다.
- `credentialVersion`과 `documentVersion`을 모르는 이전 버전과 함께 실행할 수 없어, 이전 인스턴스를 내린 뒤 배포해야 합니다.
