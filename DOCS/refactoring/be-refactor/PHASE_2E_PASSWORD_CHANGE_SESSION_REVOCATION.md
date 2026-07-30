# Phase 2E — 비밀번호 변경과 토큰 소유자 고정

## 문제

비밀번호를 재설정하거나 로그인한 상태에서 비밀번호를 바꿔도 기존 Refresh Token은
Redis에 남아 있었다. 해당 토큰을 가진 사용자는 새 비밀번호를 몰라도 토큰을 회전해
새 Access Token을 발급받을 수 있었다.

Refresh Token의 소유자도 변경 가능한 BOJ ID로만 저장했다. 회원의 BOJ ID가 바뀌거나
탈퇴한 뒤 다른 회원이 같은 BOJ ID를 사용하면 과거 토큰이 새 회원을 가리킬 수 있었다.

Access Token도 BOJ ID와 권한만 서명했고 인증 필터에서 실제 학생을 확인하지 않았다.
회원 A가 BOJ ID를 바꾸거나 탈퇴한 뒤 회원 B가 이전 ID로 가입하면, A의 남은 Access
Token이 B의 인증 정보로 사용될 수 있었다.

## 변경

프로필·재설정·관리자 비밀번호 변경과 로그인의 토큰 발급 구간을 학생별로 직렬화한다.

```text
로그인
구 비밀번호 1차 확인 → solved.ac 동기화
→ 학생별 Redis 잠금 → 최신 비밀번호 재확인
→ studentId·credentialVersion을 서명한 Access/Refresh Token 발급

보호 API
Access Token 서명·만료·필수 claim 1회 검증
→ studentId로 최신 학생 조회 → BOJ ID·자격 버전·권한 비교
→ 불변 studentId를 인증 principal로 전달

코딩 로그·AI 리뷰
로그 생성 시 studentId를 소유자로 저장
→ 템플릿·피드백·AI 캐시 반환 전에 소유자 비교
→ AI 일일 사용량도 studentId로 분리

비밀번호 변경
입력·재설정 코드 비파괴 조회 → 학생별 Redis 잠금
→ 재설정 코드 원자 소비·최신 학생 재조회
→ 예상 자격 증명 버전으로 새 비밀번호 조건부 갱신
→ 기존 Refresh Token 전체 폐기 → 잠금 해제

관리자 수정·회원 탈퇴
학생별 Redis 잠금 → 최신 학생 확인 → 자격 증명 변경 또는 ID 기반 삭제
→ studentId 기준 Refresh Token 정리 → 잠금 해제
```

- 비밀번호 재설정 코드는 발급 당시 `studentId`, `credentialVersion`, BOJ ID를
  저장한다. 재설정 직전 최신 학생과 세 값이 모두 일치할 때만 코드를 원자 소비한다.
  비밀번호·권한·BOJ ID가 바뀐 뒤의 이전 코드와 스냅샷이 없는 기존 코드는 거절한다.
- 프로필 변경은 현재 비밀번호를 확인하고 새 비밀번호가 기존 비밀번호와 다른지
  검증한다.
- 잠금 충돌이나 Redis 장애가 발생하면 재설정 코드를 소비하지 않아 같은 코드로
  다시 시도할 수 있다. 잠금 안에서 코드를 소비한 뒤 발생한 실패에는 재사용할 수 없다.
- 로그인은 solved.ac 응답을 기다리는 동안 비밀번호가 바뀔 수 있으므로 토큰 발급
  직전에 최신 학생을 다시 읽고 비밀번호를 재확인한다.
- solved.ac 프로필 부분 갱신은 학생 ID와 조회 당시 BOJ ID가 모두 일치할 때만
  반영한다. 관리자 변경 뒤 늦게 도착한 이전 BOJ 응답은 새 계정의 rating과 tier를
  바꿀 수 없다.
- 닉네임이나 주 언어만 바꾸는 요청은 Refresh Token을 폐기하지 않는다.
- 관리자가 비밀번호, BOJ ID, 권한을 바꾸는 경로도 같은 학생 잠금 안에서 최신 문서를
  다시 읽고, 저장 뒤 해당 학생의 Refresh Token을 폐기한다. 권한이 실제로 바뀌면
  자격 증명 버전도 증가하므로 변경 전 Access Token을 바로 거절한다. BOJ ID 변경도
  버전을 증가시켜 ID를 이전 값으로 되돌려도 과거 인증 수단이 다시 유효해지지 않는다.
