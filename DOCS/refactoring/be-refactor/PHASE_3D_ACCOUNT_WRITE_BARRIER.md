# Phase 3D — 계정 삭제와 사용자 데이터 쓰기 경합 차단

## 문제

Phase 3C에서 본인 탈퇴와 관리자 삭제가 같은 정리 경로를 사용하도록 바꿨지만,
삭제 전에 시작한 요청이 삭제 뒤 늦게 저장되는 경우는 남아 있었다.

- 신규 회고는 `upsert=true`라 Student가 삭제된 뒤 고아 회고를 만들 수 있었다.
- 로그, 피드백과 커스텀 템플릿은 버전 없는 문서를 `save`하므로 삭제된 문서를
  다시 만들 수 있었다.
- OAuth 코드 교환이나 회원가입의 토큰 발급이 계정 삭제 뒤 끝나면 사용할 수 없는
  Refresh Token이 Redis에 남을 수 있었다.
- 로그의 AI 피드백은 Log 전체를 다시 저장해 비동기 AI 리뷰가 부분 갱신한 필드를
  오래된 값으로 덮을 수 있었다.

## 경계

기존 `CredentialSessionCoordinator`가 사용하는 학생별 Redis 잠금과 키를 그대로
사용한다. 공통 부모인 `StudentLifecycleCoordinator`를 추가했지만 Redis 구현과
`credential:session:lock:{studentId}` 키는 하나뿐이다. 인증, 계정 삭제와 사용자
데이터 쓰기가 서로 다른 잠금을 사용하지 않게 하기 위한 선택이다.

잠금은 기다리지 않는다. 같은 학생의 작업이 이미 실행 중이면
`409 SESSION_STATE_CONFLICT`로 거절하고 요청자가 잠시 뒤 다시 시도한다.

```text
학생별 잠금 획득
→ 잠금 안에서 Student 최신 조회
→ 변경할 문서 최신 조회와 소유권 확인
→ 짧은 MongoDB 또는 Redis 쓰기
→ 잠금 해제
```

Repository나 내부 토큰 저장 함수에서는 잠금을 다시 잡지 않는다. 현재 잠금은
재진입을 지원하지 않으므로 공개 application write 경계에서 한 번만 획득한다.

## 적용 범위

| 영역 | 잠근 쓰기 | 삭제 뒤 부활을 막는 조건 |
| --- | --- | --- |
| 계정 | 본인·관리자 계정 삭제 | 같은 학생 키로 하위 데이터와 Student를 정리 |
| 로그 | 생성, AI 리뷰 피드백 | Student 재조회, `_id + studentId` 조건 부분 갱신 |
| 고객 피드백 | 생성, 상태 변경, 삭제 | 작성자 잠금 안에서 Student와 Feedback 재조회 |
| 템플릿 | 생성, 수정, 삭제, 기본값 설정 | Student와 Template 재조회, 기본값은 Student 부분 갱신 |
| 회고 | 작성, 수정, 북마크, 삭제 | Student 확인부터 upsert·CAS·삭제까지 한 잠금 안에서 실행 |
| 인증 | Refresh Token 회전, OAuth 코드 교환 | 최신 Student 확인 뒤 토큰 교체·발급 |
| 가입 | BOJ 가입, 관리자 생성, 소셜 가입 완료의 토큰 발급 | 저장된 Student ID로 다시 잠근 뒤 최신 정보로 발급 |

`RefreshTokenService.generateAndSave`와 `revokeAllForStudent`는 로그인, 가입, 교환,
비밀번호 변경과 계정 삭제의 잠금 안에서도 호출된다. 두 내부 함수에는 별도 잠금을
추가하지 않았다.

## 부분 갱신

### 로그 AI 피드백

기존 구현은 Log를 읽은 뒤 `save(updatedLog)`로 문서 전체를 교체했다. 이제
MongoDB `findAndModify`에서 다음 조건과 필드만 사용한다.

```text
조건: _id = logId AND studentId = requesterStudentId
갱신: aiFeedbackStatus, aiFeedbackReason
옵션: returnNew=true, upsert=false
```

계정 삭제나 보관기간 정리가 먼저 문서를 지우면 갱신 결과가 없으므로 로그를 다시
만들지 않는다. AI 리뷰 본문, 생성 상태, 소요 시간과 prompt version도 그대로
보존한다.

### 기본 템플릿

기본 템플릿 설정은 Student 전체 저장 대신 선택한
`defaultSuccessTemplateId` 또는 `defaultFailTemplateId`만 바꾸고
`documentVersion`을 증가시킨다. 쿼리는 `_id`가 있는 Student만 갱신하고
`upsert=false`를 사용한다.

이 방식은 계정 삭제 뒤 Student를 다시 만들지 않으며, 동시에 진행된 풀이 저장이
기본 템플릿 값을 덮지 않게 한다. 풀이 저장의 CAS가 먼저 성공하면 기본값 갱신은
최신 문서의 해당 필드만 바꾸고, 기본값 갱신이 먼저 성공하면 풀이 저장은 새 버전으로
재시도한다.

## 잠그지 않은 경로

모든 쓰기를 Redis 잠금으로 직렬화하지 않았다. 다음 경로는 삭제 뒤 문서를 만들지
않는 조건부 갱신이거나 `@Version`으로 보호되는 Student 전체 저장이다.

