# Phase 2A — 가입·회고 데이터 정합성

## 문제

가입과 회고 저장은 먼저 중복을 조회한 뒤 문서를 저장했다. 동시에 같은 값으로
요청하면 두 요청이 모두 중복 조회를 통과할 수 있으므로 애플리케이션 검사만으로는
유일성을 보장할 수 없었다.

엔티티에 인덱스 어노테이션이 있었지만 자동 생성은 꺼져 있었다. 시작 시
`MongoIndexInitializer`도 관리자 조회용 인덱스 두 개만 만들었기 때문에 다음
조건은 실제 MongoDB 계약이 아니었다.

- 로그인 제공자와 제공자 사용자 ID
- BOJ ID
- 이메일
- 닉네임
- 학생과 문제별 회고
- 비밀번호 재설정 코드와 만료 시각

## 변경

애플리케이션 시작 시 다음 인덱스를 명시적으로 확인하고, 없을 때만 생성한다.

| 컬렉션 | 키 | 옵션 |
| --- | --- | --- |
| `students` | `provider ASC, providerId ASC` | unique |
| `students` | `nickname ASC` | unique |
| `students` | `bojId ASC` | string 필드만 적용하는 partial unique |
| `students` | `email ASC` | string 필드만 적용하는 partial unique |
| `retrospectives` | `studentId ASC, problemId ASC` | unique |
| `password_reset_codes` | `resetCode ASC` | unique |
| `password_reset_codes` | `expiresAt ASC` | `expireAfter=0` |

`email`과 `bojId`의 partial 인덱스는 값이 없는 소셜 계정을 여러 건 저장할 수
있게 한다. 기존 데이터가 중복이거나 같은 이름의 인덱스 옵션이 다르면 데이터를
삭제하거나 합치지 않고 시작을 중단한다.

BOJ 인증 세션을 소비한 일반 가입은 `Student.isVerified=true`로 저장한다.
회고 보관 기간 환경 변수는 서비스가 읽는 `app.retrospective.retention-days`
경로로 옮겼다.

## 검증

```bash
./gradlew clean check --no-daemon
```

| 항목 | 결과 |
| --- | --- |
| 단위 테스트 | 500개 통과 |
| 통합 테스트 | 64개 중 57개 통과, 외부 연동·성능 테스트 7개 제외 |
| 새 MongoDB 계약 테스트 | 9개 통과 |
| core-v1 JaCoCo | Line 80.49%, Branch 58.87%, Class 91.28% |
| full-v1 JaCoCo | Line 63.69%, Branch 45.14%, Class 75.65% |

전용 통합 테스트는 매번 별도 데이터베이스를 만들고 다음을 확인한다.

- 학생 식별자 네 종류의 중복 저장 거부
- `email`, `bojId`가 없는 학생 여러 건 저장
- 같은 학생·문제의 회고를 두 스레드에서 동시에 insert해 최종 한 건 저장
- 중복 회고가 이미 있으면 두 문서를 그대로 보존하고 인덱스 초기화 실패
- 비밀번호 재설정 코드의 unique·TTL 인덱스 메타데이터

## 남은 범위

- 이메일은 현재 저장 문자열 기준으로 비교한다. 대소문자와 앞뒤 공백까지 같은
  계정으로 취급하려면 별도 정규화와 기존 데이터 변환이 필요하다.
- TTL 삭제는 MongoDB 백그라운드 작업이므로 만료 시각 직후 삭제를 보장하지 않는다.
  비밀번호 변경 시 만료 검사도 계속 수행한다.
- 인덱스 생성은 순차 DDL이다. 뒤 인덱스에서 실패하면 앞서 만든 인덱스는
  자동으로 되돌아가지 않으므로 운영 반영 전 전체 중복·옵션 점검이 필요하다.
- Refresh Token은 [Phase 2B](./PHASE_2B_REFRESH_TOKEN_ATOMIC_ROTATION.md)에서
  원자적으로 교체한다. 비밀번호 재설정 코드의 일회성 소비는 다음 단계에 남아 있다.
- 운영 데이터와 EC2의 중복 데이터·인덱스 권한은 이번 로컬 검증 범위에서 제외했다.
