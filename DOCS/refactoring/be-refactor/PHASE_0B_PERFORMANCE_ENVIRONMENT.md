# Phase 0B — 성능 측정 환경 기준선

## 목적

BE 리팩터링 전후를 같은 로컬 합성 환경에서 비교하기 위해 외부 서비스 이미지,
JVM heap, fixture 시각·크기, mock 조건과 반복 정보를 고정한다.
이 단계는 측정 조건을 고정하는 작업이며 성능 개선 결과를 만들거나 주장하는 단계가 아니다.

## 소스 기준

- Application baseline SHA: `74f7941d8d28275b9abe38877f32c4216955350b`
- Phase 0A coverage checkpoint: `dfc051983e8decd1bf94993193a4ac4fde43917e`
- Baseline branch: `develop-be-refactor-baseline`
- 성능 실행 시 application과 harness 모두 `gitDirty=false`여야 한다.

Application SHA와 harness SHA는 environment manifest에 별도로 기록한다.
하네스 문서나 스크립트가 변경되어도 application baseline SHA를 바꾸지 않는다.
측정 대상은 `APPLICATION_WORKTREE`로 지정하며 commit SHA와 dirty 여부는 해당
worktree의 Git 상태에서 직접 검출한다. `.env.performance`에 측정 SHA나 dirty 값을
수동으로 고정하지 않는다.

## Immutable container images

2026-07-26에 `docker buildx imagetools inspect`로 모두
`application/vnd.oci.image.index.v1+json` multi-arch index임을 확인했다.

| Service | Tag | OCI index digest |
| --- | --- | --- |
| MongoDB | `mongo:7.0.16` | `sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a` |
| Redis | `redis:7.2.5-alpine` | `sha256:6aaf3f5e6bc8a592fbfe2cccf19eb36d27c39d12dab4f4b01556b7449e7b1f44` |
| WireMock | `wiremock/wiremock:3.9.1` | `sha256:8fe02bc3f9b63deb1454d41750dbaf081adf4b3e8c74fd8e31f790bee5647b88` |

Compose에는 tag와 digest를 함께 기록해 사람이 버전을 확인할 수 있으면서
registry tag 변경과 무관하게 동일 OCI index를 사용한다.

## 고정 환경

`performance/k6/env.example`을 `.env.performance`로 복사한 뒤 측정 대상
`APPLICATION_WORKTREE`, `K6_RUN_ID`, 반복 index만 실행별로 변경한다.
명령 앞에서 지정한 `APPLICATION_WORKTREE`, `K6_RUN_ID`, 반복 index는 파일 값보다
우선한다.

| 항목 | 고정값 |
| --- | --- |
| App runtime mode | `gradle-toolchain` |
| App Java | 17 (`build.gradle.kts` toolchain) |
| JVM heap | `-Xms512m -Xmx512m` |
| GC | G1 |
| App timezone | `Asia/Seoul` |
| Spring profile | `default` |
| Gemini mock mode | `wiremock` |
| Gemini fixed delay | 500ms |
| Fixture version | `be-refactor-phase0b-v1` |
| Fixture epoch | `2026-07-26T00:00:00Z` |
| Retrospective fixture | 100건 |
| Read workload | 10 VUs, 1분 |
| AI concurrency | 50건 |
| AI repeat | 10회 |
| Before/after 반복 | 각각 5회 |

`MOCK_GEMINI_MODE=auto`는 실행 환경에 따라 Node mock으로 fallback할 수 있으므로
environment manifest가 거부한다.

기준 실행은 `./gradlew bootRun`이므로 `APP_RUNTIME_MODE=gradle-toolchain`을 사용한다.
manifest는 측정 대상 worktree의 `build.gradle.kts`에서 toolchain Java를 검출하고
`APP_JAVA_VERSION`과 다르면 실행을 거부한다. Phase 0B는 이 실행 방식만 허용한다.
Docker application 측정은 application image digest와 resource limit을 함께 고정한
별도 기준이 생긴 뒤 사용한다.
Gradle 기준 실행에서는 `JAVA_TOOL_OPTIONS`가 고정 heap·GC 값과 정확히 일치해야 한다.
`_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`, `SPRING_APPLICATION_JSON`은 실행을 거부한다.

## Environment manifest

성능 실행 전에 `run-local.sh`가 다음 파일을 한 번 생성한다.

```text
performance/results/environment-{K6_RUN_ID}.json
```

직접 생성할 수도 있다.

```bash
performance/k6/run-local.sh environment-manifest
```

manifest는 명시적 allowlist만 직렬화한다.

- application baseline SHA, 측정 worktree에서 검출한 SHA와 dirty 여부
- harness SHA와 dirty 여부
- runtime mode, 설정·검출 Java, JVM heap, GC, k6 버전
- 앱 MongoDB·Redis·Gemini endpoint 일치 여부와 비밀이 아닌 timeout·executor 설정
- resolved container image digest
- mock mode와 fixed delay
- fixture version, epoch, count
- read·AI·Rate Limit 조건과 반복 index
- 호스트 OS, architecture, CPU, memory

