# Phase 6I — 관리자 피드백 작성자 일괄 조회

## 문제

관리자 피드백 목록은 현재 페이지를 조회한 뒤 각 피드백의 작성자를 다시
조회했다.

```kotlin
feedbacks.map { feedback ->
    studentRepository.findById(feedback.writerId)
}
```

기본 페이지 크기 20에서 피드백 count와 페이지 조회 2회에 학생 조회 20회가
더해졌다. 같은 작성자의 피드백이 여러 건이어도 행마다 조회했으며, 학생 문서의
비밀번호와 풀이 기록처럼 응답에 쓰지 않는 필드도 함께 읽었다.

## 변경

현재 페이지의 `writerId`를 중복 없이 모아 학생의 `_id`와 `bojId`만 한 번에
조회한다.

```text
feedback page
→ writerId 중복 제거
→ students._id IN writerIds
→ projection: _id, bojId
→ writerId 기준으로 기존 Page에 결합
```

학생 조회 결과에 없는 작성자도 피드백 행과 `writerId`를 유지하며
`bojId=null`로 응답한다. 학생은 존재하지만 BOJ 연동을 하지 않은 경우도 같은
응답 계약을 유지한다. 피드백 상태 변경 API의 단건 학생 조회는 N+1이 아니므로
이번 범위에서 바꾸지 않았다.

## 비교 조건

- Before 참고 코드 SHA:
  `603e30639f0a07c8f78c20dd7f79802828921404`
- After 코드 SHA:
  `043d5bba26523fd026cc52a0c1f9c5fb331fbb7a`
- 계측 테스트 SHA-256:
  `9e30ff31ecc079d59ddbb397e69a782e3eaf4c688514cecdf13b5e1cdfad2075`
- MongoDB:
  `mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a`
- 피드백 21건, 첫 페이지의 작성자 20명은 모두 서로 다른 ID
- 첫 페이지 작성자 중 학생 문서 19건
  - BOJ ID가 있는 학생 18명
  - BOJ ID가 없는 학생 1명
  - 삭제되어 학생 문서가 없는 작성자 1명
- 정렬: `createdAt DESC`
- 페이지 크기: 1, 5, 20

Before 참고 SHA의 행별 `findById` 흐름은 계측 테스트의
`legacyFeedbackPage`에 그대로 고정했다. 같은 테스트와 같은 fixture에서 이
흐름과 실제 After 컨트롤러를 차례로 호출하고, 호출 사이마다
`CommandListener`를 초기화했다. 따라서 표는 두 알고리즘의 MongoDB 읽기 명령
비교이며 서로 다른 프로세스의 응답 시간 비교가 아니다.

## 측정 결과

| 페이지 크기 | Before 학생 `find` | After 학생 `find` | Before 전체 읽기 | After 전체 읽기 | 전체 감소율 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 1 | 3 | 3 | 0.00% |
| 5 | 5 | 1 | 7 | 3 | 57.14% |
| 20 | 20 | 1 | 22 | 3 | 86.36% |

전체 읽기는 피드백 count `aggregate` 1회, 피드백 페이지 `find` 1회와 학생
읽기를 합한 값이다. 기본 페이지 크기 20에서 학생 조회만 보면 20회에서 1회로
95.00% 줄었다.

감소율은 `(Before - After) / Before × 100`으로 계산했다. 이는 고정 fixture의
MongoDB 읽기 명령 수 감소율이며 응답 시간, DB CPU 또는 운영 처리량 개선율이
아니다.

## 쿼리와 실행 계획

After 학생 조회는 다음 조건과 projection을 사용한다.

```text
filter:     { _id: { $in: [현재 페이지 작성자 ID] } }
projection: { _id: 1, bojId: 1 }
```

페이지 크기 20의 실제 `explain("executionStats")` 결과는 다음과 같다.

- Access plan: `IXSCAN`
- 선택 인덱스: `_id_`
- 요청 작성자 ID: 20개
- 반환 학생 문서: 19개
- 검사 학생 문서: 19개
- blocking sort: 없음

전체 `Student`를 일괄 materialize하는 `findAllById` 대신 전용 projection을
사용했으므로 목록 응답에 필요하지 않은 필드를 읽지 않는다. 전송 BSON 크기는
이번 단계에서 별도로 계측하지 않아 감소율로 쓰지 않았다.

## 응답 정합성

이전 매핑을 테스트 내부에 별도로 고정하고 After 응답과 모든 필드를 비교했다.

| 페이지 크기 | 전후 공통 응답 SHA-256 |
| ---: | --- |
| 1 | `537b25f329a1c8f14bdceb43651b6cb897e14a98aefcb98f7c0598fed33a2358` |
| 5 | `11ccb280c5c6e8d2e09cd67e5209d74aff6c9bd0c2d3e36030a9430a41cca95a` |
| 20 | `fcc419cef50804631fd79b1aaf9cf1821a6e5ebdebd41c965c3e2b07c13dc6f1` |

SHA 입력에는 페이지 번호·크기·전체 건수·전체 페이지 수와 각 피드백의
`id`, `writerId`, `bojId`, 본문, 유형, 상태, 생성·수정 시각을 포함했다.

추가로 다음을 확인했다.

- `createdAt DESC` 응답 순서 유지
- 삭제된 작성자의 피드백과 원래 `writerId` 유지, `bojId=null`
- BOJ 미연동 학생의 `bojId=null`
- 같은 작성자의 여러 피드백은 작성자 ID를 한 번만 전달
- 빈 페이지는 `students` 읽기 명령 0회
- 학생 batch 쿼리는 `_id`, `bojId`만 projection
- 피드백 상태 변경 API의 기존 응답 유지

## 전체 검증과 커버리지

전용 MongoDB와 Redis에 연결해 `clean check`를 실행했다.

| 범위 | 결과 |
| --- | ---: |
| 단위 테스트 | 736개 통과 |
| 통합 테스트 | 217개 중 208개 통과, 조건부 9개 제외 |
| core-v1 Line / Branch / Method | 88.99% / 66.78% / 87.65% |
| full-v1 Line / Branch / Method | 77.87% / 59.23% / 78.03% |

작은 커버리지 변화는 성능 성과로 해석하지 않는다. 목록 응답과 실제 MongoDB
명령 수를 고정하는 회귀 테스트가 추가됐다는 검증으로만 기록한다.

## 남은 범위

- 피드백 count와 페이지 조회 2회는 유지된다.
- `createdAt DESC` 정렬의 인덱스와 동률 순서 기준은 별도 query plan 측정이
  필요하다.
- 관리자 목록 API의 최대 페이지 크기는 별도 정책 단계에서 일관되게 정해야
  한다.
- 지연 시간, DB CPU, 전송 BSON과 운영 처리량은 측정하지 않았다.
