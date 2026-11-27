# 테스트·커버리지 기준선

리팩터링 전후를 같은 범위로 비교하려고, 일부 패키지만 보던 커버리지 측정에 전체 코드 범위를 추가하고 CI에서 하락을 막는 기준을 걸었습니다. 이 문서의 수치는 성능 결과가 아니라 출발점입니다.

## 이전과 현재

| 항목 | 이전 | 현재 |
| --- | --- | --- |
| 측정 범위 | core-v1만 (collector·UI·infra 일부 제외) | core-v1 + 전체 프로덕션 코드 full-v1 |
| 전체 코드 Line 커버리지 | 보이지 않음 | 61.95% (5,282 / 8,526) |
| 커버리지 하락 | 막는 장치 없음 | CI `clean check`에서 기준 미달 시 실패 |
| 테스트·리포트 산출물 | 보존 안 함 | GitHub Actions artifact로 14일 보존 |

full-v1이 core-v1보다 낮은 것은 테스트가 적은 collector, UI, infra, security, AI, statistics 영역을 숨기지 않았기 때문입니다. 같은 full-v1 기준으로 [비밀번호 변경과 토큰 소유자 고정](./PHASE_2E_PASSWORD_CHANGE_SESSION_REVOCATION.md) 시점에는 Line 68.41%, Branch 50.52%가 됐습니다.

## 최초 측정

Java 17, JaCoCo 0.8.12에서 `clean` 뒤 측정했습니다. 단위 테스트 482개와 통합 테스트 27개(스킵 5개)가 모두 통과한 상태입니다.

| 범위 | Line | Branch | Class |
| --- | --- | --- | --- |
| core-v1 | 78.79% (3,184 / 4,041) | 55.52% (779 / 1,403) | 90.71% (166 / 183) |
| full-v1 | 61.95% (5,282 / 8,526) | 42.20% (1,125 / 2,666) | 74.72% (334 / 447) |
| collector 패키지 | 46.00% (328 / 713) | 26.98% (75 / 278) | - |

## 바꾼 내용

- core-v1은 기존 `jacocoMergedReport` 범위를 그대로 둬서 과거 수치와 이어 볼 수 있게 했습니다. 전체 서비스 커버리지는 아닙니다.
- full-v1은 `com/didimlog/**` 아래 프로덕션 클래스 전체를 포함하고, 이후 전후 비교의 기본 범위로 씁니다.
- 하락 방지 기준은 최초 값을 정수로 내림해 걸었습니다. 반올림 때문에 기준선이 흔들리지 않게 하려는 것입니다.

| 범위 | Line | Branch | Class |
| --- | --- | --- | --- |
| core-v1 | 78% | 55% | 90% |
| full-v1 | 61% | 42% | 74% |

- 측정 데이터는 `test.exec`, `integrationTest.exec` 두 파일만 씁니다. 이름이 겹친 예전 산출물(`* 2.exec`, `* 2.class`)은 넣지 않습니다.

## 확인한 방법

```bash
./gradlew clean check --no-daemon
```

리포트는 `build/reports/jacoco/jacocoMergedReport/`와 `build/reports/jacoco/jacocoFullMergedReport/`에 생깁니다.

## 남은 한계

- 기준은 최종 목표가 아니라 시작점입니다. 이후 작업마다 전체 커버리지가 내려가지 않게 하고, 바꾼 코드 중심으로 올립니다.
- 처리량과 지연 시간은 [성능 측정 환경](./PHASE_0B_PERFORMANCE_ENVIRONMENT.md)을 고정한 뒤에만 기록합니다.
