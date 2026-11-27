# 성능 측정 환경 기준선

리팩터링 전후 성능을 같은 조건에서 비교하려고, 외부 서비스 이미지, JVM 설정, 테스트 데이터, mock 지연, 반복 횟수를 고정했습니다. 조건이 하나라도 달라지면 측정이 시작되지 않습니다. 이 단계는 측정 조건을 고정한 작업이고 성능 개선 결과는 아닙니다.

## 이전과 현재

| 항목 | 이전 | 현재 |
| --- | --- | --- |
| MongoDB·Redis·WireMock 이미지 | 태그만 지정, 같은 태그가 다른 이미지를 가리킬 수 있음 | 태그와 이미지 digest를 함께 고정 |
| JVM·데이터·mock 조건 | 실행마다 다를 수 있음 | 아래 표의 값으로 고정, 다르면 실행 거부 |
| 측정 대상 코드 | 수정 중인 코드가 섞일 수 있음 | 커밋되지 않은 변경이 있으면 실행 거부 |
| 실행 조건 기록 | 없음 | 실행마다 환경 manifest 생성, 비밀값은 기록 안 함 |
| 측정 데이터 volume 삭제 | 정리 명령에 섞일 수 있음 | 확인 문자열을 넣은 전용 명령으로만 삭제 |

## 고정한 조건

| 항목 | 값 |
| --- | --- |
| 외부 서비스 | `mongo:7.0.16`, `redis:7.2.5-alpine`, `wiremock/wiremock:3.9.1` |
| 앱 실행 | `./gradlew bootRun`, Java 17 |
| JVM | `-Xms512m -Xmx512m`, G1 GC, `Asia/Seoul` |
| Gemini mock | WireMock, 고정 지연 500ms |
| 테스트 데이터 | 기준 시각 `2026-07-26T00:00:00Z`, 회고 100건 |
| 조회 부하 | 10 VUs, 1분 |
| AI 리뷰 부하 | 동시 50건, 10회 |
| 반복 | 변경 전·후 각각 5회 |

## 바꾼 내용

- `.env.performance` 하나로 앱과 k6가 같은 값을 읽습니다. 실행마다 바꾸는 값은 측정 대상 작업 트리, 실행 ID, 반복 번호뿐입니다.
- 실행 전에 `performance/results/environment-{실행 ID}.json`을 만듭니다. 코드 버전과 변경 여부, Java·JVM·k6 버전, 이미지, mock, 데이터, 부하 조건, 호스트 사양을 남기고, JWT secret·비밀번호·API key·토큰은 남기지 않습니다.
- 처음 실행할 때 조건 전체를 묶은 기준을 만들고, 이후 실행은 코드 버전과 반복 번호를 뺀 조건이 같아야 시작합니다. k6 스크립트나 mock이 바뀌어도 같은 비교로 보지 않습니다.
- 앱이 실제로 접속하는 MongoDB, Redis, Gemini 주소가 하네스 설정과 같고, 모두 `127.0.0.1`의 로컬 주소일 때만 앱을 띄웁니다. 상위 셸 환경과 외부 `.env`는 넘기지 않습니다.
- JVM 옵션을 덮어쓰는 `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`, `SPRING_APPLICATION_JSON`이 있으면 실행을 거부합니다. Gemini mock이 환경에 따라 다른 구현으로 바뀌는 `auto` 모드도 거부합니다.
- CI에서 받는 k6 바이너리는 체크섬을 확인한 뒤 설치합니다.

## 확인한 방법

```bash
cp performance/k6/env.example .env.performance
performance/k6/run-local.sh start-mocks
performance/k6/run-local.sh seed
performance/k6/run-local.sh start-app   # 다른 터미널
performance/k6/run-local.sh preflight
performance/k6/run-local.sh read
performance/k6/run-local.sh cleanup
```

스크립트 문법(`bash -n`), manifest JSON 형식, compose 설정을 따로 검사합니다. 이 환경으로 잰 기준값은 [성능 기준선 측정 결과](./PHASE_0B_BASELINE_RESULTS.md)에 있습니다.

## 남은 한계

- 앱은 아직 CPU·메모리 제한이 걸린 컨테이너 안에서 실행되지 않습니다.
- 조회 부하는 VU 수를 고정하는 closed model이라 도착률 기반 측정과는 다릅니다.
- 원본 결과 JSON은 Git에 올리지 않습니다.
