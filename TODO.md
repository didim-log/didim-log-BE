# 🚀 DidimLog (Backend) Development Roadmap

이 문서는 AI 기반 자동화 개발(Cursor + Gemini)을 위한 마일스톤 관리 문서입니다.
각 체크박스는 하나의 독립적인 작업 단위(Commit)를 의미합니다.

---

## Phase 0: Environment & Infrastructure
> **Goal:** Spring Boot, MongoDB, Redis 환경 구축 및 기본 프로젝트 세팅

- [x] **[Setup]** Spring Boot 3.x (Kotlin) 프로젝트 초기화
- [x] **[Config]** `docker-compose.yml` 작성 (MongoDB, Redis)
- [x] **[Config]** MongoDB 및 Redis 연결 설정 (`application.yml`)
- [x] **[Config]** JPA Auditing 설정
- [x] **[Docs]** Swagger (OpenAPI 3.0) 설정

## Phase 1: Core Domain Implementation
> **Goal:** 핵심 도메인 설계 및 구현 (Clean Code Principles)

- [x] **[Domain]** `Nickname`, `BojId`, `Tier` (VO & Enum)
- [x] **[Domain]** `Solution`, `Solutions` (일급 컬렉션)
- [x] **[Domain]** `Problem`, `Student`, `Retrospective` (Aggregate Roots)
- [x] **[Infra]** Repository Layer (MongoRepository) 구현

## Phase 2: Core Feature - Problem Solving
> **Goal:** 문제 데이터 수집, 문제 풀이 로직, 추천 시스템 구현

- [x] **[Infra]** Solved.ac API 연동 클라이언트 (`WebClient`)
- [x] **[Service]** `ProblemService`: 문제 데이터 수집 및 동기화
- [x] **[Service]** `StudyService`: 문제 풀이 트랜잭션 로직
- [x] **[Service]** `RecommendationService`: 무한 성장 추천 알고리즘
- [x] **[API]** 문제 추천 및 풀이 제출 API 구현
- [x] **[Service]** `RankingService`: 사용자 Rating 기준 랭킹 조회 서비스 구현
- [x] **[API]** 랭킹 조회 API 구현 (`/api/v1/ranks`)

## Phase 3: Retrospective & Dashboard
> **Goal:** 회고 작성 기능 및 메인 대시보드 API

- [x] **[Service]** `RetrospectiveService`: 회고 CRUD 및 템플릿 생성
- [x] **[Service]** `DashboardService`: 대시보드 데이터 Aggregation
- [x] **[API]** 회고 및 대시보드 API 구현
- [x] **[Feature]** 회고에 한 줄 요약(summary) 필드 추가
- [x] **[Feature]** 회고 검색 및 북마크 기능 구현

## Phase 4: Security & Authentication (New ⭐)
> **Goal:** Solved.ac 인증 기반의 진짜 회원가입 및 JWT 보안 적용

- [x] **[Config]** `build.gradle.kts`에 JWT 의존성(jjwt) 추가
- [x] **[Security]** `JwtTokenProvider` 구현 (토큰 생성/검증/파싱)
- [x] **[Security]** `JwtAuthenticationFilter` 및 Security Config 구현
- [x] **[Service]** `AuthService`: Solved.ac 실명 인증 및 회원가입/로그인 로직 구현
- [x] **[API]** `AuthController`: 로그인/회원가입 API 및 문서화 (`API_SPECIFICATION.md`)
- [x] **[Refactor]** 기존 임시 로그인 로직 제거 및 코드 정리
- [x] **[Config]** OAuth2 Client 설정 (Google, GitHub, Naver) 및 Provider 구현
- [x] **[Test]** OAuth2 및 JWT 테스트 설정 추가
- [x] **[Handler]** OAuth2SuccessHandler 구현: 신규 유저는 DB 저장 없이 쿼리 파라미터로 전달, 기존 유저는 JWT 토큰 발급
- [x] **[Service]** AuthService.finalizeSignup: 소셜 로그인 신규 유저의 Student 엔티티 생성 로직 구현
- [x] **[API]** AuthController.finalizeSignup: 회원가입 마무리 API (email, provider, providerId, nickname, bojId, termsAgreed)

## User Management & Admin
> **Goal:** OAuth/BOJ 혼합 인증 환경에서 계정 관리(찾기/탈퇴)와 관리자 기능을 체계화한다.

- [x] **[API]** 아이디/비밀번호 찾기 로직(계정 조회) 구현
  - **구현**: `POST /api/v1/auth/find-account` (email → provider 반환)
  - **남은 결정(진행 중)**: 로컬(BOJ+password) 로그인 사용자의 비밀번호 재설정(메일 발송)까지 지원할지, OAuth-only로 제한할지

- [x] **[API]** 회원 탈퇴(본인) API 구현
  - **Endpoint**: `DELETE /api/v1/students/me`
  - **구현 방식**: Hard Delete (Student + Retrospective(studentId) + Feedback(writerId) cascade)
  - **남은 결정(진행 중)**: `DELETE /api/users/me` alias 제공 여부, Soft Delete 전환 여부

- [x] **[Admin]** 관리자 대시보드 통계 조회 API (`GET /api/v1/admin/dashboard/stats`)
- [x] **[Admin]** 사용자 전체 목록 조회(페이징) (`GET /api/v1/admin/users`)
- [x] **[Admin]** 회원 강제 탈퇴 (`DELETE /api/v1/admin/users/{studentId}`)
- [x] **[Admin]** 사용자 정보 강제 수정 API 구현
  - **Endpoint**: `PATCH /api/v1/admin/users/{studentId}`
  - **대상**: Role 변경, BOJ ID 변경(중복 검사 포함), 닉네임 변경(중복 검사 포함)

