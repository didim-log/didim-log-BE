# DidimLog Performance Fixture Guide

이 fixture는 운영 DB나 실제 사용자 계정을 사용하지 않고 k6 성능 실험을 실행하기 위한 로컬 전용 데이터다.

## 실제 코드 기준 확인 사항

- 인증 필터는 JWT 서명과 만료를 검증하고 `sub`를 BOJ ID로 사용한다.
- Dashboard, Statistics, Retrospective 목록/상세는 `StudentRepository.findByBojId(BojId(jwt.sub))`를 호출하므로 MongoDB에 같은 BOJ ID의 테스트 Student가 있어야 한다.
- `POST /api/v1/logs`는 JWT subject를 `Log.bojId`로 저장한다.
- `POST /api/v1/logs/{logId}/ai-review`는 요청자 BOJ ID와 로그 소유자 BOJ ID가 다르면 403을 반환한다.
- 회원가입은 Solved.ac 조회를 수행하므로 성능 실험용 사용자 생성에는 사용하지 않는다.

## 기본 테스트 사용자

- BOJ ID: `perfuser`
- Password: `PerfPassword123!`
- Role: `USER`
- JWT secret: `performance-secret-key-must-be-at-least-256-bits-long-1234567890`

`performance/k6/run-local.sh seed`는 다음 데이터를 로컬 MongoDB에 upsert한다.

- `students._id = perf-student-1`
- `students.bojId = perfuser`
- `retrospectives._id = perf-retro-1..N`
- `retrospectives.studentId = perf-student-1`

## 앱 실행 환경 변수

Spring Boot 앱은 아래처럼 로컬 DB와 WireMock Gemini를 바라보게 실행한다.

```bash
export SPRING_DATA_MONGODB_URI=mongodb://localhost:27017/didimlog-performance
export SPRING_DATA_REDIS_HOST=localhost
export SPRING_DATA_REDIS_PORT=6379
export JWT_SECRET=performance-secret-key-must-be-at-least-256-bits-long-1234567890
export GEMINI_API_KEY=local-gemini-key
export GEMINI_API_URL=http://localhost:8090/v1beta/models/gemini-2.5-flash:generateContent
export GEMINI_MAX_RETRIES=0
./gradlew bootRun
```

## Solved.ac, OAuth, SMTP 격리

- 조회/AI 실험은 로그인 또는 회원가입 API를 호출하지 않고 k6에서 로컬 JWT를 생성한다.
- Rate Limit 실험의 회원가입/로그인/비밀번호 재설정 요청은 validation 실패 요청을 사용하므로 Solved.ac, OAuth, SMTP를 호출하지 않는다.
- OAuth Provider와 SMTP 서버는 성능 실험 범위에서 제외한다.
