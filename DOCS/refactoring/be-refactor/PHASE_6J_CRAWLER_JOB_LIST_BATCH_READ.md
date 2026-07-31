# Phase 6J — 문제 수집 작업 목록 일괄 조회

## 문제

문제 수집 작업 목록은 sorted index에서 작업 ID를 모두 읽은 뒤 상태 키를 하나씩
조회했다.

```text
ZREVRANGE problem:job:index
→ GET problem:job:status:{jobId} × N
→ queue position 계산·필터·페이징
```

`N`은 응답 페이지 크기가 아니라 index에 남아 있는 전체 작업 수다. 따라서 기본
페이지가 20건이어도 index 작업이 더 많으면 그만큼 `GET`이 늘었다. 이 흐름은 작업
목록과 감사 목록, 운영 메트릭, 대기 중인 작업의 queue position 계산에서 함께
사용됐다.

상태 키가 만료됐거나 JSON을 읽을 수 없으면 index와 실패 원장도 작업마다
`ZREM`, `DEL`로 정리했다. stale 항목 수만큼 조회와 정리 명령이 반복되는
구조였다.

## 변경

sorted index 순서대로 상태 키를 만들고 `MGET` 한 번으로 조회한다.

```text
ZREVRANGE problem:job:index
→ MGET problem:job:status:{jobId1} ... problem:job:status:{jobIdN}
→ ID와 상태 값을 같은 위치로 결합
→ queue position 계산·필터·페이징
```

`MGET` 결과의 `null`은 키가 없거나 문자열이 아닌 경우를 모두 뜻한다. `TYPE`으로
둘을 구분해 다음 기준을 적용한다.

- 상태 키가 없으면 stale 작업으로 분류한다.
- 문자열 상태가 `MGET` 직후 생성됐다면 단건으로 다시 읽어 조회 경합에서
  유효한 작업을 지우지 않는다.
- 상태 키가 문자열이 아닌 자료형이면
  `409 RESOURCE_STATE_CONFLICT`를 반환하고 관련 key를 보존한다.
- JSON 역직렬화에 실패한 문자열은 stale 작업으로 분류한다.

stale 작업은 모두 모은 뒤 index에서 한 번에 `ZREM`하고 실패 원장도 한 번에
`DEL`한다. 작업 목록과 감사 목록, 운영 메트릭의 응답 DTO와 공개 API 계약은
바꾸지 않았다.

## 비교 조건

- Before 코드 SHA:
  `6490c8b5af8725b4400102b3951f75a409836e4b`
- After 코드·측정 harness SHA:
  `b49f6c1fb93ee527f448ce4b1b449e899a734a59`
- Redis: `7.2.5`, DB 12
- 정상 작업 fixture:
  - index 작업 수 1, 5, 20
  - `PENDING`, `RUNNING`, `COMPLETED` 상태 혼합
  - index 작업 수와 응답 페이지 크기를 같게 설정
- stale 혼합 fixture:
  - 유효 작업 20건
  - 상태 키가 없는 작업 1건
  - 상태 값이 손상된 JSON인 작업 1건
  - index 항목은 모두 22건

통합 테스트에 Before의 ID별 조회와 정리 흐름을 `legacyGetJobs`로 고정했다. 같은
Redis와 같은 fixture에서 legacy 흐름과 실제 After 서비스를 차례로 호출하고,
fixture 저장을 마친 뒤 Lettuce `CommandListener`를 초기화해 목록 조회 중 실행된
명령만 기록했다.

## 측정 결과

### 정상 작업

| index 작업 수 | Before 명령 | After 명령 | Before 합계 | After 합계 | 감소율 |
| ---: | --- | --- | ---: | ---: | ---: |
| 1 | `ZREVRANGE 1`, `GET 1` | `ZREVRANGE 1`, `MGET 1` | 2 | 2 | 0.00% |
| 5 | `ZREVRANGE 1`, `GET 5` | `ZREVRANGE 1`, `MGET 1` | 6 | 2 | 66.67% |
| 20 | `ZREVRANGE 1`, `GET 20` | `ZREVRANGE 1`, `MGET 1` | 21 | 2 | 90.48% |

After의 `MGET` 인자 수는 각 조건에서 1, 5, 20이었다. 명령 수는 두 번으로
고정됐지만 `MGET`이 읽는 상태 값 수는 줄지 않는다.

### stale 항목 혼합

| 구분 | 명령 구성 | 합계 |
| --- | --- | ---: |
| Before | `ZREVRANGE 1`, `GET 22`, `ZREM 2`, `DEL 2` | 27 |
| After | `ZREVRANGE 1`, `MGET 1`, `TYPE 1`, `ZREM 1`, `DEL 1` | 5 |

