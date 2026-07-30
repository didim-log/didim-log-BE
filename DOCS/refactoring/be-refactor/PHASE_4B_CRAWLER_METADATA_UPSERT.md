# Phase 4B — 문제 메타데이터 부분 갱신

## 문제

문제 메타데이터 수집은 solved.ac 응답 한 건마다 MongoDB에서 기존 문제를 먼저
조회한 뒤 전체 문서를 저장했다.

```text
solved.ac 1회
→ problems find 1회
→ 기존 문서 copy 또는 신규 Problem 생성
→ problems update 1회
```

문제 6건을 처리하면 `problems` collection에 `find=6`, `update=6`이 발생했다.
메타데이터 수집에 필요한 것은 제목, 대표 분류, 난이도, level과 tags의 갱신 여부뿐이므로
저장 전 조회는 없앨 수 있었다.

기존 전체 문서 저장은 상세 본문과 언어를 보존하려고 먼저 읽어야 했다.
별도의 `ProblemService.syncProblem`은 새 `Problem`을 그대로 저장해 기존 상세를
지울 수 있어 같은 메타데이터 저장 규칙을 사용하도록 맞출 필요도 있었다.

## 변경

### 메타데이터 부분 upsert

`ProblemRepositoryCustom.upsertMetadata`가 `_id`로 문서를 찾고 MongoDB
`update` 한 번으로 다음 필드만 갱신한다.

```text
$set:
  title
  category
  difficulty
  level
  tags

$setOnInsert:
  url
  language

$unset:
  difficultyLevel
```

`url`과 `language`는 신규 문서에만 넣는다. 기존 문서의 사용자 지정 URL, 판별된
언어와 다음 상세 필드는 update 대상에서 제외했다.

```text
description
inputDescription
outputDescription
examples
descriptionHtml
inputDescriptionHtml
outputDescriptionHtml
sampleInputs
sampleOutputs
```

레거시 `difficultyLevel`은 부분 갱신에 그대로 남으면 현재 `level`과 다른 값으로
검색될 수 있어 함께 제거한다.

### 동기·비동기 수집 경로 통합

동기 수집과 관리자 비동기 작업에 중복돼 있던 solved.ac 응답 변환을
`upsertProblemMetadata`로 모았다. 다음 동작은 바꾸지 않았다.

- 문제별 solved.ac 호출
- 항목별 성공·실패 분리
- 취소 확인
- 처리 수, 성공 수, 실패 수와 checkpoint 갱신
- production pacing
- Redis 작업 상태 저장

반환 문서가 필요 없는 메타데이터 동기화만 부분 upsert를 사용한다. DB miss 뒤
문제를 반환해야 하는 read-through 생성 경로는 기존 저장 방식을 유지했다.

## 비교 조건

- Before SHA: `ed9854256ef808abe6e953e572b42d8d211cbb02`
- After SHA: `e04db35b8cf2d3dbb7b6fbd2f9410f16675abf5d`
- Before·After `gitDirty`: 모두 `false`
- Harness SHA-256:
  `7b680199c4ed2c96873c127456c9f449533f6b930c3b83b17aafc389e04a4197`
- MongoDB:
  `mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a`
- Redis:
  `redis:7.2.5-alpine@sha256:6aaf3f5e6bc8a592fbfe2cccf19eb36d27c39d12dab4f4b01556b7449e7b1f44`
- `portfolio-fixture` 문제: 1000~1005, 6건
- 작업 실행: inline executor
- pacing: 기준선에서만 no-op
- 시나리오: 문서가 없는 cold, 상세 문서가 있는 warm
- 각 SHA에서 상태를 정리하며 cold·warm을 5회 반복

runner는 SHA와 dirty 상태, harness SHA-256, 이미지 digest와 예상 명령 수를 각
JSON에 기록한다. 계측은 public `collectMetadataAsync` 호출 동안 대상 database의
`problems` collection에서 시작된 MongoDB 명령만 센다. Redis 명령은 격리
Redis의 `INFO commandstats` 전후 차이로 계산한다.

warm fixture에는 서로 다른 URL과 언어, 구형·현재 상세 필드와 샘플을 넣었다.
수집 뒤 모든 값이 유지되고 레거시 `difficultyLevel`만 제거되는지 확인했다.
cold fixture는 raw MongoDB 문서에 `language=ko`가 실제 저장됐는지도 확인했다.

기능 결과는 문제 ID로 정렬한 뒤 전체 `Problem` 필드를 SHA-256으로 비교했다.
Before와 After의 시나리오별 hash는 5회 모두 같았다.

```text
cold:
bdaca886ba6b22eb0cc5a28a7ab36549cad87fad26c50c22cfc60b78eaf5216e

warm:
aba92ba659fe41f5af6b5150341c04b6514c06c7527bb2637af80eb4516054db
```

## 측정 결과

