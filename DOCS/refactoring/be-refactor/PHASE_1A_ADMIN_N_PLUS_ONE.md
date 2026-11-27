# 관리자 회원 조회 N+1 제거

관리자 회원 목록에서 회원마다 회고를 따로 조회하던 것을, 현재 페이지 회원의 회고 수를 aggregation 한 번으로 세도록 바꿨습니다. 페이지 크기와 관계없이 MongoDB 조회가 2회로 고정됩니다.

## 이전과 현재

| 페이지 크기 | 이전 MongoDB 조회 | 현재 MongoDB 조회 | 감소 |
| ---: | ---: | ---: | ---: |
| 1 | 2 (`find` 1 + `find` 1) | 2 (`find` 1 + `aggregate` 1) | 0% |
| 5 | 6 (`find` 1 + `find` 5) | 2 | 66.7% |
| 10 | 11 (`find` 1 + `find` 10) | 2 | 81.8% |
| 검색 결과 없음 | - | 1 (회고 집계 생략) | - |

로컬 MongoDB 7.0.16에 회원 12명, 회원별 회고 1건을 넣고 셌습니다. 감소율은 MongoDB 조회 횟수이며 응답 시간이나 처리량이 아닙니다. 현재 값에는 `aggregate`도 조회로 셌습니다.

## 문제

목록의 회원마다 다음 조회를 반복해, 회고 수를 세려고 회고 문서를 전부 가져왔습니다.

```kotlin
retrospectiveRepository.findAllByStudentId(studentId).size
```

## 바꾼 내용

```text
$match studentId in 현재 페이지 회원 ID
→ $group by studentId
→ $sum retrospectiveCount
```

결과를 `studentId` Map으로 바꿔 원래 회원 순서에 붙입니다. 회고가 없거나 ID가 없는 회원은 이전처럼 0이고, 빈 페이지에서는 aggregation을 실행하지 않습니다.

## 확인한 방법

- 회고가 0·1·2건인 회원을 섞어 회원별 값이 맞는지, 페이지 밖 회원의 회고가 빠지는지 확인합니다.
- aggregation 결과 순서와 관계없이 회원 응답 순서가 유지되는지 확인합니다.
- 기존 단건 `findAllByStudentId` 호출이 0회인지 확인합니다.
- `./gradlew clean check`가 통과했습니다(단위 482개 → 483개, 통합 32개 → 34개, 조건부 7개 제외).

## 남은 한계

- `studentId` 인덱스가 없어 aggregation 한 번이 회고 문서 12개를 모두 검사했습니다(`COLLSCAN`). [회고 studentId 인덱스](./PHASE_1B_RETROSPECTIVE_STUDENT_INDEX.md)에서 고쳤습니다.
- 회원 전체를 읽고 메모리에서 검색·페이징하는 문제는 [DB 페이징](./PHASE_1C_ADMIN_DB_PAGINATION.md)에서 고쳤습니다.
