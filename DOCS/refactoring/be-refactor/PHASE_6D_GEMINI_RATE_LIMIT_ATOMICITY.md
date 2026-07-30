# Phase 6D — Gemini 호출 제한 원자화

## 문제

기존 `GeminiRateLimiter`는 최소 호출 간격, 분당 사용량(RPM), 일일 사용량(RPD)을
각각 다른 Redis 명령으로 처리했다.

```text
GET last
→ INCR rpm → 첫 값이면 EXPIRE
→ INCR rpd → 첫 값이면 EXPIRE
→ SET last
```

같은 시각에 도착한 요청들은 `last` 값을 함께 조회하고 최소 간격 검사를 모두 통과할
수 있었다. RPM과 RPD도 한 요청 단위로 함께 검사·기록되지 않았다. RPM은 증가한 뒤
한도를 확인했기 때문에 차단된 요청까지 RPM에 포함됐고, 그 요청은 RPD와 `last`를
기록하지 않은 채 끝났다.

429 응답을 재시도할 때도 문제가 있었다. 호출 허가는 첫 HTTP 요청 전에 한 번만
받고, Reactor 재시도는 허가 확인 없이 다음 HTTP 요청을 보냈다. 실제 공급자 요청
수와 Redis에 기록한 허가 수가 달라질 수 있는 경로였다.

## 기준선

- Before SHA: `9791705fe30f697b49ca1c47f39442ead7023e36`
- Redis: `7.2.5`
- Redis DB: `14`
- 조건: 최소 간격 키가 없는 상태에서 같은 논리 시각으로 20건 동시 실행
- 기존 고정 정책: 최소 간격 4초, 15 RPM, 1,500 RPD

같은 `CountDownLatch` 통합 테스트를 기존 구현에 먼저 적용했다. 최소 간격에 따라
한 건만 허용돼야 한다는 검사는 `expected: 1, actual: 15`로 실패했다.

| 항목 | Before |
| --- | ---: |
| 동시 요청 | 20건 |
| 허용 | 15건 |
| 차단 | 5건 |
| 최종 RPM | 20 |
| 최종 RPD | 15 |

차단 5건도 RPM을 증가시켰고, RPD와 `last`는 앞서 허용된 15건만 기록했다. 이 값은
실제 Redis에서 동시 실행의 원자성을 확인한 정확성 기준선이며 응답 시간이나 처리량
측정값이 아니다.

## 변경

### 검사와 기록을 Lua 한 번으로 처리

애플리케이션은 현재 UTC 분·일 버킷의 키와 남은 만료 시간, 운영 정책을 Redis Lua
스크립트에 전달한다. 스크립트는 아래 순서를 한 번에 처리한다.

```text
RPM·RPD·최소 간격 상태 읽기
→ 값과 설정 검증
→ 기존 TTL 복구
→ 최소 간격 → RPM → RPD 순서로 한도 확인
→ 허용된 요청만 RPM·RPD 증가와 최소 간격 키 기록
```

결과는 `결정 코드, RPM, RPD, 재시도 대기 시간` 네 값으로 돌려준다.

| 결정 코드 | 의미 | 카운터 기록 |
| ---: | --- | --- |
| `0` | 허용 | RPM·RPD 각각 1 증가 |
| `1` | 최소 간격 미달 | 증가하지 않음 |
| `2` | RPM 한도 도달 | 증가하지 않음 |
| `3` | RPD 한도 도달 | 증가하지 않음 |
| `4` | 설정 또는 저장값 손상 | 증가하지 않고 실패 |

허용 경로의 RPM과 RPD 만료 시각은 각각 다음 UTC 분·일 경계에 맞춘다. 차단 경로는
유효한 카운터를 증가시키거나 기존의 유한 TTL을 연장하지 않는다. 다만 이전 코드가
남긴 TTL 없는 키나 현재 버킷보다 긴 TTL은 아래의 호환 처리에 따라 복구한다.

애플리케이션과 Redis 사이의 호출은 정상 구간에서 기존 4회(`GET`, `INCR`,
`INCR`, `SET`), 버킷 첫 요청에서 최대 6회였던 개별 명령을 Lua 실행 1회로 합쳤다.
이는 코드 경로의 왕복 수 비교일 뿐, 지연 시간이나 처리량 개선율은 아니다.

### 기존 키를 요청 시점에 전환

키 이름은 기존 값과 이어서 사용할 수 있도록 유지했다.

```text
gemini:rate:rpm:<UTC epoch minute>
gemini:rate:rpd:<UTC epoch day>
gemini:rate:last:
```