- 프로필 비밀번호 변경은 `nickname`, `password`, `primaryLanguage` 중 요청된 필드만
  `$set`한다. 풀이 기록이나 로그인 프로필 동기화 결과를 전체 문서 저장으로
  덮어쓰지 않는다.
- 비밀번호를 저장할 때 `credentialVersion`을 한 번에 증가시킨다. 조회한 버전이나
  BOJ ID가 현재 값과 다르면 오래된 요청으로 보고 409로 중단한다.
- Student 문서에는 별도의 `documentVersion`을 두고 모든 전체 저장에 낙관적 잠금을
  적용한다. 비밀번호 부분 갱신도 문서 버전을 증가시키므로, 변경 전 Student를 읽은
  Member·Template 등의 지연 전체 저장은 새 비밀번호를 과거 값으로 되돌릴 수 없다.
  Study는 [풀이 결과 부분 갱신과 충돌 재시도](./PHASE_3A_STUDY_SOLUTION_CAS.md)부터
  풀이 필드만 문서 버전 조건으로 갱신한다.
  기존 문서는 애플리케이션 시작 시 누락된 문서 버전을 0으로 채운다.
- Refresh Token에는 변경되지 않는 `studentId`와 발급 시점의 `credentialVersion`을
  서명한다. Redis token 값과 사용자별 token set도 BOJ ID가 아닌 `studentId`를
  소유자로 사용한다. 회전할 때 `studentId`로 학생을 읽고 토큰의 BOJ ID와 현재 BOJ
  ID도 비교한다.
- Access Token에도 `studentId`와 `credentialVersion`을 서명한다. 인증 필터는
  `studentId`로 현재 학생을 조회하고 BOJ ID, 자격 버전, 권한이 모두 서명 시점과
  같을 때만 인증한다. 삭제·BOJ 변경·비밀번호 변경·권한 변경 전 토큰은 즉시
  거절한다.
- 보호 API의 인증 principal은 BOJ ID가 아니라 `studentId`다. 프로필, 풀이, 회고,
  코딩 로그, 템플릿, 대시보드, 통계, 추천, 피드백 경로도 같은 ID로 소유자를 조회한다.
- `Log`는 `studentId`를 소유자로 저장하고 BOJ ID는 생성 시점 표시값으로만 남긴다.
  AI 리뷰는 로그 자체 캐시와 코드 캐시를 확인하기 전에 요청자의 `studentId`를
  비교한다. AI 일일 사용량 Redis 키도 BOJ ID가 아닌 `studentId`를 사용한다.
- Swagger HTTP Basic 인증은 Swagger 경로에만 적용한다. 일반 사용자·관리자 API는
  Basic 자격으로 접근할 수 없고 Access Token 인증을 거쳐야 한다.
- OAuth 교환 코드는 `studentId`, BOJ ID, 자격 증명 버전, 권한을 함께 저장한다.
  교환 시 최신 학생과 네 값이 모두 같을 때만 Access·Refresh Token을 발급한다.
  이전 저장 형식은 한 번 소비한 뒤 거절한다.
- Swagger 계정에는 기본값을 두지 않는다. `SWAGGER_USERNAME`,
  `SWAGGER_PASSWORD`가 없거나 비어 있으면 애플리케이션을 시작하지 않는다.
- 필터는 Access Token을 한 번만 파싱한다. 다만 유효한 Access Bearer Token을 보낸
  요청은 필터에서 최신 학생을 확인하는 MongoDB 조회 1회가 추가된다. 보안 경계
  변경에 따른 비용이며 지연 시간과 처리량은 별도 측정 전까지 개선 수치로 기록하지
  않는다.
- `studentId` claim이 없는 이전 Refresh Token은 거절한다. 이전 Redis 키는 만료
  시각까지 남을 수 있지만 새 토큰 회전 경로에서는 사용하지 않는다.
- `studentId`나 `credentialVersion` claim이 없는 이전 Access Token도 거절한다.
- Redis에 토큰이 남아 있어도 학생의 최신 자격 증명 버전과 다르면 회전할 수 없다.
  버전 필드가 없는 기존 Student 문서는 0으로 읽는다.
