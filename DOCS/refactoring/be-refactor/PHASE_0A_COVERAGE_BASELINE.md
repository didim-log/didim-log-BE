# Phase 0A — 테스트·커버리지 기준선

## 목적

BE 리팩터링 전후를 같은 JaCoCo 범위로 비교하기 위한 기준선을 고정한다.
이 문서의 수치는 성능 개선 결과가 아니며, 리팩터링 전 측정 범위와 품질 하한을 기록한 것이다.

## 소스 기준

- Application baseline SHA: `74f7941d8d28275b9abe38877f32c4216955350b`
- Baseline branch: `develop-be-refactor-baseline`
- Java toolchain: 17
- JaCoCo: 0.8.12
- 테스트 실행 전 `clean` 필수

기존 `main` 작업 트리와 기준선 작업 트리를 분리했다. 기준선 측정에는 수정 중인 DTO와 미추적 성능 파일을 포함하지 않는다.

## 고정 범위

### core-v1

기존 `jacocoMergedReport` allowlist를 그대로 보존한다. 과거 측정과의 연속성을 위한 범위이며 다음 제한이 있다.

- `application/problem/collector` 제외
- 일부 application 패키지 제외
- 전체 UI와 infra 패키지 제외

따라서 core-v1 수치는 전체 서비스 커버리지가 아니다.

### full-v1

`com/didimlog/**` 아래의 전체 프로덕션 클래스를 포함한다. 테스트 클래스만 제외한다.
앞으로 BE 전체 리팩터링 전후 비교에는 full-v1을 기본 범위로 사용한다.

## 최초 clean 측정

- 단위 테스트: 482개, 실패 0
- 통합 테스트: 27개, 실패 0, 스킵 5
- core-v1 Line: 3,184 / 4,041, 78.79%
- core-v1 Branch: 779 / 1,403, 55.52%
- core-v1 Class: 166 / 183, 90.71%
- full-v1 Line: 5,282 / 8,526, 61.95%
- full-v1 Branch: 1,125 / 2,666, 42.20%
- full-v1 Class: 334 / 447, 74.72%
- collector 패키지 Line: 328 / 713, 46.00%
- collector 패키지 Branch: 75 / 278, 26.98%

full-v1 최초 값이 core-v1보다 낮은 이유는 테스트가 부족한 collector, UI, infra, security, AI, statistics 영역을 숨기지 않고 포함했기 때문이다.

## 하락 방지 기준

부동소수점 반올림으로 기준선이 불안정해지지 않도록 최초 값의 소수 둘째 자리 아래를 내림한 값으로 gate를 설정한다.

- core-v1: Line 78%, Branch 55%, Class 90%
- full-v1: Line 61%, Branch 42%, Class 74%

이 기준은 최종 목표가 아니라 시작점이다. Phase 1부터 전체 커버리지 하락 금지와 변경 코드 중심의 상향식 ratchet을 적용한다.

## 재현 명령

```bash
./gradlew clean check --no-daemon
```

예상 산출물:

```text
build/jacoco/test.exec
build/jacoco/integrationTest.exec
build/reports/jacoco/jacocoMergedReport/
build/reports/jacoco/jacocoFullMergedReport/
build/reports/tests/test/
build/reports/tests/integrationTest/
```

JaCoCo execution data는 `test.exec`, `integrationTest.exec` 두 파일만 사용한다. 과거 산출물과 이름이 겹친 `* 2.exec`, `* 2.class`는 측정 대상에 포함하지 않는다.

## CI

PR의 integration-test job에서 `clean check`를 실행한다. 테스트와 두 JaCoCo 보고서는 성공 여부와 관계없이 14일 동안 GitHub Actions artifact로 보존한다.

## 다음 단계에 포함하지 않은 항목

다음 항목은 Phase 0B에서 별도로 고정한다.

- MongoDB, Redis, WireMock image digest
- JVM heap, GC, CPU와 메모리 제한
- 결정적 fixture와 시간 기준
- 크롤러 Mongo·Redis command 계측
- 관리자 조회 command count와 query plan
- k6 workload, warm-up, 반복 순서
- 성능 raw JSON과 환경 manifest

Phase 0B가 끝나기 전에는 처리량, 지연시간 또는 개선율을 README와 이력서에 기록하지 않는다.
