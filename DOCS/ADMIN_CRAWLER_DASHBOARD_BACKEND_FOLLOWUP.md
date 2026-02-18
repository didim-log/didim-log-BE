# Admin Problem Crawler Dashboard - Backend Follow-up Report

작성일: 2026-02-18
대상: `/Users/dh/Desktop/Code/didim-log` (BE), `/Users/dh/Desktop/Code/didim-log/DOCS/API_SPECIFICATION.md`

## 목적
FE 관리자 문제 크롤링 대시보드를 5단계(가시성/운영제어/장애대응/신뢰성/리포팅)로 확장하면서, 현재 백엔드 API 계약으로는 FE가 임시 로컬 상태(localStorage)에 의존할 수밖에 없는 영역이 확인되었습니다.

이 문서는 **백엔드가 다음 스프린트에서 바로 구현 가능한 수준**으로 필요한 API/명세 보완사항을 정리합니다.

---

## 현재 상태 요약
현재 제공 API:
- `POST /api/v1/admin/problems/collect-metadata`
- `GET /api/v1/admin/problems/collect-metadata/status/{jobId}`
- `POST /api/v1/admin/problems/collect-details`
- `GET /api/v1/admin/problems/collect-details/status/{jobId}`
- `POST /api/v1/admin/problems/refresh-details`
- `GET /api/v1/admin/problems/refresh-details/status/{jobId}`
- `POST /api/v1/admin/problems/update-language`
- `GET /api/v1/admin/problems/update-language/status/{jobId}`

현 계약으로 가능한 것:
- 단일 `jobId` 기준 진행률 폴링
- 400/404 실패 처리
- 체크포인트 기반 재시작(일부)

현 계약으로 어려운 것:
- 최근 작업 목록/큐 상태 조회(서버 소스 오브 트루스 부재)
- 작업 취소/강제 종료
- 큐 위치/대기 사유/워커 점유 상태
- 정체(stagnation) 판별용 heartbeat 필드
- 관리자 단위 감사 로그(누가 어떤 범위를 돌렸는지)

---

## FE에서 확인한 명세 허점

### 1) Job 상태 스키마 불균일
`collect-metadata` 상태와 나머지 상태가 필드 편차가 존재합니다.

권장: 모든 배치 상태 응답을 아래 공통 스키마로 통일

```json
{
  "jobId": "string",
  "jobType": "METADATA|DETAILS|DETAILS_REFRESH|LANGUAGE_UPDATE",
  "status": "PENDING|RUNNING|COMPLETED|FAILED|CANCELLED",
  "queuedAt": 1739880000,
  "startedAt": 1739880005,
  "lastHeartbeatAt": 1739880100,
  "completedAt": 1739880200,
  "totalCount": 1000,
  "processedCount": 320,
  "successCount": 300,
  "failCount": 20,
  "progressPercentage": 32,
  "estimatedRemainingSeconds": 240,
  "queuePosition": 1,
  "range": { "start": 1000, "end": 5000 },
  "lastCheckpointId": "string|number|null",
  "errorCode": "string|null",
  "errorMessage": "string|null",
  "createdBy": "adminId"
}
```

### 2) 대기(PENDING) 상태 정보 부족
현재는 FE에서 "대기 중"만 표시 가능하고, **왜 대기인지/얼마나 대기할지** 서버 정보가 없음.

필요 필드:
- `queuePosition`
- `queuedAt`
- `lastHeartbeatAt`
- `pendingReason` (선택)

### 3) 작업 리스트 API 부재
관리자 대시보드의 최근 작업/운영 리포트를 서버 기준으로 만들려면 API가 필요합니다.

현재 FE는 임시로 localStorage 이력을 사용 중(브라우저 의존, 서버 신뢰도 낮음).

### 4) 에러 코드 표준화 부족
현재 400/404는 처리 가능하지만, 운영 자동화(자동재시작/가이드 문구)는 `message` 파싱 의존이 큼.

필요:
- `errorCode` enum (`INVALID_RANGE`, `JOB_NOT_FOUND`, `QUEUE_TIMEOUT`, `WORKER_UNAVAILABLE` 등)