- Redis 토큰 폐기에 실패해도 이미 증가한 자격 증명 버전이 기존 토큰을 거절한다.
  요청 오류는 그대로 반환하며, 재설정 코드는 이미 소비됐으므로 새 코드를 발급받아야
  한다.
- 재설정 코드 소비 뒤 자격 증명 CAS가 충돌하면 `PASSWORD_RESET_CONFLICT`를 반환한다.
  이 오류는 같은 코드로 재시도할 수 없으며 새 코드 발급이 필요하다.

Refresh Token 전체 폐기는
[`RefreshTokenService.revokeAllForStudent`](../../../src/main/kotlin/com/didimlog/application/auth/RefreshTokenService.kt)을
사용한다. Redis Lua가 `studentId`별 token set에 포함된 token key와 set key를 한
번에 삭제한다.

학생별 잠금은 30초 TTL과 요청별 소유자 값을 사용하며 작업 중에는 10초마다 TTL을
갱신한다. 잠금 갱신과 해제는 Lua에서 현재 소유자를 확인한 뒤 수행하므로, 다른 요청이
획득한 잠금을 이전 요청이 연장하거나 지우지 않는다. 같은 학생의 처리가 이미 진행
중이면 409, Redis를 사용할 수 없으면 503으로 응답한다. 작업이 끝난 직후에도
소유권을 동기로 다시 확인하므로 임대가 유실된 로그인 결과는 반환하지 않는다.

잠금은 일반적인 동시 요청을 빠르게 직렬화하지만 유일한 정합성 장치는 아니다. 잠금
갱신이 장시간 실패해 임대가 끝나더라도 MongoDB 자격 증명 버전 CAS가 오래된 비밀번호
저장을 거절하고, 구 버전 Refresh Token은 회전 단계에서 거절한다.

## 검증

MongoDB 7.0.16과 Redis 7.2.5를 분리된 로컬 컨테이너로 실행했다.

- 비밀번호 재설정 전에 같은 사용자에게 Refresh Token 2개 발급
- 재설정 뒤 두 토큰의 회전 모두 거절
- 인증된 프로필 비밀번호 변경 전에도 Refresh Token 2개 발급
- 변경 뒤 두 토큰의 회전 모두 거절
- 구 비밀번호 로그인을 solved.ac 조회에서 멈춘 뒤 비밀번호 재설정
- 지연 로그인을 재개해 비밀번호 재확인 실패와 새 Refresh Token 0개 확인
- 실제 Redis에서 첫 요청이 잠금을 보유한 동안 두 번째 요청 409, 해제 뒤 재획득 확인
- 잠금 TTL 갱신, Redis 연결 실패 시 503, 작업 실패 시 소유 잠금 해제
- 일반 변경 작업 완료 뒤 잠금 키가 사라져도 이미 반영한 결과 유지
- 로그인 발급 경로에서 작업 중 잠금 키 삭제·소유자 교체 뒤 결과 반환 409
- 잠금 409·Redis 503에서는 재설정 코드 보존
- 재설정 코드의 소유 학생이 바뀐 경합에서 다른 학생 코드 보존
- 재설정 코드 발급 뒤 비밀번호·권한·BOJ ID가 바뀌면 이전 코드 거절
- 버전·BOJ ID 스냅샷이 없는 기존 재설정 코드 거절
- 비밀번호 인코딩 중 BOJ ID가 바뀌면 최종 CAS에서 갱신 거절
- 실제 MongoDB에서 레거시 버전 0 호환, stale CAS 무변경, 성공 시 버전 1회 증가
- 비밀번호 부분 갱신 뒤 과거 Student 전체 저장 거절, 새 비밀번호·버전 유지
- 누락된 Student 문서 버전 backfill 뒤 전체 저장 성공
- Redis에 남은 구 버전 Refresh Token도 회전 거절
- 과거 회원 토큰의 BOJ ID를 다른 회원이 사용해도 학생 ID 불일치로 회전 거절
- `studentId` claim이 없는 이전 Refresh Token 거절
- BOJ ID 변경·학생 삭제 뒤 같은 BOJ ID로 다른 학생이 가입해도 기존 Access Token 401
- 비밀번호 버전과 권한이 바뀐 뒤 기존 Access Token 401
- 관리자 권한 단독 변경 뒤 자격 증명 버전 증가, 기존 Access·Refresh Token 거절
- 관리자 BOJ ID를 변경했다가 되돌려도 버전은 계속 증가하고 변경 전 Access Token 거절
- OAuth 교환 코드 발급 뒤 비밀번호·권한·BOJ ID가 바뀌면 교환 거절
- 이전 Redis 형식과 손상된 OAuth 교환 값은 소비 뒤 재사용 불가
- 실제 JWT 필터부터 대표 보호 API까지 불변 studentId principal 전달 확인
- 같은 BOJ ID를 새 학생이 사용해도 과거 로그 템플릿·피드백 접근 403
- 불변 소유자가 없는 기존 로그는 같은 BOJ ID의 현재 학생에게 자동 연결하지 않음
- 다른 학생의 로그 자체 캐시·코드 캐시 AI 리뷰를 잠금 전에 403
- Swagger Basic은 Swagger 경로에서만 허용하고 대표 사용자 API에서는 401
- BOJ ID 변경 뒤 늦게 도착한 solved.ac 응답의 rating·tier·문서 버전 무변경
- 관리자 비밀번호 변경과 로그인 경합 직렬화, 관리자 변경 뒤 기존 토큰 거절
- Student 문서 버전이 바뀐 탈퇴도 ID 기반 삭제로 완료
- 낙관적 잠금 충돌을 재시도 가능한 `RESOURCE_STATE_CONFLICT` 409로 반환
- 잘못된 코드, 만료 코드, 학생 없음, 현재 비밀번호 불일치에서는 폐기 미실행

