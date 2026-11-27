# 로그 피드백 소유권 검증

AI 리뷰에 LIKE/DISLIKE를 남기는 API가 로그 주인을 확인하지 않던 것을, 본인 로그에만 저장하도록 바꿨습니다. 다른 사용자의 로그 ID로 요청하면 403이 됩니다.

## 이전과 현재

| 상황 | 이전 | 현재 |
| --- | --- | --- |
| 다른 사용자 로그에 피드백 | 200, 저장됨 | 403 `ACCESS_DENIED`, 상태 `NONE` 유지 |
| 본인 로그에 피드백 | 200 | 200, 상태와 사유 저장 |
| 관리자가 다른 사용자 로그에 피드백 | 200 | 403 |
| 주인이 없는 기존 로그 | 저장됨 | 403 |
| 인증 정보 없음 | - | 401, 로그 조회 안 함 |
| 없는 로그 | - | 404 `COMMON_RESOURCE_NOT_FOUND` |

실제 Security Filter Chain과 MongoDB로 확인했습니다. 접근 권한을 바로잡은 변경이라 성능은 측정하지 않았습니다.

## 문제

`POST /api/v1/logs/{logId}/feedback`은 AI 리뷰 평가를 저장하는 API입니다. 서비스 문의를 등록하는 `POST /api/v1/feedback`과는 다릅니다.

JWT 보호 경로였지만 Controller는 인증 사용자를 받지 않았고, Service는 로그 ID로 문서를 찾은 뒤 주인을 보지 않고 저장했습니다. `testuser`의 로그에 `admin` 토큰으로 피드백을 보낸 재현 테스트는 403이 아니라 200을 받았습니다.

## 바꾼 내용

```text
요청자 studentId 확인 → 로그 조회 → Log.studentId와 요청자 비교 → 피드백 저장
```

- Controller가 인증된 사용자의 `studentId`를 Service까지 넘깁니다.
- 관리자도 이 사용자용 API로는 다른 사용자의 로그를 바꿀 수 없습니다.
- `studentId`가 없는 기존 로그는 주인을 증명할 수 없어 거절합니다. 지금의 BOJ ID로 자동 연결하면 탈퇴나 ID 변경 뒤 새 회원에게 넘어갈 수 있기 때문입니다.
- Swagger 응답과 API 명세에 403과 "본인 로그만 변경" 계약을 적었습니다.

## 확인한 방법

- 수정 전 재현 테스트가 200으로 실패했고, 수정 뒤 403과 저장 상태 `NONE` 유지를 확인합니다.
- Service 테스트의 저장 mock이 실제로 넘어온 `Log`를 돌려주게 바꿔, 잘못된 객체를 저장하면 테스트가 실패하게 했습니다.
- `./gradlew clean check`가 통과했습니다(단위 530개, 통합 90개 중 83개 통과·조건부 7개 제외).

```bash
./gradlew test --tests '*LogServiceFeedbackTest' --tests '*LogControllerTest' --tests '*LogControllerErrorTest'
./gradlew integrationTest --tests '*SecurityIntegrationTest'
```

## 남은 한계

- `NONE`을 피드백 취소 값으로 받을지 FE·BE 계약이 정해지지 않았습니다.
- DISLIKE 사유의 필수 여부와 최대 길이가 없습니다.
- 피드백 저장과 AI 리뷰 완료가 겹치면 필드를 덮어쓸 수 있습니다.