### 5) 취소 API 부재
장시간 실행 작업을 관리자가 중단할 수 없어 운영 리스크가 있음.

---

## 백엔드 구현 권장안 (우선순위)

## P1 (즉시)
### A. 최근 작업 목록 API
- `GET /api/v1/admin/problems/jobs`
- Query:
  - `type` optional
  - `status` optional
  - `from`/`to` optional (ISO8601)
  - `page`/`size`
- Response: `Page<JobStatusUnifiedResponse>`

### B. 공통 Job 상세 API
- `GET /api/v1/admin/problems/jobs/{jobId}`
- 기존 `/status/{jobId}`는 호환 유지, 내부적으로 통일 DTO 반환

### C. 상태 DTO 공통화
- Metadata/Details/Refresh/Language 상태 DTO를 하나로 통합
- 최소 필드: `queuedAt`, `lastHeartbeatAt`, `jobType`, `createdBy`, `errorCode`

## P2 (운영)
### D. 작업 취소 API
- `POST /api/v1/admin/problems/jobs/{jobId}/cancel`
- 상태 전이: `PENDING|RUNNING -> CANCELLED`
- 응답: `{ jobId, status, message }`

### E. 재시도 API (서버 주도)
- `POST /api/v1/admin/problems/jobs/{jobId}/retry`
- 기존 파라미터/체크포인트를 서버가 재사용

## P3 (리포팅)
### F. 운영 통계 API
- `GET /api/v1/admin/problems/jobs/metrics`
- Query: `window=DAY|WEEK|MONTH`
- Response:
  - `totalJobs`, `completedJobs`, `failedJobs`
  - `avgDurationSeconds`
  - `avgFailureRate`
  - `topErrorCodes[]`

### G. 배치 감사 로그 API
- `GET /api/v1/admin/problems/jobs/audit`
- who/when/what(range)/result 조회

---

## API_SPECIFICATION.md 보완 포인트
`API_SPECIFICATION.md`에 아래를 추가/정리 권장:

1. 배치 상태 공통 스키마 섹션 신설
- 현재 엔드포인트별로 비슷한 필드가 반복됨
- `JobStatusUnifiedResponse` 섹션 하나로 정의하고 각 엔드포인트는 참조

2. 상태 전이 정의
- `PENDING -> RUNNING -> COMPLETED|FAILED|CANCELLED`
- 각 상태 의미와 FE 권장 행동(폴링 주기, 재시도 정책)

3. 에러 코드 명세
- 400/404의 `message` 자유 텍스트 대신 `errorCode`를 문서화

4. 시간 필드 단위 통일
- `startedAt`, `completedAt`, `queuedAt`, `lastHeartbeatAt` 단위를 명시 (Unix seconds 권장)

5. 범위 필드 표준화
- `start/end` 쿼리 파라미터와 상태 응답의 `range.start/range.end` 일관성 유지

---

## FE 연계 메모
현재 FE는 아래를 선반영함:
- 단계형 상태 배지(PENDING/RUNNING/COMPLETED/FAILED)
- 실패율/정체 경고
- 최근 작업 이력/24h·7d 리포트(임시 localStorage 기반)
- 동적 폴링(PENDING 느리게, RUNNING 빠르게) + 네트워크 백오프
- checkpoint 기반 자동복구 옵션

백엔드가 위 P1 API를 제공하면 FE는 즉시 아래 전환 가능:
- localStorage 이력 제거 -> 서버 이력 사용
- 리포트 정확도/신뢰도 개선
- 다중 관리자 환경에서 동일한 운영 시야 확보

---

## 제안 작업 순서 (BE)
1. `JobStatusUnifiedResponse` DTO/매퍼 도입
2. 기존 4개 status API 응답을 통합 DTO로 교체(호환 필드 유지)
3. `GET /admin/problems/jobs` 추가
4. `API_SPECIFICATION.md`에 공통 스키마/상태 전이/에러 코드 문서화
5. 이후 `cancel`, `retry`, `metrics` 순차 도입

