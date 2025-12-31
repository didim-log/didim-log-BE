# Deployment Checklist

배포 전 마지막 점검을 위한 체크리스트입니다. **코드 기준**으로 작성되었으며, 최종 배포 직전에 한 번 더 확인하세요.

---

## 1) 환경변수(Env) 점검

`src/main/resources/application.yaml` 기준으로 다음 값을 반드시 주입해야 합니다.

- **MongoDB**
  - `SPRING_DATA_MONGODB_URI` (예: `mongodb://mongo:27017/didimlog`)
- **Redis**
  - `REDIS_HOST`
  - `REDIS_PORT`
- **JWT**
  - `JWT_SECRET`
  - (선택) `JWT_ACCESS_TOKEN_EXPIRATION`, `JWT_REFRESH_TOKEN_EXPIRATION`, `JWT_EXPIRATION`
- **Admin**
  - `ADMIN_SECRET_KEY`
- **CORS**
  - (선택) `CORS_ALLOWED_ORIGINS`
- **OAuth2**
  - `OAUTH_GOOGLE_ID`, `OAUTH_GOOGLE_SECRET`
  - `OAUTH_GITHUB_ID`, `OAUTH_GITHUB_SECRET`
  - `OAUTH_NAVER_ID`, `OAUTH_NAVER_SECRET`
  - (선택) `OAUTH_REDIRECT_URI`
- **Mail(SMTP)**
  - (선택) `MAIL_USERNAME` (기본값 존재)
  - `MAIL_PASSWORD`
- **AI (선택)**
  - `AI_ENABLED` (기본값 `false`)
  - (AI 사용 시) `GEMINI_API_KEY`
  - (선택) `GEMINI_API_URL`
- **Server URL (선택)**
  - `SERVER_URL` (기본값 `http://localhost:8080`)

---

## 2) 인프라/컨테이너 점검

- **Docker Compose**
  - `docker-compose.yml`에 `mongo`, `redis`가 올라오는지 확인
  - 백엔드 컨테이너가 Mongo/Redis에 의존하므로 `depends_on` 동작 확인
- **포트**
  - 컨테이너 내부는 `8080`
  - `docker-compose.yml` 기준 외부 노출은 `8083:8080`
  - Reverse Proxy(Nginx) 사용 시 Proxy Pass 포트가 실제 노출 포트와 일치하는지 확인

---

## 3) 데이터/마이그레이션 점검 (MongoDB)

- **인덱스**
  - 신규/변경된 조회 API(공지사항 정렬 등)에 필요한 인덱스가 존재하는지 확인
  - 데이터량이 많다면 정렬/검색 조건 기준으로 인덱스 설계를 점검
- **초기 데이터**
  - 운영에서 최초 관리자 생성 플로우(`super-admin`) 사용 계획이 있다면 `ADMIN_SECRET_KEY` 설정 및 접근 제한 확인

---

## 4) 보안/운영 기능 점검

- **유지보수 모드**
  - `POST /api/v1/admin/system/maintenance`로 ON/OFF가 되는지 확인
  - ON 시 일반 사용자 요청이 `503 MAINTENANCE_MODE`로 차단되는지 확인
- **민감정보 로그**
  - 이메일/BOJ ID 등은 마스킹 로깅이 적용되어 있는지 운영 로그 샘플로 확인
- **CORS**
  - 운영 프론트 도메인이 `CORS_ALLOWED_ORIGINS`에 포함되어 있는지 확인

---

## 5) 배포 전 최종 확인

- `./gradlew test` 통과
- `./gradlew build` 통과
- `DOCS/API_SPECIFICATION.md`가 코드와 동기화되어 있는지 확인
- 운영 환경에서 OAuth2 리다이렉트 URL 설정(공급자 콘솔/프론트/백엔드)이 일치하는지 확인


