# 회고 studentId 인덱스 보장

`@Indexed`만 붙어 있고 실제로는 만들어지지 않던 회고 `studentId` 인덱스를, 앱 시작 때 직접 만들도록 바꿨습니다. 관리자 목록의 회고 수 집계가 회고 문서를 읽지 않고 인덱스만으로 끝납니다.

## 이전과 현재

| 페이지 크기 | 실행 계획 | 검사한 회고 문서 | 검사한 인덱스 키 | MongoDB 조회 |
| ---: | --- | ---: | ---: | ---: |
| 1 | `COLLSCAN` → `IXSCAN` | 12 → 0 | 0 → 1 | 2 → 2 |
| 5 | `COLLSCAN` → `IXSCAN` | 12 → 0 | 0 → 5 | 2 → 2 |
| 10 | `COLLSCAN` → `IXSCAN` | 12 → 0 | 0 → 10 | 2 → 2 |

로컬 MongoDB 7.0.16에 회원 12명, 회원별 회고 1건을 넣고 페이지 크기마다 새 컨테이너에서 5회 반복했습니다. 5회 결과가 모두 같았습니다. 12건 합성 데이터의 실행 계획이며 운영 응답 시간이 아닙니다.

[N+1 제거](./PHASE_1A_ADMIN_N_PLUS_ONE.md)와 합치면 10명 페이지 기준 조회가 11회에서 2회로 줄고, 남은 집계도 인덱스를 씁니다.

## 문제

`Retrospective.studentId`에는 `@Indexed`가 있었지만 `spring.data.mongodb.auto-index-creation`이 꺼져 있어, 새 MongoDB에서 앱을 띄워도 인덱스가 생기지 않았습니다. 그래서 N+1을 없앤 aggregation도 회고 컬렉션 전체를 검사했습니다.

## 바꾼 내용

자동 생성을 전역으로 켜면 다른 문서의 unique 인덱스까지 함께 만들어져, 기존 데이터 충돌과 쓰기 비용 변화가 생길 수 있습니다. 그래서 `MongoIndexInitializer`가 이 인덱스 하나만 멱등적으로 만듭니다.

```text
collection: retrospectives
key: { studentId: 1 }
unique: false, sparse: false
```

집계가 `studentId`만 쓰기 때문에 MongoDB가 인덱스만으로 결과를 만들고(`PROJECTION_COVERED`), 회고 본문은 읽지 않습니다.

## 확인한 방법

- 실제 인덱스 목록이 `_id_`, `studentId` 두 개뿐인지 확인합니다.
- 초기화를 여러 번 실행해도 인덱스가 하나이고, 이름이 다른 같은 키 인덱스는 재사용하는지 확인합니다.
- `explain("executionStats")`로 선택된 인덱스와 검사 문서 수를 확인합니다.
- `./gradlew clean check`가 통과했습니다(단위 483개, 통합 37개 중 30개 통과·조건부 7개 제외).

## 남은 한계

- 회원 조회는 아직 전체 회원을 읽은 뒤 메모리에서 검색·페이징합니다. [DB 페이징](./PHASE_1C_ADMIN_DB_PAGINATION.md)에서 고쳤습니다.
- 운영에 반영하려면 기존 인덱스 이름 충돌, `createIndex` 권한, 컬렉션 크기를 먼저 확인해야 합니다.
