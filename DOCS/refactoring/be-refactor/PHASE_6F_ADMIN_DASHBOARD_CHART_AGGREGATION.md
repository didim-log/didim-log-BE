# Phase 6F — 관리자 대시보드 차트 집계

## 문제

관리자 차트는 회원과 회고 전체 문서를 조회한 뒤 애플리케이션에서 날짜별로
묶었다. 회원 차트에는 `createdAt`만 필요하지만 비밀번호와 풀이 배열까지 포함한
`Student`를 읽었고, 회고 차트에는 `createdAt`만 필요하지만 최대 5,000자인 본문도
함께 읽었다.

해결 문제 카드와 차트에는 정합성 문제도 있었다.

- 카드 집계가 `solutions.solutions.problemId.value`를 사용했지만 실제 MongoDB
  저장 경로는 `solutions.items.problemId`다. 성공 풀이가 있어도 카드가 0을
  반환할 수 있었다.
- 차트는 같은 문제를 기간 안에서만 중복 제거했다. 같은 문제를 다른 날짜에 다시
  성공하면 누적값에 여러 번 반영됐다.
- 주 번호는 ISO 기준을 사용하면서 연도는 달력 연도를 사용했다. 예를 들어
  `2018-12-31`을 `2018-W01`로 표시했다.

## 집계 기준

기존 API 형태와 누적 차트 방식은 유지하고 각 지표의 기준을 다음처럼 고정했다.

| 데이터 | 기간별 증가값 | 누적 최종값 |
| --- | --- | --- |
| `USER` | 해당 기간에 가입한 회원 수 | 전체 회원 수 |
| `SOLUTION` | 문제별 최초 성공 시점에 배정된 고유 문제 수 | 전체 회원의 성공 풀이 중 고유 `problemId` 수 |
| `RETROSPECTIVE` | 해당 기간에 작성된 회고 문서 수 | 전체 회고 수 |

`SOLUTION`은 사람별 성공 횟수나 제출 횟수가 아니다. 플랫폼 전체에서 같은
`problemId`는 한 번만 세며, 차트에는 가장 이른 성공 시점에 반영한다. 이 기준으로
카드와 차트의 마지막 값이 같아진다.

## 변경

### 해결 문제 카드 저장 경로

실제 저장 문서에 맞춰 집계 경로를 바꿨다.

```text
Before
solutions.solutions
solutions.solutions.result
solutions.solutions.problemId.value

After
solutions.items
solutions.items.result
solutions.items.problemId
```

도메인 객체를 모킹한 단위 테스트만으로는 저장 경로 오류를 찾을 수 없어 실제
MongoDB에 `Student`를 저장한 뒤 카드 값을 확인하는 통합 테스트를 추가했다.

### 기간별 MongoDB 집계

세 차트 모두 MongoDB가 작은 기간 bucket만 반환하도록 바꿨다.

```text
USER
createdAt 형식 변환
→ 기간별 count
→ 날짜 정렬

SOLUTION
solutions.items unwind
→ SUCCESS 필터
→ problemId별 min(solvedAt)
→ 최초 성공 시점의 기간별 count
→ 날짜 정렬

RETROSPECTIVE
createdAt 형식 변환
→ 기간별 count
→ 날짜 정렬
```

애플리케이션은 정렬된 bucket의 count를 누적값으로 바꾸는 일만 담당한다.

### 시간대와 ISO 주차

MongoDB의 `$dateToString`에 애플리케이션 JVM과 같은 시간대를 전달한다. 로컬
계측 환경은 `Asia/Seoul`이다.

```text
DAILY   %Y-%m-%d
WEEKLY  %G-W%V
MONTHLY %Y-%m
```

주별 키는 ISO week-based year인 `%G`와 ISO week number인 `%V`를 함께 사용한다.
과거 회원 문서에 `createdAt`이 없으면 기존 `Student` 읽기 동작처럼 조회 시점의
`$$NOW`에 포함한다.

## 비교 조건