After의 `MGET`은 22개 상태 키를 한 번에 읽었다. 상태 키가 없는 ID를 확인하는
`TYPE`은 1회였다. 두 stale ID는 `ZREM` 한 번에 전달했고 두 실패 원장 key도
`DEL` 한 번에 전달했다. 조회와 정리 명령 합계는 27회에서 5회로 81.48%
줄었다.

감소율은 `(Before - After) / Before × 100`으로 계산했다. 표는 고정 fixture의
Redis 명령 수 비교이며 전송 데이터 크기, JSON 역직렬화 비용, 응답 시간이나 운영
처리량의 개선율이 아니다.

## 응답과 정리 정합성

실제 Redis 통합 테스트에서 다음을 확인했다.

- 정상 작업 1·5·20건의 전후 페이지 응답과 queue position이 같음
- `queuedAt DESC` 목록 순서와 페이지 메타데이터 유지
- 빈 index에서는 `ZREVRANGE`만 실행하고 `MGET`을 생략
- 상태 키가 없는 작업과 손상 JSON 작업은 응답에서 제외
- 두 stale ID의 index membership과 실패 원장을 일괄 삭제
- 유효 작업의 상태 키와 실패 원장은 보존
- 문자열이 아닌 상태 key는 409를 반환하고 index·상태·실패 원장을 삭제하지 않음
- `MGET` 직후 생성된 문자열 상태는 단건 재조회해 보존
- 작업 메트릭, 감사 목록과 대기 작업 단건 조회의 기존 집계·필터·queue position
  유지

## 전체 검증과 커버리지

전용 MongoDB와 Redis에 연결해 `clean check`를 실행했다. 아래 값은 조회 명령
감소율과 분리한 테스트 검증 기록이다.

| 범위 | 직전 main | Phase 6J | 변화 |
| --- | ---: | ---: | ---: |
| 단위 테스트 | 736개 통과 | 738개 통과 | 2개 증가 |
| 통합 테스트 | 217개 중 208개 통과, 조건부 9개 제외 | 224개 중 215개 통과, 조건부 9개 제외 | 통과 7개 증가 |
| core-v1 Line / Branch / Method | 88.99% / 66.78% / 87.65% | 88.99% / 66.78% / 87.65% | 변화 없음 |
| full-v1 Line / Branch / Method | 77.87% / 59.23% / 78.03% | 78.71% / 60.60% / 78.63% | +0.84%p / +1.37%p / +0.60%p |

커버리지 변화는 새 목록 조회·정리 경계 테스트가 포함된 결과다. Redis 명령 수
감소나 운영 성능 향상으로 해석하지 않는다.

## 재현

목록 조회 통합 테스트:

```bash
TEST_REDIS_PORT=6398 \
SPRING_DATA_REDIS_HOST=127.0.0.1 \
SPRING_DATA_REDIS_PORT=6398 \
./gradlew integrationTest \
  --tests 'com.didimlog.application.problem.collector.ProblemCollectorJobListQueryIntegrationTest'
```

## 남은 범위

- `MGET`은 index의 상태 값 N개를 모두 전송하며 애플리케이션도 모두
  역직렬화한다. Redis 명령 수는 줄었지만 데이터 전송량과 CPU 비용은 O(N)이다.
- 타입·상태·기간 필터와 페이징은 전체 상태를 읽은 뒤 애플리케이션에서 처리한다.
  Redis 단계의 범위 조회나 보조 index는 이번 범위에 포함하지 않았다.
- 작업 index 자체에는 TTL이 없다. 상태가 만료된 index 항목은 목록을 읽을 때
  정리하므로 조회가 없는 동안의 선제 정리 정책은 별도 단계다.
- Phase 6J의 작업 상태와 실패 원장은 24시간 뒤 만료된다. 후속
  [Phase 6L](./PHASE_6L_CRAWLER_TARGET_MANIFEST.md)에서 추가한 대상 manifest도
  같은 TTL을 사용한다. 주·월 메트릭 장기 보존 정책은 별도 단계다.
- 서버 재시작 뒤 `PENDING`·`RUNNING` 작업을 자동으로 이어받는 worker lease는
  없다. 후속 [Phase 6K](./PHASE_6K_CRAWLER_STARTUP_ORPHAN_RECOVERY.md)는 단일
  BE 시작 시 진행 작업을 `FAILED`로 정리한다.
- 현재는 standalone Redis 구성을 기준으로 한다. Redis Cluster에서 여러 상태
  key를 `MGET`하려면 같은 hash slot을 보장하거나 다른 조회 방식을 사용해야 한다.

> [Phase 6L](./PHASE_6L_CRAWLER_TARGET_MANIFEST.md) 이후 stale 상태 정리는 실패
> 원장과 대상 manifest key를 함께 삭제한다. Phase 6J의 조회 명령 수 기준선은
> 변경하지 않는다.