| 시나리오 | 항목 | Before | After | 변화 |
| --- | --- | ---: | ---: | ---: |
| cold 6건 | `problems` MongoDB 명령 | 12 | 6 | 50.00% 감소 |
| cold 6건 | `find` / `update` | 6 / 6 | 0 / 6 | 사전 조회 제거 |
| warm 6건 | `problems` MongoDB 명령 | 12 | 6 | 50.00% 감소 |
| warm 6건 | `find` / `update` | 6 / 6 | 0 / 6 | 사전 조회 제거 |
| cold·warm | Redis `get` / `setex` / `zadd` | 15 / 9 / 9 | 15 / 9 / 9 | 동일 |
| cold·warm | solved.ac fixture 호출 | 6 | 6 | 동일 |

50.00%는 문제 6건의 메타데이터 저장 구간에서 `problems` collection에 발생한
MongoDB 명령 수의 감소율이다. 크롤러 전체 응답 시간, 처리량이나 운영 성능이
50% 좋아졌다는 뜻이 아니다.

MongoDB의 modifier upsert도 wire command에서는 `update`로 기록된다. 이 단계는
항목별 실패와 checkpoint 의미를 유지했으므로 여전히 문제당 update 1회와
solved.ac 호출 1회가 남는다.

## 검증

- 신규 문서의 ID, 제목, 대표 분류, 난이도, level, tags, URL과 언어
- 같은 ID를 두 번 upsert해도 문서 1건 유지
- 기존 URL과 언어 보존
- 구형·현재 상세 및 샘플 필드 보존
- 현재 category와 difficulty의 실제 MongoDB 문자열 형식
- 레거시 `difficultyLevel` 제거
- 동기·비동기 수집의 같은 메타데이터 변환
- 기존 작업 상태, checkpoint, 재시도와 외부 호출 횟수 유지
- Before·After 기능 결과 hash 일치

전체 검증 결과는 다음과 같다.

| 범위 | 결과 |
| --- | ---: |
| 단위 테스트 | 680개 통과 |
| 통합 테스트 | 161개 중 153개 통과, 8개 조건부 제외 |
| core-v1 Line / Branch / Class | 85.33% / 63.67% / 93.48% |
| full-v1 Line / Branch / Class | 71.86% / 53.90% / 80.20% |

Phase 4A와 비교해 일반 통합 테스트 2개가 늘었다. JaCoCo 변화는 작아 별도 성과로
해석하지 않는다. 조건부 제외 8개에는 크롤러 기준선 2개와 통계 기준선 1개가
포함되며 각 runner가 별도 환경에서 활성화한다.

## 재현

저장소 root에서 비교 SHA별 detached worktree를 만든 뒤 실행한다.

Before:

```bash
git worktree add --detach ../didim-log-BE-phase4b-before \
  ed9854256ef808abe6e953e572b42d8d211cbb02

cd ../didim-log-BE-phase4b-before
CRAWLER_BASELINE_RUN_ID=crawler-phase4b-before-ed98542 \
  CRAWLER_BASELINE_EXPECTED_FIND_COUNT=6 \
  CRAWLER_MONGO_PORT=27220 \
  CRAWLER_REDIS_PORT=6399 \
  performance/crawler/run-baseline.sh
```

After:

```bash
git worktree add --detach ../didim-log-BE-phase4b-after \
  e04db35b8cf2d3dbb7b6fbd2f9410f16675abf5d

cd ../didim-log-BE-phase4b-after
CRAWLER_BASELINE_RUN_ID=crawler-phase4b-after-e04db35 \
  CRAWLER_BASELINE_EXPECTED_FIND_COUNT=0 \
  CRAWLER_MONGO_PORT=27220 \
  CRAWLER_REDIS_PORT=6399 \
  performance/crawler/run-baseline.sh
```

원시 JSON은 다음 경로에 생성되며 Git에는 포함하지 않는다.

```text
performance/results/crawler-phase4b-before-ed98542/
performance/results/crawler-phase4b-after-e04db35/
```

## 남은 제한

- 실제 solved.ac 네트워크 대기와 production pacing을 제외한 로컬 합성
  fixture의 명령 수 비교다.
- 처리량 값은 6건 smoke의 JIT와 로컬 환경 노이즈가 커 개선 주장에 사용하지
  않는다.
- 상세·refresh·언어 작업은 미리 읽은 `Problem` 전체를 저장한다. 이 작업과
  메타데이터 수집이 동시에 실행되면 오래된 전체 문서가 새 메타데이터를 되돌릴
  가능성은 후속 정합성 단계에 남아 있다.
- 기존 문서에 URL이나 언어 필드가 없는 경우 `$setOnInsert`로 채워지지 않는다.
  운영 반영 전 누락 문서 수를 확인하고 별도 보정 여부를 결정해야 한다.
- bulk write는 update 명령을 더 줄일 수 있지만 항목별 실패 격리, 취소,
  checkpoint와 pacing 의미가 달라져 이번 단계에서 적용하지 않았다.
- EC2 배포와 운영 데이터 확인은 범위에서 제외했다.