- 비교 대상 코드 SHA: `69223233cb45ede7451728490cf8e2dfe1b13674`
- 계측 실행 SHA: `061d96e34eddcda55db36e9391d1806636e8a9c1`
- 계측 실행 시 `gitDirty=false`
- Harness SHA-256:
  `43c4c9d306aeb178266fc3b37a2007b3ca8f1382441cc7fd4e26e50684d26e18`
- MongoDB:
  `mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a`
- 회원 240명
- 회원별 풀이 24건
- 공유 문제 120개를 서로 다른 학생과 첫 달·마지막 달에 반복 성공
- 회고 1,200건
- 회고 본문 한 건당 4,096자
- 2022-01부터 24개월
- 기간: `MONTHLY`
- 시간대: `Asia/Seoul`

계측 테스트는 public `AdminDashboardChartService`를 호출해 실제 `find` 또는
`aggregate` 명령을 캡처한다. 캡처한 filter와 pipeline을
`RawBsonDocument`로 다시 실행해 반환 문서 수와 논리 BSON 크기를 합산했다.
Before 반환량은 별도 Before 바이너리를 실행한 값이 아니다. 계측 실행 SHA의
harness가 비교 대상 SHA의 Repository `findAll()` 계산을 같은 fixture에서
재현하고, 그 실제 MongoDB 명령과 반환 문서를 캡처한 값이다.

USER와 RETROSPECTIVE는 이전 계산, 현재 서비스와 raw 집계 재생 결과 hash가
같다. SOLUTION은 알려진 중복 집계 오류를 바로잡았으므로 이전 결과와 같다고
주장하지 않는다. 대신 전역 고유 문제의 최초 성공 시점을 애플리케이션에서 별도로
계산한 기준 결과와 현재 서비스, raw 집계 재생 결과가 같은지 확인했다.

## 측정 결과

| 시나리오 | 항목 | Before | After | 변화 |
| --- | --- | ---: | ---: | ---: |
| 회원 월별 차트 | 반환 문서 | 240건 | 24건 | 90.00% 감소 |
| 회원 월별 차트 | 반환 논리 BSON | 635,160 B | 792 B | 99.88% 감소 |
| 해결 문제 월별 차트 | 반환 문서 | 240건 | 24건 | 90.00% 감소 |
| 해결 문제 월별 차트 | 반환 논리 BSON | 635,160 B | 792 B | 99.88% 감소 |
| 회고 월별 차트 | 반환 문서 | 1,200건 | 24건 | 98.00% 감소 |
| 회고 월별 차트 | 반환 논리 BSON | 5,176,340 B | 792 B | 99.98% 감소 |

명령은 세 Before 시나리오 모두 `find 1 + getMore 1`, After는
`aggregate 1 + getMore 0`이었다. 이는 이 fixture에서 관찰한 MongoDB 명령 수이며
일반적인 네트워크 round trip 수로 확장해 해석하지 않는다.

기능 결과 SHA-256은 다음과 같다.

| 시나리오 | 이전 계산 | 현재 정책 기준 | 현재 서비스·raw 재생 |
| --- | --- | --- | --- |
| USER | `319ef559c4f5b3f8ae2401b59ea74a771369d40269b1f4f64086f75fe3809b1c` | 동일 | 동일 |
| SOLUTION | `9aa9bc334eb58b87efc85d7979c1a819e23fd3fc0ee39852794f002ee16b379d` | `8779d27bcc2f0dacce5489080bb3ef8f0afb570ce6cfd84ff59ecfd9932db142` | 현재 정책 기준과 동일 |
| RETROSPECTIVE | `484c66e101b3096a6ea4a90d49859c35484e0a9900b3099ba541e969bf1e329c` | 동일 | 동일 |

99.88%와 99.98%는 고정 fixture에서 MongoDB가 반환한 raw 결과 문서의 BSON
크기 합계 감소율이다. wire protocol 헤더, 압축, TLS, JVM heap, endpoint 응답
시간이나 운영 처리량의 개선율을 뜻하지 않는다.

## 정합성 검증

변경 전 실제 MongoDB 통합 테스트 5개 중 4개가 다음 값으로 실패했다.