전체 `clean check` 결과는 다음과 같다.

| 항목 | 결과 |
| --- | --- |
| 단위 테스트 | 634개 통과 |
| 통합 테스트 | 136개 중 129개 통과, Gemini·crawler baseline 7개 조건부 제외 |
| core-v1 | Line 83.07%, Branch 61.64%, Class 92.42% |
| full-v1 | Line 68.41%, Branch 50.52%, Class 77.62% |

두 JaCoCo 하한은 모두 통과했다. 커버리지 값은 변경 경로의 회귀 검증 범위이며
별도 개선 성과로 해석하지 않는다.

이 단계는 세션 보안 경계를 수정한 작업이다. 지연 시간이나 처리량을 전후 반복
측정하지 않았으므로 성능 향상률은 기록하지 않는다.

## 남은 범위

- 기존 `Log` 문서에는 `studentId`가 없다. 현재 학생 정보와 작성 시각만으로는 과거
  BOJ ID 변경·재사용 이력을 증명할 수 없으므로 자동 이관하지 않는다. 사용자
  경로에서는 접근을 거부하며, 보존이 필요한 자료는 배포 전에 신뢰 가능한 별도 회원
  매핑으로 `studentId`를 채워야 한다.
- 탈퇴와 회고·피드백 저장이 동시에 실행되면 연관 데이터를 지운 뒤 늦은 저장이
  고아 문서를 만들 수 있다. 관리자 강제 탈퇴의 연관 데이터 정책과 함께 다음
  정합성 단계에서 다룬다.
- 관리자 BOJ ID 변경 시 `providerId`와 solved.ac rating·tier를 어떻게 처리할지
  정책을 정해야 한다.
- 관리자·수집 작업 감사 필드는 배포 뒤부터 불변 studentId를 저장한다. 이전 BOJ ID
  값과 같은 열에 섞이는 전환 시점을 운영 문서에 남겨야 한다.
- Redis와 MongoDB를 함께 묶는 분산 트랜잭션은 사용하지 않는다. MongoDB에서 자격
  증명 버전을 먼저 증가시켜 기존 토큰을 논리적으로 만료시킨 뒤 Redis 데이터를
  정리한다.
- `credentialVersion`과 `documentVersion`을 모르는 이전 바이너리와 새 바이너리를
  동시에 실행하지 않는다. 이전 인스턴스를 내린 뒤 새 버전의 문서 backfill을
  완료하는 단일 버전 배포가 필요하다.
