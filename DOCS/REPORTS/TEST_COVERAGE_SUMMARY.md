# 테스트 커버리지 요약 보고서

> 기존 루트 파일(`TEST_COVERAGE_SUMMARY.md`)을 문서 폴더로 이관하고, 최신 테스트 기준으로 내용 일부를 현행화했습니다.

---

## ✅ 핵심 커버 시나리오

### Auth
- **BOJ ID 중복 체크**
  - 중복/미중복 `200 OK` 응답 검증
  - `bojId` 파라미터 누락 시 `400 Bad Request` 검증
  - 예상치 못한 예외 발생 시 `500 Internal Server Error` + `COMMON_INTERNAL_ERROR` 검증

### Retrospective
- `solveTime` 필드가 Request/Response에 포함되고 저장/조회되는지 검증
- 회고 수정(PATCH) 성공/실패(소유권 위반 시 403) 시나리오 검증

### Notice
- 공지 목록/상세 조회 `200 OK` + JSON 구조 검증
- (Admin) 공지 작성 시 유효성 실패(제목/내용) `400 Bad Request` 검증
- 예상치 못한 예외 발생 시 `500 Internal Server Error` + `COMMON_INTERNAL_ERROR` 검증

### Admin Dashboard / System
- 성능 메트릭 조회(기본값 포함) `200 OK` 검증
- 유지보수 모드 토글 `200 OK` 검증
- 예상치 못한 예외 발생 시 `500 Internal Server Error` + `COMMON_INTERNAL_ERROR` 검증

---

## 📌 참고

- 컨트롤러 테스트는 `@WebMvcTest` + `MockMvc` 기반으로 작성
- 전역 예외 처리(`GlobalExceptionHandler`)의 에러 포맷(`ErrorResponse`)이 문서/테스트와 일치하도록 검증