| 항목 | 기대 | 변경 전 실제 |
| --- | ---: | ---: |
| 해결 문제 카드 | 4 | 0 |
| 해결 문제 일별 차트 마지막 값 | 4 | 6 |
| `2018-12-31` 주차 | `2019-W01` | `2018-W01` |
| USER 차트 조회 명령 | `find 0, aggregate 1` | `find 1, aggregate 0` |

변경 후에는 다음을 실제 MongoDB 7.0.16에서 확인했다.

- 실제 저장 문서의 `solutions.items.problemId`로 카드 값 계산
- 여러 학생과 여러 날짜의 같은 성공 문제를 최초 성공 시점에 한 번만 반영
- DAILY, WEEKLY, MONTHLY 차트 마지막 값과 해결 문제 카드 값 일치
- `2018-12-31 → 2019-W01`
- `2021-01-01 → 2020-W53`
- 회원과 회고 문서 수 누적
- 다른 학생의 같은 회고 문제 ID도 별도 회고 문서로 집계
- `createdAt`이 없는 이전 회원을 조회 시점에 포함
- 각 차트에서 `find 0, aggregate 1`

## 전체 검증과 커버리지

전용 MongoDB와 Redis에 연결해 `clean check`를 실행했다.

| 범위 | 결과 |
| --- | ---: |
| 단위 테스트 | 731개 통과 |
| 통합 테스트 | 205개 중 196개 통과, 조건부 9개 제외 |
| core-v1 Line / Branch / Class | 88.50% / 66.30% / 94.83% |
| full-v1 Line / Branch / Class | 77.07% / 57.98% / 82.59% |
| `AdminDashboardChartService` Line / Branch | 100% / 100% |

변경 전 merged report에서 차트 서비스는 실행 대상 69줄 중 65줄이 실행되지
않았다. 이번 단계에서 실제 MongoDB 통합 테스트 6개를 추가했고 변경 후 서비스의
60줄과 8개 분기가 모두 실행됐다. 리팩터링으로 실행 대상 줄 수가 함께 바뀌었으므로
이를 응답 성능 향상률로 환산하지 않는다.

## 재현

정합성 테스트:

```bash
SPRING_DATA_MONGODB_URI=mongodb://127.0.0.1:27218/didimlog-test \
TEST_MONGO_PORT=27218 \
./gradlew integrationTest \
  --tests 'com.didimlog.application.admin.AdminDashboardChartIntegrationTest'
```

반환량 계측:

```bash
SPRING_DATA_MONGODB_URI=mongodb://127.0.0.1:27218/didimlog-test \
TEST_MONGO_PORT=27218 \
ADMIN_DASHBOARD_CHART_BASELINE_ENABLED=true \
ADMIN_DASHBOARD_CHART_BASELINE_OUTPUT_DIR=/tmp/admin-dashboard-chart \
./gradlew integrationTest \
  --tests 'com.didimlog.application.admin.AdminDashboardChartBaselineIntegrationTest' \
  --rerun-tasks
```

원시 JSON은 지정한 출력 경로의
`admin-dashboard-chart-baseline.json`에 생성한다. 결과 파일은 Git에 포함하지
않는다.

## 남은 제한

- 전체 기간 누적값이 필요하므로 MongoDB는 대상 컬렉션과 풀이 배열을 계속
  검사한다. 이번 단계는 반환 데이터와 애플리케이션 객체 생성을 줄인 작업이며
  `totalDocsExamined` 감소를 주장하지 않는다.
- 전체 기간 집계에 유리한 인덱스가 확인되지 않아 새 인덱스를 추가하지 않았다.
- 고유 해결 문제 수가 크게 늘면 MongoDB group의 메모리와 임시 디스크 사용량을
  별도로 확인해야 한다.
- `todaySignups`와 `todayRetrospectives` 카드는 현재 FE 계약에서 각각 전체 USER,
  RETROSPECTIVE 누적 차트를 연다. 오늘만의 시계열을 분리하는 API·FE 변경은 이번
  단계에 포함하지 않았다.
- 응답 시간, 처리량, JVM heap과 운영 데이터 분포는 측정하지 않았다.
- EC2 배포와 운영 MongoDB 실행 계획 확인은 범위에서 제외했다.