| 경로 | 근거 |
| --- | --- |
| 풀이 결과 저장 | `_id + documentVersion`, `upsert=false` CAS와 재시도 |
| solved.ac 프로필 동기화 | Student `_id + BOJ ID`, `upsert=false` 부분 갱신 |
| 비밀번호·프로필 부분 갱신 | Student ID와 자격 증명 버전 조건, `upsert=false` |
| `@Version`이 있는 Student 전체 저장 | 삭제된 문서의 stale 저장은 낙관적 잠금 오류 |
| 조회 API와 정리 작업 | 새 사용자 소유 문서를 만들지 않음 |

특히 풀이 결과 저장까지 현재 Redis 잠금으로 감싸면 같은 학생의 정상적인 동시
제출 중 한 요청이 즉시 `409`가 된다. 기존 MongoDB CAS가 두 요청의 결과를 합치는
동시성은 유지했다.

## API 계약

잠금을 사용하는 쓰기와 토큰 API는 다음 응답을 문서화한다.

| 상황 | 응답 |
| --- | --- |
| 잠금 안에서 Student가 없음 | `404 STUDENT_NOT_FOUND` |
| 같은 학생의 계정 상태 변경 또는 쓰기와 충돌 | `409 SESSION_STATE_CONFLICT` |
| Redis 잠금 저장소를 사용할 수 없음 | `503 SESSION_STATE_UNAVAILABLE` |

`SESSION_STATE_CONFLICT`는 재시도 가능한 오류다. 한 요청이 끝난 뒤 최신 상태에서
다시 실행해야 한다.

## 검증

단위 테스트에서 다음을 확인했다.

- 로그·피드백·템플릿·회고 쓰기가 Student 재조회 뒤에만 Repository를 변경
- 잠금 획득 충돌 또는 작업 진입 전 Student 부재 시 저장과 토큰 발급 0건
- 로그 피드백이 전체 `save`를 사용하지 않고 소유자 조건 부분 갱신만 실행
- Refresh Token 회전과 OAuth 교환이 strict 잠금 안에서 최신 Student를 사용
- 일반 가입, 관리자 생성과 소셜 가입 완료가 저장된 Student를 다시 읽어 토큰 발급
- 회고 작성·수정·북마크·삭제가 공개 경계에서 잠금을 한 번만 획득

MongoDB 7.0.16과 Redis 7.2.5를 별도 로컬 컨테이너로 실행해 다음 경합을 확인했다.

- 템플릿 생성이 잠금을 가진 동안 계정 삭제는 `409`
- 템플릿 생성 완료 뒤 계정 삭제를 재시도하면 Student와 템플릿 모두 삭제
- 계정 삭제가 하위 데이터 정리 중일 때 신규 회고 작성은 `409`
- 삭제 완료 뒤 회고 작성을 재시도하면 `404`, 고아 회고 0건
- lifecycle 타입과 credential 타입이 실제 Redis에서 같은 키로 충돌
- 로그 피드백 부분 갱신 뒤 기존 AI 리뷰 필드 보존
- 기본 템플릿 부분 갱신 뒤 다른 Student 필드 보존과 삭제 문서 비부활

전체 단위 테스트 665개가 통과했다. 통합 테스트는 151개 중 144개가 통과했고,
외부 Gemini·crawler 조건이 필요한 7개는 조건부 제외됐다. core-v1과 full-v1
JaCoCo 하한도 통과했다.

| 범위 | Phase 3C | Phase 3D | 차이 |
| --- | ---: | ---: | ---: |
| 단위 테스트 | 653 | 665 | +12 |
| 통합 테스트 | 146 | 151 | +5 |
| core-v1 Line | 83.94% | 85.08% | +1.14%p |
| full-v1 Line | 69.13% | 69.84% | +0.71%p |

프로덕션 코드 분모도 함께 바뀌었으므로 커버리지 차이를 새 테스트만의 효과로
해석하지 않는다. Branch와 Class를 포함한 최종 스냅샷은
[관리자 회원 조회 최적화 근거의 역사적 테스트·JaCoCo 기록](./ADMIN_QUERY_OPTIMIZATION_OVERVIEW.md#역사적-테스트jacoco-기록)에
중간 단계별 표를 늘리지 않고 Phase 3D 이정표로 추가했다.

이 단계는 경합 결과와 데이터 부활 여부를 검증한 작업이다. 반복 지연 시간이나
처리량을 측정하지 않았으므로 성능 향상률을 기록하지 않는다.

## 남은 제한

- Redis 잠금은 30초 lease를 갱신한다. 프로세스 중단, 장시간 네트워크 단절처럼
  잠금 소유권을 잃는 상황까지 강하게 막으려면 tombstone 또는 fencing token이
  필요하다.
- 토큰 저장·회전 뒤 완료 확인에서 잠금 소유권 상실을 발견하면 요청은 실패하지만
  이미 저장한 Refresh Token은 TTL 동안 남을 수 있다. 이 경우까지 원자화하려면
  잠금 소유자 확인과 토큰 쓰기를 같은 Redis Lua 연산으로 묶어야 한다.
- MongoDB 여러 컬렉션과 Redis는 하나의 트랜잭션이 아니다. 잠금은 정상 Redis
  상태에서 실행 순서를 정하지만 이미 반영된 쓰기를 롤백하지 않는다.
- OAuth 교환 코드는 Student ID를 알기 위해 먼저 소비한다. 이후 잠금 충돌이 나면
  OAuth 로그인을 다시 시작해야 한다.
- 신규 Student 저장 직후 토큰 발급 잠금이 충돌하면 토큰은 만들지 않지만 현재
  MongoDB 구성에서는 앞서 저장한 Student가 남을 수 있다.
- 향후 `MongoTransactionManager`를 추가하면 트랜잭션 commit이 잠금 해제 뒤
  일어나지 않도록 잠금 바깥 서비스와 트랜잭션 내부 서비스를 분리해야 한다.
