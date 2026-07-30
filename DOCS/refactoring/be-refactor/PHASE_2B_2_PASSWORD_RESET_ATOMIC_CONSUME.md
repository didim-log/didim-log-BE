# Phase 2B-2 — 비밀번호 재설정 코드 원자적 소비

## 문제

기존 비밀번호 재설정은 다음 순서로 처리됐다.

```text
재설정 코드 조회
→ 회원 전체 문서 조회
→ 새 비밀번호로 회원 전체 문서 저장
→ 재설정 코드 삭제
```

같은 코드로 요청이 동시에 들어오면 여러 요청이 코드 조회를 통과할 수 있었다. 각
요청은 비밀번호를 저장한 뒤 코드를 삭제하므로, 한 번만 사용할 수 있어야 하는 코드로
비밀번호가 여러 번 변경될 수 있었다.

비밀번호만 바꾸는 작업에서도 조회한 `Student` 전체를 다시 저장했다. 그 사이 다른
요청이 프로필이나 풀이 기록을 변경하면 이전에 읽은 문서가 나중 변경을 덮어쓸 수
있었다.

## 변경

비밀번호 정책을 먼저 검사한 뒤 MongoDB의 `findAndRemove`로 재설정 코드를 조회와
동시에 삭제한다. 같은 코드에 대한 여러 요청 중 MongoDB에서 문서를 받은 요청만 다음
단계로 진행한다.

```text
새 비밀번호 정책 검사
→ 재설정 코드 원자적 소비
→ 만료 여부 검사
→ 비밀번호 인코딩
→ Student의 password 필드만 갱신
```

비밀번호 갱신은 `_id` 조건의 `updateFirst`와 `$set`을 사용한다. `matchedCount`로 회원
존재 여부를 확인하므로, 같은 암호화 문자열이 저장돼 `modifiedCount`가 0이어도 회원이
없는 것으로 잘못 판단하지 않는다.

재설정 코드 소비와 비밀번호 갱신은 서로 다른 MongoDB 문서에 대한 명령이다. 현재
standalone MongoDB 구성에서는 이를 하나의 트랜잭션으로 묶지 않는다. 코드 소비 뒤
회원 조회나 비밀번호 갱신이 실패하면 코드를 복원하지 않는 방식으로 재사용을 막는다.

실제 발급 경로에서 사용되지 않던 Redis 재설정 코드 저장소는 제거했다. 재설정 코드는
MongoDB의 `resetCode` 유일 인덱스와 `expiresAt` TTL 인덱스를 사용하며, API 설명도 실제
저장 방식에 맞췄다.

## 실패 경계

| 상황 | 코드 상태 | 비밀번호 |
| --- | --- | --- |
| 새 비밀번호 정책 위반 | 유지 | 변경 없음 |
| 코드 없음 | 없음 | 변경 없음 |
| 만료 코드 | 소비 | 변경 없음 |
| 코드의 회원 없음 | 소비 | 변경 없음 |
| 소비 이후 인코딩·DB 갱신 실패 | 소비 | 성공을 보장하지 않음 |
| 정상 요청 | 소비 | `password` 필드만 변경 |

만료 코드도 소비하기 때문에 MongoDB TTL 정리 주기를 기다리지 않고 즉시 다시 사용할 수
없다. 기존의 유효하지 않은 코드와 만료 코드 오류 메시지는 유지했다.

## 동시 요청 결과

격리한 MongoDB 7.0.16에 학생 한 명과 재설정 코드 한 개를 저장한 뒤, 20개 스레드가
같은 코드와 새 비밀번호로 동시에 재설정을 요청했다.

| 확인 항목 | 결과 |
| --- | --- |
| 재설정 성공 | 20건 중 1건 |
| 재설정 실패 | 20건 중 19건 |
| 실패 사유 | 이미 소비된 코드 |
| 최종 재설정 코드 | 0건 |
| 비밀번호 인코딩 | 1회 |
| 최종 비밀번호 | 새 비밀번호로 변경 |
| 닉네임·rating | 기존 값 유지 |

20건은 처리량 측정이 아니라 일회성 코드의 동시성 계약을 확인하기 위한 조건이다.

추가로 만료 코드는 소비되지만 비밀번호가 바뀌지 않는지, 정책에 맞지 않는 비밀번호는
코드를 소비하지 않는지, 회원이 없는 코드와 인코딩에 실패한 코드가 소비 후 복원되지
않는지 확인했다. 비밀번호 인코딩을 중단시킨 동안 rating을 별도로 변경한 경합
테스트에서는 최종 문서에 새 비밀번호와 새 rating이 함께 남았다.

## 검증

대상 단위 테스트와 전용 MongoDB 통합 테스트를 다음 명령으로 실행했다.

```bash
./gradlew test \
  --tests 'com.didimlog.application.auth.AuthServiceResetPasswordTest' \
  --no-daemon

TEST_MONGO_PORT=27018 \
./gradlew integrationTest \
  --tests 'com.didimlog.application.auth.PasswordResetConsistencyIntegrationTest' \
  --no-daemon

SPRING_DATA_MONGODB_URI=mongodb://localhost:27018/didimlog-test \
TEST_MONGO_PORT=27018 \
SPRING_DATA_REDIS_PORT=6380 \
TEST_REDIS_PORT=6380 \
SERVER_URL=https://dummy-server.com \
./gradlew clean check jacocoMergedReport jacocoFullMergedReport --no-daemon
```

| 항목 | 결과 |
| --- | --- |
| 단위 테스트 | 503개 통과 |
| 통합 테스트 | 74개 중 67개 통과, 외부 연동·성능 테스트 7개 제외 |
| 새 MongoDB 계약 테스트 | 6개 통과 |
| core-v1 JaCoCo | Line 80.93%, Branch 59.14%, Class 91.37% |
| full-v1 JaCoCo | Line 64.24%, Branch 45.43%, Class 75.70% |

검증된 현재 값의 정수 하한으로 JaCoCo gate를 올렸다.

| 범위 | Line | Branch | Class |
| --- | ---: | ---: | ---: |
| core-v1 | 80% | 58% | 91% |
| full-v1 | 64% | 45% | 75% |

Phase 2B-1과 비교한 full-v1 변화는 Line 0.07%p, Branch 0.15%p, Class
0.00%p다. README에 별도의 커버리지 성과로 기록할 정도의 차이가 아니므로 동시성
검증 결과만 반영했다.

## 남은 범위

- 사용자별 활성 재설정 코드를 한 개로 제한하고 새 발급이 이전 코드를 대체하는 작업
- 보안용 난수 생성기와 코드 충돌 재시도 적용
- 재설정 코드 발급 API의 요청 제한 누락과 Redis 카운터 원자화
- 완료: [Phase 2E — 비밀번호 변경과 토큰 소유자 고정](./PHASE_2E_PASSWORD_CHANGE_SESSION_REVOCATION.md)
