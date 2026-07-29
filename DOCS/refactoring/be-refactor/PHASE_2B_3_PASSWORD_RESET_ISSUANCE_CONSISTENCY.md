# Phase 2B-3 — 비밀번호 재설정 코드 발급 정합성

## 문제

기존 발급은 재설정 코드를 새 문서로 저장한 뒤 메일을 보냈다.

```text
회원 확인
→ 난수 코드 생성
→ 새 PasswordResetCode 저장
→ 메일 발송
```

같은 회원이 여러 번 요청하면 활성 코드가 여러 문서로 남을 수 있었다. 동시에 요청한
경우에도 회원별 최종 코드가 하나라는 보장이 없었다. 다른 회원의 코드와 충돌하면
`resetCode` 유일 인덱스에서 실패했고, 저장 뒤 메일 발송이 실패하면 사용자가 받지 못한
코드가 만료 시각까지 남았다.

## 변경

재설정 코드 생성은 `SecureRandom`을 사용하는 별도 생성기로 분리했다. 코드는 기존
형식과 같은 영문 대문자·숫자 8자리다.

발급 저장은 `studentId` 조건의 `findAndModify`로 바꿨다.

```text
회원 확인
→ 보안 난수 코드 생성
→ studentId 기준 findAndModify(upsert, returnNew)
→ 메일 발송
→ 발송 실패 시 studentId와 resetCode가 모두 같은 문서만 삭제
```

조회 조건은 `studentId` equality만 사용한다. `resetCode != 후보` 같은 조건을 더하지
않으므로 동시 upsert가 회원 유일 인덱스를 이용해 기존 문서로 수렴할 수 있다.
`resetCode`, `expiresAt`, `createdAt`은 한 번에 갱신하고 `studentId`는 바꾸지 않는다.

MongoDB 인덱스는 다음 세 가지를 보장한다.

| 필드 | 이름 | 조건 |
| --- | --- | --- |
| `resetCode` | `uniq_password_reset_code` | unique |
| `studentId` | `uniq_password_reset_student_id` | unique |
| `expiresAt` | `ttl_password_reset_expires_at` | TTL 0초 |

같은 옵션과 필드를 가진 기존 인덱스는 이름이 달라도 재사용한다. 발급 재시도는 고정
인덱스 이름뿐 아니라 MongoDB 오류의 `keyPattern`도 확인한다.

## 충돌과 실패 처리

저장 시도는 요청당 최대 5회다.

| 상황 | 처리 |
| --- | --- |
| `resetCode` 충돌 | 새 후보를 생성해 재시도 |
| 동시 upsert의 `studentId` 충돌 | 같은 후보와 발급 시각으로 재시도 |
| 다른 중복 키·일반 DB 오류 | 재시도하지 않고 원래 오류 전파 |
| 5회 소진 | 내부 오류 반환, 메일 발송 안 함 |
| 현재 코드의 메일 실패 | `(studentId, resetCode)` 조건부 삭제 |
| 이전 요청의 지연된 메일 실패 | 더 최신 코드는 유지 |
| 조건부 삭제 실패 | 메일 예외를 유지하고 삭제 예외를 suppressed로 보관 |

재설정 코드 값은 로그에 기록하지 않는다. 중복 키 예외에는 실제 충돌 값이 포함될 수
있으므로 재시도 소진 로그에도 MongoDB 예외 객체를 남기지 않는다.

## 동시 요청 정책

같은 회원의 동시 요청이 모두 저장과 메일 발송에 성공하면 메일이 여러 통 전송될 수
있다. 최종적으로 MongoDB에 저장된 코드만 유효하며, 이전 메일의 코드는 이후 발급으로
무효화된다. 메일 도착 순서는 보장하지 않는다.

SMTP 서버가 메일을 수락한 뒤 오류를 반환하면 메일이 도착했어도 저장 코드는 삭제될 수
있다. 또한 이전 메일이 성공한 뒤 최신 발급의 메일이 실패하면 활성 코드가 0건이 될 수
있다. 이번 단계는 이런 경우 코드가 남지 않도록 처리하는 방식을 택했다. 전달 순서와
성공을 함께 보장하려면 사용자별 발급 직렬화나 outbox가 별도로 필요하다.

`SecureRandom`이 같은 회원의 직전 코드와 완전히 같은 값을 다시 만들면 기존 문서를 같은
코드로 갱신한다. 36가지 문자의 8자리 조합에서 발생 가능성은 낮지만, 새 발급 코드가
직전 코드와 반드시 다르다는 계약은 두지 않았다.