- TTL 없는 RPM·RPD 키는 현재 UTC 버킷의 남은 시간으로 만료를 설정한다.
- 현재 버킷 경계를 넘는 TTL은 남은 시간으로 줄인다.
- TTL 없는 `last` 값은 기존 초 단위 epoch 값을 읽어 남은 최소 간격만큼 TTL을
  설정한다.
- 초 단위로 저장된 기존 `last`는 같은 초의 마지막 밀리초로 보수적으로 계산해
  최소 간격을 일찍 열지 않는다.
- 이미 최소 간격이 지난 `last`는 지우고 현재 요청을 계속 검사한다.
- 숫자가 아닌 값이나 음수 사용량은 정상 상태로 간주하지 않고 요청을 허용하지
  않는다.

별도 일괄 변환 없이 새 코드가 처음 키를 읽을 때 전환한다. `minIntervalSeconds=0`은
최소 간격 제한만 끄는 설정이다. 이때 `last` 키를 삭제하며 RPM과 RPD 제한은 계속
적용한다.

### 실제 HTTP 재시도마다 허가 확인

Gemini HTTP 요청과 허가 확인을 `Mono.defer` 안에 두었다. 최초 구독과 429 이후
재구독 모두 다음 순서를 따른다.

```text
Redis 허가 획득
→ Gemini HTTP 요청
→ 429이면 대기
→ Redis 허가 재획득
→ 다음 Gemini HTTP 요청
```

재시도 대기 시간은 설정된 backoff와 최소 호출 간격 중 더 긴 값을 사용한다. 두 번째
허가가 거절되면 두 번째 HTTP 요청은 보내지 않는다. 공급자 429가 재시도 뒤에도
계속되면 `AI_SERVICE_BUSY`를 유지하고, Redis 연결 실패나 명령 시간 초과는
`RATE_LIMIT_SERVICE_UNAVAILABLE` 503과 `retryable=true`로 전달한다. AI 리뷰
서비스의 동기 호출 경계는 이 오류를 일반 500으로 바꾸지 않는다. 공개 AI 리뷰
요청은 작업을 등록한 뒤 먼저 `202`를 반환하므로 이후 워커에서 난 오류가 그 HTTP
응답을 바꾸지는 않는다. 워커는 실패 상태를 기록하고 공급자 호출이 완료되지 않은
경우 앞서 예약한 사용자·전역 사용량을 반환한다.

## 설정

운영 기본값과 환경 변수는 다음과 같다.

| 항목 | 기본값 | 환경 변수 |
| --- | ---: | --- |
| 최소 호출 간격 | 4초 | `GEMINI_RATE_LIMIT_MIN_INTERVAL_SECONDS` |
| 분당 호출 한도 | 15 | `GEMINI_RATE_LIMIT_MAX_RPM` |
| 일일 호출 한도 | 1,500 | `GEMINI_RATE_LIMIT_MAX_RPD` |

최소 호출 간격은 0 이상, RPM과 RPD는 1 이상이어야 한다. 설정값이 이 범위를
벗어나면 애플리케이션 설정 바인딩 단계에서 실패한다. 여러 인스턴스가 하나의 전역
제한을 공유하려면 같은 Redis와 같은 세 정책값을 사용해야 한다.

## 정확성 검증

실제 Redis 통합 테스트에서 다음 조건을 확인했다.

- 최소 간격이 4초일 때 같은 시각의 20건 중 1건만 허용하고 RPM·RPD를 각각 1로 기록
- RPM이 14일 때 같은 시각의 20건 중 1건만 허용하고 최종 RPM을 15로 유지
- RPD 차단 시 RPM·RPD 값과 기존 TTL을 연장하지 않음
- 최소 간격 차단 시 RPM·RPD 값과 간격 TTL을 연장하지 않음
- TTL 없는 기존 RPM·RPD·`last` 키를 남은 구간에 맞춰 전환
- 아직 유효한 기존 `last` 키는 카운터를 늘리지 않고 차단
- 손상된 카운터는 값을 덮어쓰거나 공급자 호출을 허용하지 않음
- 음수 일일 사용량은 손상 상태로 처리
- UTC 분·일 경계에서 새 버킷으로 전환

로컬 Reactor Netty 서버를 사용한 재시도 테스트도 다음 경계를 확인했다.

