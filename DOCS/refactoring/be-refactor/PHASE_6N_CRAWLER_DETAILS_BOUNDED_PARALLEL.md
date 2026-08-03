# Phase 6N — 문제 상세 수집 제한 병렬화

## 문제

기존 상세 수집 작업은 HTTP 응답을 기다린 뒤 MongoDB와 Redis를
갱신하는 과정을 한 건씩 끝내고 다음 문제로 넘어갔다. 외부 응답을
기다리는 동안에는 다른 문제를 준비할 수 없어 수집 건수가 늘어날수록
대기 시간이 그대로 누적됐다.

단순히 완료된 순서대로 저장하면 속도는 높일 수 있지만 기존 Redis
진행률 계약을 깨뜨린다. `processedCount`는 이전 값에서 정확히 1씩
증가하고, checkpoint는 대상 manifest의 연속된 처리 prefix를 가리켜야
한다. 느린 앞 문제보다 뒤 문제의 checkpoint를 먼저 저장하면 재시작
후 사이의 문제가 누락될 수 있다.

## 제한 병렬 조회와 순서 보장 반영

```text
대상 manifest
  → 요청 시작 간격 확보
  → 최대 K개 상세 조회
  → manifest 순서로 결과 전달
  → MongoDB 부분 갱신
  → Redis 진행률·실패 ID·checkpoint 갱신
```

`ParallelProblemDetailsFetcher`는 외부 조회만 전용 executor에서 최대
`K`개씩 실행한다. 결과가 뒤섞여 완료돼도 coordinator는 manifest의
원래 순서로 Future를 받은 뒤 DB와 Redis를 갱신한다.

- 외부 조회·대기만 worker thread에서 실행한다.
- MongoDB 저장과 Redis 상태 갱신은 coordinator 한 곳에서 처리한다.
- 대기 Future와 완료 결과는 최대 `K`개이므로 추가 메모리는 `O(K)`다.
- coordinator가 중단되면 현재 기다리는 Future를 포함한 남은 창을
  `cancel(true)`로 취소한다.
- interrupt와 cancellation은 실패 항목으로 소비하지 않고 작업 중단으로
  전파해 checkpoint를 옮기지 않는다.

`ProblemCollectorPacer`는 대기를 요청 끝이 아닌 시작 직전에 적용한다.
동일 JVM의 여러 수집 작업은 하나의 요청 시작 타임라인을 공유한다.
첫 요청은 즉시 통과하고 메타데이터는 500ms, BOJ 상세는 2~4초의
시작 간격을 유지한다.

## 설정

```yaml
app:
  problem-collector:
    parallel:
      enabled: false
      max-concurrency: 1
```

환경 변수는 `PROBLEM_COLLECTOR_PARALLEL_ENABLED`,
`PROBLEM_COLLECTOR_MAX_CONCURRENCY`를 사용한다. 잘못된 설정으로 과도한
thread가 생성되지 않도록 동시성은 `1..16`으로 제한한다. 운영
기본값은 병렬 처리를 켜지 않은 `false`, `1`이다.

## 정확성·중단 경계 검증

| 조건 | 결과 |
| --- | --- |
| 1번이 느리고 2·3번이 먼저 완료 | MongoDB 저장과 checkpoint는 `1 → 2 → 3` |
| 중간 항목의 외부 조회 실패 | 실패 ID만 Redis 원장에 기록, 연속 checkpoint 완료 |
| 작업 취소 후 대기 결과 도착 | MongoDB 저장·진행률 증가 0건 |
| coordinator interrupt | 현재 Future와 남은 창 interrupt, checkpoint 증가 0건 |
| 병렬 worker interrupt | 항목 실패로 소비하지 않고 작업 `FAILED` |
| `K=2·3` 활성 수 | 설정한 창 크기 초과 0건 |

## 3,400건 로컬 고정 지연 측정

외부 BOJ에는 요청하지 않았다. 10ms 후 고정 `ProblemDetails`를 반환하는
fixture crawler로 외부 대기를 재현하고, `ProblemCollectorService`, MongoDB
7.0.16, Redis 7.2.5, 부분 갱신과 작업 상태 갱신은 실제 경로를
사용했다. 외부 파싱 성능이 아닌 제한 병렬 파이프라인의 처리
시간을 비교하려는 측정이다.

측정 중에는 실제 운영 호출 간격이 결과를 가리지 않도록 pacer를 끌
때와 같은 no-op으로 두었다. 따라서 이 수치를 BOJ 실제 3,400건 완료
시간으로 해석하면 안 된다.

| 반복 | 순차 | `K=4` | 단축률 |
| ---: | ---: | ---: | ---: |
| 1 | 54.981초 | 12.116초 | 77.96% |
| 2 | 55.954초 | 12.226초 | 78.15% |
| 3 | 55.632초 | 12.354초 | 77.79% |
| 중앙값 | **55.632초** | **12.226초** | **77.96%** |

세 회 모두 다음 조건을 통과했다.

- 최종 MongoDB 문서 3,400건, 누락·중복·미완성 상세 0건
- crawler 호출 3,400회, 중복 대상 호출 0회
- `processed=3,400`, `success=3,400`, `fail=0`, 마지막 checkpoint `4399`
- 순차·병렬 최종 결과 SHA-256 일치
- 최대 동시 실행 순차 1, 병렬 4

재현 명령:

```bash
ALLOW_DIRTY_CRAWLER_DETAILS_BENCHMARK=true \
  performance/crawler/run-details-parallel-benchmark.sh
```

결과 JSON은 `performance/results/`에 생성되며 `.gitignore`로 추적하지
않는다. 세 반복의 관련 파일 hash는 모두
`3803756f9bb0ddc26d9d7039156c2af1dd9f3069b66ec1f2bf6325729543d372`로
같았다. 측정 시 worktree가 dirty였으므로 결과를 운영 성능 보증으로
사용하지 않는다.

## 남은 한계

- 요청 시작 간격은 하나의 JVM 안에서만 공유한다. 여러 BE 인스턴스의
  합산 요청률을 제한하려면 단일 수집 worker 배치나 Redis 분산 limiter가
  필요하다.
- 취소와 worker lease 상실은 결과 반영을 막지만, 이미 시작된 최대
  `K`개의 HTTP 요청을 즉시 종료한다고 보장하지 않는다.
- worker 소유권 확인과 MongoDB 부분 갱신은 서로 다른 시스템의
  연산이다. 경계에서 이전 worker의 갱신 한 건과 새 worker의 재시도가
  겹칠 수 있으며, ID 기반 부분 갱신으로 최종 문서를 멱등하게 만든다.
- `BojCrawler`는 HTTP 실패를 `null`로 합쳐 반환한다. 429·5xx·timeout과
  4xx를 구분한 자동 재시도는 아직 없고, 실패 ID를 Redis에 남겨
  작업 재시도 API로 다시 수집한다.
- 운영 기본은 병렬 비활성화다. 활성화하기 전에 외부 사이트의
  허용 요청률과 단일·다중 worker 배치를 먼저 확정해야 한다.
