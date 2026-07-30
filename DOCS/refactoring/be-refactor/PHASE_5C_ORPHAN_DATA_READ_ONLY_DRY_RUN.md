# Phase 5C — 고아 데이터 읽기 전용 점검

## 문제

계정 삭제와 사용자 데이터 쓰기의 경합은 새 고아 데이터가 생기지 않도록 막았지만,
기존 데이터에 남아 있을 수 있는 참조까지 자동으로 삭제하지는 않는다. 과거 데이터를
정리하려면 먼저 대상과 보존 범위를 분리해 읽기 전용으로 확인해야 한다.

이번 단계는 삭제 작업이 아니다. 점검 결과에도 `cleanupAuthorized: false`를 기록하며,
결과가 있거나 없다는 이유만으로 삭제를 승인하지 않는다.

## 점검 범위

사용자 소유 관계 5개와 기본 템플릿 참조 2개를 확인한다.

| 구분 | 참조 | 판정 |
| --- | --- | --- |
| 사용자 소유 | `retrospectives.studentId → students._id` | 소유자 없음, 필수값 누락, 잘못된 참조 형식 |
| 사용자 소유 | `feedbacks.writerId → students._id` | 소유자 없음, 필수값 누락, 잘못된 참조 형식 |
| 사용자 소유 | CUSTOM `templates.studentId → students._id` | 소유자 없음, 필수값 누락, 잘못된 참조 형식 |
| 사용자 소유 | `logs.studentId → students._id` | 값이 있는 로그의 소유자 없음·잘못된 참조 형식 |
| 사용자 소유 | `password_reset_codes.studentId → students._id` | 소유자 없음, 필수값 누락, 잘못된 참조 형식 |
| 기본 참조 | `students.defaultSuccessTemplateId → templates._id` | 대상 없음, 다른 사용자의 CUSTOM 템플릿, 대상 형태 오류 |
| 기본 참조 | `students.defaultFailTemplateId → templates._id` | 대상 없음, 다른 사용자의 CUSTOM 템플릿, 대상 형태 오류 |

다음 데이터는 삭제 후보로 해석하지 않는다.

- SYSTEM 템플릿
- `studentId`가 없거나 `null`인 과거 로그
- `admin_audit_logs`
- 로그의 BOJ ID 스냅샷과 문제 참조

BOJ ID로 소유자를 추정하지 않는다. 학생과 템플릿 ID는 String과 ObjectId 양쪽으로
조회하되, 같은 값이 두 타입으로 함께 존재하면 어느 문서를 뜻하는지 정할 수 없으므로
점검을 중단한다.

## 안전 경계

운영 대상에서는 해당 DB의 built-in `read` 역할 하나만 가진 계정을 허용한다.
`mongodb://` URI는 `tls=true` 또는 `ssl=true`가 필요하며, `mongodb+srv://` URI도
TLS를 끄는 옵션을 허용하지 않는다.

다음 조건에서는 결과를 `BLOCKED`로 남기고 중단한다.

- 대상 DB 이름 불일치 또는 예약 DB 지정
- 과도한 권한이나 다른 역할을 가진 계정
- Student·Template의 지원하지 않는 ID 타입
- 대소문자를 정규화했을 때 겹치는 String/ObjectId ID
- 실행 전후 컬렉션 문서 수 또는 점검 필드 fingerprint 변화

각 읽기는 primary와 majority read concern으로 실행한다. 실행 전후에는 아래 필드만
EJSON 형태로 순서대로 SHA-256 처리한다.

| 컬렉션 | fingerprint 입력 필드 |
| --- | --- |
| `students` | `_id`, `defaultSuccessTemplateId`, `defaultFailTemplateId` |
| `templates` | `_id`, `type`, `studentId` |
| `retrospectives` | `_id`, `studentId` |
| `feedbacks` | `_id`, `writerId` |
| `logs` | `_id`, `studentId` |
| `password_reset_codes` | `_id`, `studentId`, `expiresAt` |