## Phase 5: Code Quality & Documentation
> **Goal:** 코드 품질 향상, 문서화, 및 프로젝트 정리

- [x] **[Docs]** API 명세서 최신화 (DELETE 엔드포인트 추가, 페이지네이션 1-based로 수정)
- [x] **[Docs]** Swagger(SpringDoc) 명세 및 `DOCS/API_SPECIFICATION.md` 전면 최신화
- [x] **[Fix]** GlobalExceptionHandler 예외 처리 강화 및 ErrorResponse 표준화(401/403/enum 파싱/타입 변환 등)
- [x] **[Refactor]** 클린코드 리팩토링 (else 키워드를 Early Return 패턴으로 변경)
- [x] **[Cleanup]** 불필요한 공백 및 코드 정리 (파일 끝 공백 라인 제거, 라인 끝 공백 제거)
- [x] **[Docs]** FE TODO.md에 백엔드 완료 작업 반영

## Phase 6: Service Enhancement (v1.2)
> **Goal:** 회고 중심의 랭킹 개편, 대시보드 시각화 강화, 그리고 소셜 로그인 및 본인 인증 도입

### 6-1. Advanced Authentication (Auth 2.0)
- [ ] **[Config]** OAuth2 Client 설정 (Google, Github, Naver) 및 Provider 구현
- [ ] **[Domain]** `User` 엔티티 수정: 소셜 ID, 프로바이더, 약관 동의 여부, 별명 필드 추가
- [x] **[Feature]** BOJ 계정 소유권 인증 로직 구현 (상태 메시지 검증 방식)
- [ ] **[UI]** 로그인 페이지 개편: 소셜 로그인 버튼, 회원가입(약관 동의 -> BOJ 인증 -> 닉네임 설정) 위저드 구현

### 6-2. Dashboard 2.0 (Motivation & Layout)
- [x] **[Logic]** 다음 티어까지 남은 경험치(Rating) 계산 및 게이지바(%) 로직 구현
- [ ] **[UI]** 대시보드 레이아웃 전면 수정:
  - 상단: 내 정보 + 티어 경험치 게이지바
  - 중단: 추천 문제 카드 (크게 배치)
  - 하단: 최근 풀이 활동 잔디 (전체 너비 채움)
- [ ] **[Navigation]** 헤더 '내 정보' 버튼을 드롭다운 메뉴(내 정보, 랭킹, 로그아웃)로 변경

### 6-3. Ranking System 2.0 (Retrospective Based)
- [x] **[Logic]** 랭킹 산정 기준 변경: '문제 풀이 수' -> **'회고 작성 수'**
- [x] **[Infra]** Mongo Aggregation(MongoTemplate)을 활용한 기간별(DAILY/WEEKLY/MONTHLY/TOTAL) 집계 쿼리 구현
- [ ] **[UI]** 랭킹 페이지 개편:
  - 상단: "꾸준한 회고가 성장의 지름길입니다" 등 감사/동기부여 배너
  - 리스트: 1~3위 강조형 카드, 4~100위 테이블 리스트
  - 필터: 일간/월간/연간 정렬 탭 구현

### 6-4. User Profile & Settings
- [x] **[API]** 내 정보 수정 (닉네임 변경) API
- [ ] **[UI]** 마이페이지 내 설정 탭 구현

## Phase 7: AI-Powered Retrospective (Structured)
> **Goal:** `docs/RETROSPECTIVE_STANDARDS.md`에 정의된 구조를 기반으로, AI가 분석 리포트를 제공한다.

- [x] **[Config]** Gemini 2.0 Flash 연동 설정 및 LLM 클라이언트 구현

- [x] **[Design]** 프롬프트 템플릿 구조화

  - AI에게 전체 회고 작성이 아닌, **요청된 섹션 본문만** 생성하도록 제한
  - `RETROSPECTIVE_STANDARDS.md` 기반의 **정형화된 마크다운 템플릿**(섹션 번호/제목/구조)을 시스템 프롬프트에 주입하여 일관성 보장

- [x] **[API]** 섹션별 AI 분석 요청 API 구현 (`POST /api/v1/ai/analyze`)

  - **Request:**

    - `code`: 사용자 코드

    - `problemId`: 문제 번호

    - `sectionType`: `REFACTORING` | `ROOT_CAUSE` | `COUNTER_EXAMPLE` (요청할 항목 지정)

  - **Response:** 해당 섹션에 들어갈 마크다운 텍스트

- [x] **[Refactor]** 비용 절감을 위한 정적 템플릿 + AI 키워드 주입 방식 구현

  - `StaticTemplateService`: RETROSPECTIVE_STANDARDS.md 구조에 맞춘 정적 템플릿 생성 (플레이스홀더 포함)
  - `AiKeywordService`: AI가 키워드 3개만 추출하도록 최적화 (토큰 절감)
  - `LlmClient.extractKeywords()`: 키워드 추출 전용 메서드 추가
  - AI 활성화 여부(`app.ai.enabled`)에 따른 동적 키워드 주입 로직
  - AI 호출 실패 시에도 정적 템플릿 정상 반환 (안정성 보장)

- [ ] **[UI]** 회고 작성 에디터 고도화

  - 성공/실패 선택 시 미리 정의된 목차(Markdown)가 에디터에 자동 세팅됨

  - 'AI 분석' 버튼 클릭 시, 특정 섹션(예: 리팩토링 제안) 아래에 로딩 애니메이션 후 텍스트가 타이핑되듯 추가됨
