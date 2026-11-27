# 문제 메타데이터 부분 갱신

문제 메타데이터를 저장하기 전에 기존 문서를 읽던 단계를 없애고, MongoDB `update` 한 번으로 필요한 필드만 갱신하도록 바꿨습니다.

## 이전과 현재

| 문제 6건 수집 | 이전 | 현재 |
| --- | ---: | ---: |
| `problems` MongoDB 명령 | 12회 | 6회 (50% 감소) |
| 저장 전 조회(`find`) | 6회 | 0회 |
| 저장(`update`) | 6회 | 6회 |
| solved.ac 호출 | 6회 | 6회 |
| 수집 결과 | - | 이전과 같음 |
| 기존 URL·언어·상세 본문 | 유지 | 유지 |

로컬 MongoDB 7.0.16·Redis 7.2.5에서 문서가 없는 경우와 상세까지 있는 경우를 각각 5번 반복해 셌습니다. 저장 구간의 MongoDB 명령 수이며, 수집 전체 속도가 50% 빨라졌다는 뜻은 아닙니다.

## 문제

메타데이터 수집은 solved.ac 응답 한 건마다 기존 문제를 조회하고, 값을 복사한 뒤 문서 전체를 저장했습니다.

```text
solved.ac 1회 → problems find 1회 → 복사 또는 새로 생성 → problems update 1회
```

메타데이터 수집에 필요한 것은 제목, 분류, 난이도, level, 태그뿐이라 저장 전 조회는 필요 없었습니다. 전체 저장은 상세 본문과 언어를 지우지 않으려고 먼저 읽어야 했던 것이고, 별도의 `ProblemService.syncProblem`은 새 객체를 그대로 저장해서 기존 상세를 지울 수 있었습니다.

## 바꾼 내용

`ProblemRepositoryCustom.upsertMetadata`가 `_id`로 문서를 찾아 한 번에 갱신합니다.

```text
$set:         title, category, difficulty, level, tags
$setOnInsert: url, language        (새 문서일 때만)
$unset:       difficultyLevel      (이전 난이도 필드 정리)
```

기존 문서의 URL, 언어, 상세 본문과 예제는 건드리지 않습니다. 동기 수집과 관리자 비동기 작업에 각각 있던 변환 코드를 `upsertProblemMetadata` 하나로 모았고, 호출 횟수, 항목별 성공·실패, 취소, checkpoint, 요청 간격은 그대로입니다.

## 확인한 방법

- 이전·현재 코드에서 같은 측정 스크립트를 돌려 공개 메서드 `collectMetadataAsync` 실행 중 `problems` 명령만 셌습니다. Redis 명령은 `INFO commandstats`의 전후 차이로 봤고 같았습니다.
- 상세까지 있는 데이터에는 서로 다른 URL, 언어, 이전·현재 상세 필드를 넣고 수집 뒤 모두 그대로인지, 이전 난이도 필드만 지워졌는지 확인했습니다.
- 수집 결과를 문제 ID로 정렬해 비교했고 5번 모두 이전과 같았습니다.
- 전용 MongoDB·Redis에 연결한 `./gradlew clean check`가 통과했습니다(단위 680개, 통합 161개 중 153개 통과·조건부 8개 제외).

```bash
performance/crawler/run-baseline.sh
```

## 남은 한계

- 실제 solved.ac 네트워크 대기와 요청 간격을 뺀 로컬 비교입니다.
- 문제당 `update` 1회는 남습니다. bulk write로 더 줄일 수 있지만 항목별 실패, 취소, checkpoint 의미가 바뀌어 적용하지 않았습니다.
- 상세·언어 작업의 전체 저장이 새 메타데이터를 되돌리는 문제는 [문제 상세·언어 부분 갱신](./PHASE_5B_CRAWLER_DETAIL_PARTIAL_UPDATE.md)에서 고쳤습니다.
- URL이나 언어가 빠진 기존 문서는 `$setOnInsert`로 채워지지 않습니다.
