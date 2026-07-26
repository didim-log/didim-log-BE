# Phase 0B — 리팩터링 전 기준선 결과

## 문서 성격

이 문서는 BE 리팩터링 전 상태의 재현 가능한 기준선을 기록한다.
아직 동일 protocol로 측정한 리팩터링 후 결과가 없으므로 개선율이나 성과 표를
작성하지 않는다.

## 소스와 측정 조건

- Application baseline SHA: `74f7941d8d28275b9abe38877f32c4216955350b`
- 측정 application·harness SHA:
  `d28133065da6d3dce79db036d7ca3abfeef6ab3a`
- Application·harness dirty 상태: 모두 `false`
- Harness content SHA-256:
  `755b3ed35d2cca2ba7c899bbb490bd0e764b7c522678c606e673eddb41169b44`
- Paired protocol ID: `be-refactor-phase0b-v1`
- Paired protocol SHA-256:
  `1ba8fa49ee05b01dc335ed38819564063196ff39c52526349b1795edcdab779a`

고정 환경과 manifest 규칙은
[`PHASE_0B_PERFORMANCE_ENVIRONMENT.md`](./PHASE_0B_PERFORMANCE_ENVIRONMENT.md)를
따른다.

## 크롤러 command 기준선

`portfolio-fixture`, 실제 MongoDB·Redis, no-op pacer 조건에서 문제 6건의
metadata 수집 service를 inline으로 cold·warm 각각 5회 실행했다. 이 기준선은
`problems` 저장 data path와 Redis job lifecycle을 측정하며 task executor scheduling과
admin audit 경로는 제외한다.

- 5회 모두 MongoDB `find=6`, `update=6`
- 5회 모두 Redis `get=15`, `setex=9`, `zadd=9`
- 5회 모두 solved.ac fixture 호출 `6`
- cold 결과의 제목·level·분류·태그 저장을 검증
- warm 실행에서 기존 상세 내용이 보존되는지 검증

현재 구조는 문제 수만큼 MongoDB 조회·갱신과 solved.ac 호출이 반복되는 형태다.
6건 실행의 `itemsPerSecond`는 JIT와 로컬 노이즈에 민감하므로 원시 결과에만
진단값으로 남겼고 성능 수치나 개선 주장에는 사용하지 않는다.

프로덕션의 metadata 500ms, detail 2,000~3,999ms pacing은 유지했다.
기준선 테스트에서만 no-op pacer를 주입해 production pacing을 제거하고,
`portfolio-fixture`로 외부 solved.ac 대기를 제거했다. 따라서 이 측정은 외부 API
대기시간이나 executor 처리량이 아닌 명령 수와 저장 정확도를 비교하기 위한 것이다.

재현 명령:

```bash
CRAWLER_BASELINE_RUN_ID=crawler-baseline-d28133065da6 \
  performance/crawler/run-baseline.sh
```

원시 결과:

```text
performance/results/crawler-baseline-d28133065da6/
```

## 관리자 사용자 조회 기준선

실제 MongoDB에 학생 12명과 학생별 회고 1건을 저장하고 관리자 사용자 목록의
페이지 크기를 바꾸어 `find` command와 `explain("executionStats")`를 측정했다.

- page size 1: 학생 조회 1회 + 회고 조회 1회 = 총 `find 2회`
- page size 5: 학생 조회 1회 + 회고 조회 5회 = 총 `find 6회`
- page size 10: 학생 조회 1회 + 회고 조회 10회 = 총 `find 11회`
- 조회 command는 `1 + 현재 페이지에서 반환된 회원 수`로 증가
- `students`, `retrospectives` 모두 `_id_` 외 별도 index 없음
- 학생 전체 조회: `COLLSCAN`, 반환 12, 문서 검사 12, key 검사 0
- `studentId` 회고 조회: `COLLSCAN`, 반환 1, 문서 검사 12, key 검사 0

이는 N+1 조회와 `studentId` index 부재를 개선하기 전의 비교 기준이다.
아직 조회 구조나 index를 변경하지 않았으므로 개선율은 기록하지 않는다.

재현 명령:

```bash
ADMIN_QUERY_BASELINE_RUN_ID=admin-query-baseline-d28133065da6 \
  performance/query/run-baseline.sh
```

원시 결과:

```text
performance/results/admin-query-baseline-d28133065da6/
```

## 테스트·커버리지 확인

`./gradlew clean check --no-daemon` 결과는 다음과 같다.

- 단위 테스트: 482개, 실패 0
- 통합 테스트: 32개, 실패 0, 스킵 7
- core-v1 Line 78.79%, Branch 55.52%, Class 90.71%
- full-v1 Line 61.94%, Branch 42.20%, Class 74.78%

스킵 7건은 `GEMINI_API_KEY`가 필요한 기존 AI review 5건과
`CRAWLER_BASELINE_ENABLED`가 필요한 크롤러 기준선 2건이다. 크롤러 runner는
후자 2건을 활성화했고, clean check에서도 실행되는 관리자 조회 3건은 전용 격리
MongoDB에서 다시 실행해 JSON을 생성했다. 이 수치는 Phase 0B 계측 추가 후 품질
gate가 유지됐다는 확인이며 커버리지 향상 성과로 사용하지 않는다.

## 검증된 범위와 제한

- 크롤러 MongoDB command count는 대상 database·collection으로 제한한 command
  listener 결과다.
- 관리자 조회 command count는 격리 Mongo client에서 대상 collection으로 제한한
  command listener 결과다.
- Redis command count는 격리 Redis의 `INFO commandstats` 실행 전후 차이다.
- Query plan은 실제 MongoDB의 `explain("executionStats")` 결과다.
- 외부 solved.ac 응답은 결정적 로컬 fixture를 사용했다.
- manifest에는 비밀값을 기록하지 않으며 application·harness clean 상태를 검증했다.
- k6 workload 조건은 고정했지만 이번 단계에서는 before latency를 실행하지 않았다.
- Gradle 실행 application의 CPU·memory cgroup은 아직 고정하지 않았다.
- read workload는 closed model인 `constant-vus` 방식이다.
- `performance/results` 원시 JSON은 Git ignore 대상이며 README에는 게시하지 않는다.

Phase 1에서 한 가지 병목을 변경한 뒤 같은 기준선 runner와 paired protocol로 다시
측정한다. 차이가 반복 측정에서도 유지되고 원인이 설명될 때만 별도 성과 문서와
README 요약에 전후 수치와 개선율을 추가한다.

첫 번째 개선 결과는
[`PHASE_1A_ADMIN_N_PLUS_ONE.md`](./PHASE_1A_ADMIN_N_PLUS_ONE.md)에 기록한다.
