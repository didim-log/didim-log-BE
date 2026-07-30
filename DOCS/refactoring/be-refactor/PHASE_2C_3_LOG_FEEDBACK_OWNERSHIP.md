# Phase 2C-3 — 로그 피드백 소유권 검증

## 문제

`POST /api/v1/logs/{logId}/feedback`는 AI 리뷰의 LIKE/DISLIKE 평가를 저장하는 API다.
서비스 문의를 등록하는 `POST /api/v1/feedback`과는 다른 기능이다.

기존 엔드포인트는 JWT 보호 경로였지만 Controller가 인증 사용자를 받지 않았다.
Service도 로그 ID로 문서를 찾은 뒤 소유자를 확인하지 않고 저장했다.

```text
인증된 사용자
→ 다른 사용자의 logId로 피드백 제출
→ 로그 존재 여부만 확인
→ 피드백 저장
```

실제 Security Filter Chain과 MongoDB를 사용하는 재현 테스트에서 `testuser`가 만든
로그에 `admin` 토큰으로 피드백을 제출하면, 수정 전에는 `403` 대신 `200`을 반환했다.

## 변경

Controller가 JWT subject인 BOJ ID를 Service까지 전달한다. Service는 다음 순서로
인증과 소유권을 확인한 뒤 피드백을 저장한다.

```text
요청자 BOJ ID 확인
→ 로그 조회
→ Log.bojId와 요청자 비교
→ 피드백 저장
```

| 상황 | 응답 | 저장 |
| --- | --- | --- |
| 인증 정보 없음 또는 공백 | `401 UNAUTHORIZED` | 로그 조회 없음 |
| 로그 없음 | `404 COMMON_RESOURCE_NOT_FOUND` | 없음 |
| 로그 소유자 없음 | `403 ACCESS_DENIED` | 없음 |
| 다른 사용자 로그 | `403 ACCESS_DENIED` | 없음 |
| 본인 로그 | `200 OK` | 상태와 사유 저장 |

관리자도 이 사용자용 엔드포인트에서는 다른 사용자의 로그를 바꿀 수 없다. 소유자가
없는 기존 로그도 소유권을 증명할 수 없으므로 변경을 허용하지 않는다.

Swagger 응답에 `403`을 추가하고 API 명세의 설명도 본인 로그만 변경할 수 있다는
계약에 맞췄다.

## 검증

Service 테스트는 실제로 전달된 `Log`를 반환하도록 저장 mock을 바꿨다. 수정 전처럼
미리 만든 결과를 반환하면 잘못된 객체를 저장해도 테스트가 통과할 수 있었기 때문이다.
보안 통합 테스트는 전용 임시 MongoDB를 사용해 다른 테스트의 데이터 정리와 겹치지
않도록 격리했다.

```bash
./gradlew test \
  --tests 'com.didimlog.application.log.LogServiceFeedbackTest' \
  --tests 'com.didimlog.ui.controller.LogControllerTest' \
  --tests 'com.didimlog.ui.controller.LogControllerErrorTest' \
  --no-daemon

./gradlew integrationTest \
  --tests 'com.didimlog.global.security.SecurityIntegrationTest' \
  --no-daemon
```

| 항목 | 결과 |
| --- | --- |
| 관련 Controller·Service 단위 테스트 | 13개 통과 |
| 보안 통합 테스트 | 9개 통과 |
| 전체 단위 테스트 | 530개 통과 |
| 전체 통합 테스트 | 90개 중 83개 통과, 조건부 테스트 7개 제외 |
| JaCoCo gate | core-v1, full-v1 모두 통과 |
| 수정 전 타인 로그 요청 | `403` 기대, 실제 `200`으로 실패 |
| 수정 후 타인 로그 요청 | `403 ACCESS_DENIED`, 저장 상태 `NONE` 유지 |
| 수정 후 본인 로그 요청 | `200`, DISLIKE 상태와 사유 저장 |

이 단계는 접근 권한을 바로잡은 작업이므로 처리량이나 성능 향상률은 기록하지 않는다.

## 남은 범위

- `NONE`을 피드백 취소 값으로 허용할지에 대한 FE·BE 계약
- DISLIKE 사유의 필수 여부와 최대 길이
- 피드백 전체 저장과 동시에 완료되는 AI 리뷰 사이의 필드 덮어쓰기 방지