JWT secret, password, API key, token, credential 포함 URI와 전체 process environment는
기록하지 않는다. dirty worktree에서는 기본적으로 manifest 생성과 k6 실행을 거부한다.
같은 `K6_RUN_ID`의 manifest가 이미 있으면 현재 환경을 다시 검증하고, 기존 snapshot과
하나라도 다를 경우 덮어쓰지 않고 새 run ID를 요구한다.
`K6_RUN_ID`는 영문·숫자로 시작하고 영문·숫자·점·밑줄·하이픈만 사용하는
81자 이하 slug여야 한다.

`start-app`은 Spring Boot가 실제 사용하는 MongoDB URI, Redis host·port·database,
Gemini URL을 하네스 설정과 비교한다. 모두 credential 없는 로컬 endpoint이고 서로
일치할 때만 지정한 `APPLICATION_WORKTREE`에서 앱을 실행한다. 부모 shell 환경은
상속하지 않고 명시한 local-only 앱 설정만 전달하며, 외부 `.env` import도 차단한다.
Compose의 실제 published port와 endpoint port도 일치해야 하고 모든 port는
`127.0.0.1`에만 bind된다.

첫 실행은 다음 paired protocol snapshot을 생성한다.

```text
performance/results/protocol-{PERFORMANCE_PROTOCOL_ID}.json
```

이후 다른 `K6_RUN_ID`도 application source SHA와 반복 index를 제외한 runtime,
host, container, mock, fixture, endpoint, workload 조건의 SHA-256 fingerprint가
같아야 실행된다. k6·fixture·mock·검증 script 등 `performance/` 하네스 자산의
content hash는 fingerprint에 포함하므로 측정 로직이 달라지면 동일 protocol로
비교할 수 없다.
애플리케이션의 Gemini client·AI executor 설정은 튜닝 대상일 수 있어 protocol
fingerprint에서는 제외하되 별도 `applicationConfigSha256`으로 남긴다.

CI에서 내려받는 Linux amd64 k6 release asset은
`performance/k6/K6_LINUX_AMD64_SHA256`으로 checksum을 검증한 뒤 설치한다.

## 성능 전용 volume reset

volume 삭제는 `start-mocks`, `all`, `cleanup`에 포함하지 않는다.
아래 명시적 명령으로만 실행한다.

```bash
CONFIRM_PERFORMANCE_VOLUME_RESET=didimlog-performance \
  performance/k6/run-local.sh reset-volumes
```

reset은 다음 조건을 모두 만족해야 실행된다.

- local `didimlog-performance` MongoDB
- `MOCK_GEMINI_MODE=wiremock`
- 확인 문자열 일치
- performance compose에 선언된 volume이 정확히
  `didimlog-performance-mongo-data` 하나

조건이 다르면 종료하고 어떤 volume도 삭제하지 않는다.

## 기준 실행 순서

```bash
cp performance/k6/env.example .env.performance

CONFIRM_PERFORMANCE_VOLUME_RESET=didimlog-performance \
  performance/k6/run-local.sh reset-volumes

performance/k6/run-local.sh start-mocks
performance/k6/run-local.sh seed

# 별도 terminal에서 같은 .env.performance로 지정 worktree의 application 실행
performance/k6/run-local.sh start-app

performance/k6/run-local.sh preflight
performance/k6/run-local.sh smoke
performance/k6/run-local.sh read
performance/k6/run-local.sh ai-review
performance/k6/run-local.sh ai-retry
performance/k6/run-local.sh rate-limit
performance/k6/run-local.sh cleanup
```

각 before/after 반복은 새로운 `K6_RUN_ID`와 정확한
`MEASUREMENT_REPETITION_INDEX=1..5`를 사용한다. 앱과 k6 명령은 같은
`.env.performance`와 `K6_RUN_ID`를 사용한다.

## 검증

```bash
bash -n performance/k6/run-local.sh
bash -n performance/verify/write_environment_manifest.sh
python3 -m json.tool /tmp/didimlog-phase0b-environment.json
docker compose \
  -f performance/mock-external/docker-compose.performance.yml \
  config
```

## 남은 제한

- application은 아직 성능 compose의 CPU·memory cgroup 안에서 실행되지 않는다.
- 기존 read workload는 closed model인 `constant-vus` 방식이다.
- `performance/results` raw JSON은 Git ignore 대상이다.
- 이 기준선으로 before와 after를 반복 측정하기 전에는 개선율을 계산하지 않는다.

깨끗한 Phase 0B 커밋으로 실행한 command count와 query plan은
[`PHASE_0B_BASELINE_RESULTS.md`](./PHASE_0B_BASELINE_RESULTS.md)에 기록한다.