- 공급자가 `429 → 200`을 반환하면 HTTP 요청 2건과 Redis 허가 2건이 일치
- 두 번째 허가가 거절되면 HTTP 요청은 최초 1건에서 중단
- Redis가 중단되면 공급자 HTTP 요청은 0건이고 503 오류를 유지
- 429가 계속되면 설정한 재시도 횟수만큼만 호출하고 혼잡 오류를 유지
- 첫 429 뒤 Redis가 중단되면 추가 HTTP 요청 없이 503 오류를 유지
- 호출 제한·저장소 장애·컨텍스트 초과 오류는 AI 리뷰 서비스에서 유지되고 예약
  사용량이 반환되며, 다른 공급자 내부 오류는 기존 생성 실패 응답으로 변환

기준선 확인:

```bash
TEST_REDIS_PORT=6398 \
SPRING_DATA_REDIS_HOST=127.0.0.1 \
SPRING_DATA_REDIS_PORT=6398 \
./gradlew integrationTest \
  --tests 'com.didimlog.infra.ai.GeminiRateLimiterIntegrationTest' \
  --rerun-tasks
```

Before 구현에서는 동시 최소 간격 검사가 `expected: 1, actual: 15`로 실패했고,
Redis 값은 RPM 20, RPD 15였다.

변경 뒤 대상 검증:

```bash
bash -n performance/k6/run-local.sh

TEST_REDIS_PORT=6398 \
SPRING_DATA_REDIS_HOST=127.0.0.1 \
SPRING_DATA_REDIS_PORT=6398 \
./gradlew test \
  --tests 'com.didimlog.infra.ai.GeminiRateLimiterTest' \
  --tests 'com.didimlog.infra.ai.GeminiLlmClientRetryTest' \
  --tests 'com.didimlog.application.log.AiReviewServiceTest' \
  integrationTest \
  --tests 'com.didimlog.infra.ai.GeminiRateLimiterIntegrationTest' \
  --rerun-tasks
```

셸 문법 검사와 대상 단위·통합 테스트는 모두 통과했다. 검증에는 호출 제한 단위
시나리오 9개, HTTP 재시도 5개, 실제 Redis 통합 시나리오 9개와 기존 AI 리뷰 서비스
회귀 테스트가 포함된다.

이 단계는 동시 요청의 허가 수, 카운터와 오류 전달을 검증했다. 같은 부하를 반복한
응답 시간·처리량 측정은 하지 않았으므로 성능 향상률이나 개선 백분율은 기록하지
않는다.

## 남은 제한

- 구 버전은 Lua 규칙을 사용하지 않고 같은 Redis 키를 개별 명령으로 변경한다.
  구·신 버전의 혼합 실행은 지원하지 않는다. 구 버전 작업을 종료한 뒤 새 버전으로
  전체 교체해야 한다.
- 분·일 버킷과 남은 TTL은 각 애플리케이션 인스턴스의 UTC 시각으로 계산한다. 모든
  인스턴스와 Redis 호스트의 NTP 동기화를 유지해야 하며, 큰 시각 차이가 있으면 서로
  다른 버킷을 사용할 수 있다.
- 현재 키 세 개는 Redis Cluster의 같은 hash slot을 보장하는 hash tag가 없다.
  이번 Lua 스크립트는 standalone Redis 기준이다. Cluster로 전환할 때는 키에 같은
  hash tag를 적용하고 기존 키 전환 절차를 별도로 마련해야 한다.
- 여러 애플리케이션 인스턴스가 다른 Redis 또는 다른 제한 설정을 사용하면 전역
  호출 제한이 나뉜다. 모든 인스턴스가 같은 Redis와 정책을 사용해야 한다.
- Redis 연결 실패와 timeout은 허가하지 않고 503을 반환한다. 반대로 Redis 데이터가
  유실되면 현재 버킷의 카운터도 초기화되며, 네트워크 분리로 서로 다른 Redis가
  승격되면 각 환경에서 별도 허가가 발생할 수 있다.
- 허가 뒤 네트워크 실패가 나거나 공급자가 429를 반환해도 이미 시도한 호출은 RPM과
  RPD에 남긴다. 실제 공급자 요청 시도를 보수적으로 세기 위한 정책이며 자동 반환하지
  않는다.
- `GEMINI_RATE_LIMIT_MIN_INTERVAL_SECONDS=0`은 개발·검증용으로 최소 간격만
  비활성화한다. RPM과 RPD는 유지되지만 운영에서는 공급자 제한과 동시 요청 급증을
  고려해 기본값 4초를 사용한다.
- EC2 배포, 운영 Redis 데이터 전환과 실제 Gemini 계정 한도 검증은 이번 범위에서
  제외했다.
