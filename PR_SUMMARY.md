# PR Summary: Gemini AI 코드 리뷰 기능 및 관리자 통계 개선

## 🎯 주요 변경 사항

### 1. Gemini 2.5 Flash API 통합
- **GeminiAiApiClient 어댑터 구현**: `LlmClient`를 `AiApiClient` 인터페이스에 맞춘 어댑터 추가
- **모델 업그레이드**: Gemini 2.0 Flash → Gemini 2.5 Flash (최신 안정 버전)
- **한국어 리뷰 지원**: 모든 AI 리뷰가 한국어로 생성되도록 프롬프트 수정

### 2. AI 코드 리뷰 API 구현
- **POST `/api/v1/logs`**: 코딩 로그 생성 API (bojId 자동 추출)
- **POST `/api/v1/logs/{logId}/ai-review`**: AI 한 줄 리뷰 생성/조회 API
  - 언어 자동 감지 (C, Java, Python, Kotlin 등)
  - 캐시 우선 처리 (중복 생성 방지)
  - 타임아웃 처리 (30초)
  - 생성 시간 로깅 (성능 모니터링용)

### 3. 관리자 기능 강화
- **AI 리뷰 로그 조회 API**:
  - `GET /api/v1/admin/logs`: 로그 목록 조회 (페이징, bojId 필터링)
  - `GET /api/v1/admin/logs/{logId}`: 상세 로그 조회
- **bojId별 통계 추가**:
  - `AdminUserResponse`에 `solvedCount`, `retrospectiveCount` 필드 추가
- **AI 메트릭 대시보드**:
  - 평균 AI 생성 시간
  - 총 생성 수, 타임아웃 수, 타임아웃 비율

### 4. 코드 품질 개선
- **AiReviewService 리팩토링**: 
  - 메서드 분리 (단일 책임 원칙 준수)
  - Early Return 패턴 적용
  - 들여쓰기 depth 감소

## 📋 API 변경 사항

### 새로 추가된 API
1. `POST /api/v1/logs` - 로그 생성
2. `POST /api/v1/logs/{logId}/ai-review` - AI 리뷰 생성/조회
3. `GET /api/v1/admin/logs` - 관리자 로그 목록 조회
4. `GET /api/v1/admin/logs/{logId}` - 관리자 로그 상세 조회

### 기존 API 변경
- `GET /api/v1/admin/users`: `solvedCount`, `retrospectiveCount` 필드 추가
- `GET /api/v1/admin/dashboard/stats`: `aiMetrics` 필드 추가

## 🧪 테스트
- **AiReviewIntegrationTest**: 실제 Gemini API를 호출하는 통합 테스트
- 모든 테스트 통과 확인 ✅

## 📝 문서 업데이트
- `DOCS/API_SPECIFICATION.md`: 새로운 API 명세 추가 및 기존 API 필드 업데이트
- `README.md`: Gemini API URL 업데이트

## 🔧 기술적 개선 사항
- MongoDB 원자적 락을 활용한 중복 호출 방지
- 타임아웃 예외 처리 및 로깅
- 빈 중복 등록 문제 해결 (@Component 제거)
- 클린코드 원칙 준수 (메서드 분리, Early Return)

## 💡 주요 특징
- **비용 최적화**: 캐시 우선 처리로 중복 AI 호출 방지
- **성능 모니터링**: AI 생성 시간 로깅 및 관리자 대시보드 제공
- **사용자 경험**: 한국어 리뷰로 더 친숙한 피드백 제공
- **확장성**: 어댑터 패턴으로 향후 다른 AI 모델로 교체 용이