## 기존 데이터

`studentId`가 같은 문서가 이미 두 개 이상 있으면 유일 인덱스 생성을 실패시킨다. 만료
문서도 TTL monitor가 실제로 삭제하기 전에는 중복 데이터에 포함된다. 초기화 과정에서
기존 문서를 자동으로 삭제하지 않는다.

적용 전 점검이 필요하면 전체 문서를 대상으로 다음 집계를 사용할 수 있다.

```javascript
db.password_reset_codes.aggregate([
  {
    $group: {
      _id: "$studentId",
      count: { $sum: 1 },
      ids: { $push: "$_id" }
    }
  },
  { $match: { count: { $gt: 1 } } }
])
```

운영 데이터 정리와 배포 순서는 이번 작업 범위에 포함하지 않았다.

## 검증

MongoDB 7.0.16을 로컬 `27018` 포트에 격리해 대상 테스트를 실행했다.

```bash
./gradlew test \
  --tests 'com.didimlog.application.auth.AuthServiceFindIdPasswordTest' \
  --tests 'com.didimlog.infra.auth.SecureRandomPasswordResetCodeGeneratorTest' \
  --no-daemon

TEST_MONGO_PORT=27018 \
./gradlew integrationTest \
  --tests 'com.didimlog.application.auth.PasswordResetIssuanceConsistencyIntegrationTest' \
  --tests 'com.didimlog.global.config.mongo.MongoIndexInitializerIntegrationTest' \
  --no-daemon
```

확인한 주요 조건은 다음과 같다.

- 같은 회원의 순차 발급 뒤 문서 1건과 동일한 `_id` 유지
- 저장소 직접 동시 발급과 서비스 동시 발급 각각 20건
- 서비스 동시 발급 오류 0건, 최종 활성 코드 1건
- 이름이 다른 호환 `studentId` 유일 인덱스에서도 재시도 성공
- 다른 회원의 코드와 충돌한 뒤 새 후보로 발급
- 코드 충돌 5회 소진 시 새 문서와 메일 0건
- 현재 발급 메일 실패 뒤 코드 0건
- 이전 발급의 지연된 메일 실패 뒤 최신 코드 1건 유지
- 활성·만료 중복 문서가 함께 있으면 자동 삭제 없이 인덱스 초기화 실패

전체 검증은 다음 조건으로 실행했다.

```bash
TEST_MONGO_PORT=27018 \
SPRING_DATA_MONGODB_URI=mongodb://localhost:27018/didimlog-test \
SPRING_DATA_REDIS_PORT=6380 \
TEST_REDIS_PORT=6380 \
SERVER_URL=https://dummy-server.com \
./gradlew clean check jacocoMergedReport jacocoFullMergedReport --no-daemon
```

| 항목 | 결과 |
| --- | --- |
| 단위 테스트 | 509개 통과 |
| 통합 테스트 | 84개 중 77개 통과, 외부 연동·성능 테스트 7개 제외 |
| 발급 정합성 전용 MongoDB 테스트 | 9개 통과 |
| MongoDB 인덱스 계약 테스트 | 10개 통과 |
| core-v1 JaCoCo | Line 81.13%, Branch 59.49%, Class 91.37% |
| full-v1 JaCoCo | Line 64.57%, Branch 45.83%, Class 75.76% |

Phase 2B-2와 비교하면 core-v1은 Line `+0.20%p`, Branch `+0.35%p`, Class
`+0.00%p`, full-v1은 Line `+0.33%p`, Branch `+0.40%p`, Class `+0.06%p`
변했다. 처리량 개선 수치가 아니므로 성능 향상률로 사용하지 않는다.

검증된 정수 하한이 오른 core-v1 Line gate만 80%에서 81%로 올렸다. 나머지 gate는
유지했다.

| 범위 | Line | Branch | Class |
| --- | ---: | ---: | ---: |
| core-v1 | 81% | 58% | 91% |
| full-v1 | 64% | 45% | 75% |

## 남은 범위

- 재설정 코드 발급 API를 포함한 인증 Rate Limit 경로 정리
- Redis 카운터 증가와 최초 TTL 설정의 원자화
- 비밀번호 변경 뒤 기존 Refresh Token을 폐기하는 세션 정책
