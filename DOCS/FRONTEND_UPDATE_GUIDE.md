# 프론트엔드 연동 업데이트 가이드

## 목적
백엔드 API를 프론트엔드에서 실제 사용하는 범위로 정리하고, 응답 스키마/카테고리 정책을 최신화합니다.

## 현재 연동 기준 (FE 사용 API)
### Template
- `GET /api/v1/templates`
- `GET /api/v1/templates/summaries`
- `GET /api/v1/templates/presets`
- `POST /api/v1/templates/preview`
- `POST /api/v1/templates/{id}/render`
- `GET /api/v1/templates/{id}/render` (레거시 호환)
- `POST /api/v1/templates`
- `PUT /api/v1/templates/{id}`
- `PUT /api/v1/templates/{id}/default?category=SUCCESS|FAIL`
- `DELETE /api/v1/templates/{id}`

### Retrospective
- `POST /api/v1/retrospectives?problemId={id}`
- `studentId`는 쿼리 파라미터로 전달하지 않으며 JWT에서 식별합니다.

## 제거/비권장
- `POST /api/v1/retrospectives/template/static` 정적 템플릿 전용 API는 현재 정책에서 사용하지 않습니다.
- 템플릿 기본값 category는 `SUCCESS` 또는 `FAIL`만 지원합니다.

## Swagger 카테고리 정책
- 관리 기능은 `Admin`으로 통합합니다.
- 공개 시스템 상태는 `System` 카테고리로 노출합니다.

## 프론트 체크리스트
- [ ] 템플릿 기본값 설정 시 category가 `SUCCESS` 또는 `FAIL`만 전달되는지 확인
- [ ] 템플릿 프리셋 응답에서 `title`, `guide`, `contentGuide`를 그대로 사용
- [ ] 회고 작성 요청에서 `studentId`를 전송하지 않는지 확인
- [ ] 인증 실패(401)와 검증 실패(400) 에러 메시지 핸들링 확인
