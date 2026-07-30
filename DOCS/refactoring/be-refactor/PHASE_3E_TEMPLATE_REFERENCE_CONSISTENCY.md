# Phase 3E — 기본 템플릿 참조 정합성

## 문제

기본값으로 지정한 커스텀 템플릿을 삭제하면 `templates` 문서는 없어지지만
`Student.defaultSuccessTemplateId` 또는 `defaultFailTemplateId`에는 삭제한 ID가
남았다.

이 상태에서는 API마다 결과가 달랐다.

- 기본 템플릿 조회는 존재하지 않는 ID를 감지해 시스템 템플릿으로 대체
- 템플릿 목록은 `Student`의 non-null ID를 그대로 사용해 기본 템플릿을 표시하지 않음

계정 삭제에도 같은 문제가 있었다. 커스텀 템플릿을 일괄 삭제한 뒤 로그나 Student
삭제가 실패하면 재시도를 위해 Student는 남지만 기본값은 이미 삭제된 템플릿을
가리켰다.

## 변경 범위

### 개별 템플릿 삭제

같은 학생 생명주기 잠금 안에서 다음 순서로 처리한다.

1. Student와 삭제할 Template을 다시 조회
2. 시스템 Template과 다른 사용자 Template 거부
3. 삭제할 ID를 사용하는 SUCCESS·FAIL 카테고리 계산
4. 일치하는 Student 필드만 부분 갱신으로 해제
5. Template 삭제

참조 해제는 다음 조건으로 실행한다.

```text
query:
  _id = studentId
  selected default field = expectedTemplateId

update:
  $unset selected default field
  $inc documentVersion 1

options:
  returnNew = true
  upsert = false
```

두 카테고리가 같은 Template을 사용하면 한 명령에서 두 필드를 해제한다. 다른
카테고리의 기본값은 건드리지 않는다. 조건이 맞지 않으면 Template을 삭제하지 않고
`409 SESSION_STATE_CONFLICT`를 반환한다.

참조 해제를 먼저 실행하므로 뒤의 Template 삭제가 실패해도 Student가 존재하지 않는
Template을 가리키는 상태는 만들지 않는다. 이때 Template은 남지만 더 이상 기본값은
아니다.

### 계정 삭제

계정 삭제는 Student의 기본값 ID를 최대 두 개 확인한다.

- SYSTEM Template이면 삭제 대상이 아니므로 참조 유지
- CUSTOM Template 또는 이미 존재하지 않는 ID면 참조 해제
- 이후 사용자 Template 일괄 삭제

따라서 Template 삭제 뒤 로그 삭제가 실패해 Student가 남더라도 삭제된 CUSTOM
Template 참조는 남지 않는다. 시스템 기본값 선택은 보존한다.

### 목록과 기본값 조회의 판정 통일

템플릿 목록과 요약 목록은 현재 목록에 실제로 포함된 ID만 사용자 기본값으로
인정한다. 저장된 ID가 없거나 다른 사용자의 Template이면 기존 기본값 조회와 같은
시스템 fallback ID를 사용한다.

과거 데이터에 이미 깨진 ID가 있어도 목록과 `/templates/default`가 서로 다른
Template을 기본값으로 표시하지 않는다.

### 소유권 오류 응답

다른 사용자의 커스텀 Template 수정·삭제·기본값 설정과 시스템 Template 수정은
`ACCESS_DENIED`로 통일했다. API 문서의 `403`과 실제 응답이 같아졌다.

## 검증

단위 테스트에서 다음을 확인했다.

- 비기본 Template 삭제 시 Student 갱신 없음
- 같은 Template을 SUCCESS·FAIL 기본값으로 사용할 때 두 참조를 먼저 해제
- 참조 조건 불일치 시 `409`, Template 삭제 0건
- 다른 사용자 Template 수정·삭제·기본값 설정 시 `403`
- Template 수정 접근 거부와 삭제 충돌의 실제 HTTP 응답이 각각 `403`, `409`
- 계정 삭제 중간 실패 전에 CUSTOM 참조 해제
- 계정 삭제 중 SYSTEM 참조 보존
- 목록에 없는 ID를 시스템 기본값 ID로 해석

실제 MongoDB 7.0.16과 Redis 7.2.5에서는 다음을 확인했다.

- 기본 Template 삭제 뒤 Student의 SUCCESS·FAIL 참조 0건
- 참조 해제 뒤 Template 저장소 삭제 실패 시 Template은 남고 Student 참조는 0건
- 기본값 설정이 잠금을 가진 동안 삭제 요청은 `409`
- 설정 완료 뒤 삭제 재시도 시 Template과 Student 참조 모두 제거
- 계정 삭제에서 CUSTOM Template 삭제 뒤 로그 삭제 실패 시 Student는 남지만
  CUSTOM 참조는 제거되고 SYSTEM 참조는 유지
- 부분 갱신 뒤 이전 Student 전체 저장은 문서 버전 충돌로 거절
- 삭제된 Student를 부분 갱신으로 다시 만들지 않음

전체 검증 결과는 다음과 같다.

| 범위 | 결과 |
| --- | ---: |
| 단위 테스트 | 675개 통과 |
| 통합 테스트 | 156개 중 149개 통과, 7개 조건부 제외 |
| core-v1 Line / Branch / Class | 85.18% / 63.65% / 92.98% |
| full-v1 Line / Branch / Class | 70.20% / 52.31% / 78.74% |

외부 Gemini·crawler 조건이 필요한 7개는 기존과 같이 조건부 제외됐다. 이 단계는
정합성 검증이며 지연 시간이나 처리량을 측정하지 않았으므로 성능 향상률을 기록하지
않는다. Phase 3D 대비 커버리지 차이도 작아 README의 별도 성과 수치로 사용하지
않는다.

## 남은 제한

- MongoDB 두 컬렉션을 묶는 트랜잭션은 아니다. 참조 해제 뒤 Template 삭제가
  실패하면 Template은 남지만 기본값 선택은 해제된다.
- 과거 Student 문서의 깨진 ID를 시작 시 일괄 수정하지 않는다. 읽기 응답은 시스템
  fallback으로 통일했으며, 실제 저장값의 일괄 정리는 운영 데이터 확인 뒤 별도
  migration으로 실행해야 한다.
- Redis 잠금 lease를 잃는 상황에서 늦은 Template 생성까지 막으려면 Phase 3D에
  기록한 tombstone 또는 fencing token이 필요하다.
- Student가 이미 없는 과거 고아 Template·회고·피드백·로그의 일괄 정리는 공개 계정
  삭제 API와 분리된 dry-run 가능한 운영 작업이 필요하다.