보고서에는 원본 Student·Template ID를 출력하지 않는다. 쓰기 가능한 MongoDB 메서드와
`$out`, `$merge` 단계가 없는지 정적으로 검사하고, 실제 `read` 역할로 전체 점검을
실행한다.

## 합성 fixture 검증

아래 값은 운영 데이터 수치가 아니라 점검 로직을 확인하기 위해 만든 fixture의
기대값이다.

| 확인 항목 | 기대값 | 결과 |
| --- | ---: | ---: |
| 사용자 소유 관계 | 5개 | 5개 확인 |
| 기본 템플릿 참조 | 2개 | 2개 확인 |
| 소유자가 없는 fixture 문서 | 6건 | 6건 일치 |
| 대상이 없는 기본 템플릿 참조 | 1건 | 1건 일치 |
| 다른 사용자의 CUSTOM 템플릿 참조 | 1건 | 1건 일치 |
| 쓰기 가능 단계 요청 | 0건 | 0건 |
| 반복 실행 결과 hash | 동일 | 동일 |
| fixture 원문과 점검 필드 fingerprint | 실행 전후 동일 | 동일 |
| 과도한 권한 | 차단 | `REMOTE_READ_ROLE_REQUIRED` |
| Student·Template canonical ID 충돌 | 각각 차단 | 각각 `CANONICAL_PARENT_ID_COLLISION` |

검증은 다음 MongoDB 이미지에서 실행한다.

```text
mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a
```

보고서의 `source.mongoImage`는 이 합성 fixture 검증 환경을 기록한 값이며 운영
MongoDB의 배포 정보를 뜻하지 않는다. 검증 harness hash에는 실행 스크립트, 검증기,
fixture와 snapshot 스크립트를 모두 포함한다.

## 재현

합성 fixture 검증은 임시 MongoDB 컨테이너를 만들고 종료할 때 제거한다.

```bash
ORPHAN_DRY_RUN_EXPECT_CLEAN_SOURCE=true \
performance/orphan-data/verify-dry-run.sh
```

운영 데이터는 삭제 권한이 없는 전용 계정과 TLS URI를 준비한 뒤 wrapper로만
점검한다. 자격 증명과 실제 URI는 저장소에 기록하지 않는다.

```bash
ORPHAN_DRY_RUN_MONGO_URI="$READ_ONLY_MONGO_URI" \
ORPHAN_DRY_RUN_EXPECTED_DATABASE=didimlog \
ORPHAN_DRY_RUN_TARGET_SCOPE=remote-read-only \
performance/orphan-data/run-dry-run.sh
```

완료 또는 중단 보고서는 `performance/results/<run-id>/` 아래에 생성된다.

## 결과 해석과 남은 제한

- `candidateLogicalBsonBytes`는 MongoDB `$bsonSize`를 더한 논리 크기다. 실제로
  회수되는 디스크 용량이나 저장 공간 절감량이 아니다.
- `maxTimeMs`는 전체 실행 시간이 아니라 각 MongoDB 명령의 제한이다.
- 문서 수와 점검 필드 fingerprint를 전후에 비교하지만 여러 명령이 하나의 DB
  snapshot을 공유하지 않는다. 보고서에도 `snapshotGuaranteed: false`를 기록한다.
- `NO_FINDINGS_OBSERVED`는 실행 중 관측한 범위에서 발견하지 못했다는 뜻이며 전체
  데이터 무결성을 보장하지 않는다.
- 합성 fixture 결과는 운영 환경의 고아 데이터 비율이나 처리 성능을 뜻하지 않는다.
- 이 단계는 성능 비교나 Java 코드 변경이 아니므로 개선율과 JaCoCo 변화를 기록하지
  않는다.
- 점검 결과는 검토 입력일 뿐이며 이 도구는 삭제를 실행하거나 승인하지 않는다.
