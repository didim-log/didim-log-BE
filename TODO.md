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

## Phase 3: Retrospective & Dashboard
> **Goal:** 회고 작성 기능 및 메인 대시보드 API

- [x] **[Service]** `RetrospectiveService`: 회고 CRUD 및 템플릿 생성
- [x] **[Service]** `DashboardService`: 대시보드 데이터 Aggregation
- [x] **[API]** 회고 및 대시보드 API 구현

## Phase 4: Security & Authentication (New ⭐)
> **Goal:** Solved.ac 인증 기반의 진짜 회원가입 및 JWT 보안 적용

- [x] **[Config]** `build.gradle.kts`에 JWT 의존성(jjwt) 추가
- [x] **[Security]** `JwtTokenProvider` 구현 (토큰 생성/검증/파싱)
- [x] **[Security]** `JwtAuthenticationFilter` 및 Security Config 구현
- [x] **[Service]** `AuthService`: Solved.ac 실명 인증 및 회원가입/로그인 로직 구현
- [x] **[API]** `AuthController`: 로그인/회원가입 API 및 문서화 (`API_SPECIFICATION.md`)
- [x] **[Refactor]** 기존 임시 로그인 로직 제거 및 코드 정리

## Phase 5: Service Enhancement (v1.2)
> **Goal:** 회고 중심의 랭킹 개편, 대시보드 시각화 강화, 그리고 소셜 로그인 및 본인 인증 도입

### 5-1. Advanced Authentication (Auth 2.0)
- [ ] **[Config]** OAuth2 Client 설정 (Google, Kakao, Naver) 및 Provider 구현
- [ ] **[Domain]** `User` 엔티티 수정: 소셜 ID, 프로바이더, 약관 동의 여부, 별명 필드 추가
- [ ] **[Feature]** BOJ 계정 소유권 인증 로직 구현 (상태 메시지 검증 방식)
- [ ] **[UI]** 로그인 페이지 개편: 소셜 로그인 버튼, 회원가입(약관 동의 -> BOJ 인증 -> 닉네임 설정) 위저드 구현

### 5-2. Dashboard 2.0 (Motivation & Layout)
- [ ] **[Logic]** 다음 티어까지 남은 경험치(Rating) 계산 및 게이지바(%) 로직 구현
- [ ] **[UI]** 대시보드 레이아웃 전면 수정:
  - 상단: 내 정보 + 티어 경험치 게이지바
  - 중단: 추천 문제 카드 (크게 배치)
  - 하단: 최근 풀이 활동 잔디 (전체 너비 채움)
- [ ] **[Navigation]** 헤더 '내 정보' 버튼을 드롭다운 메뉴(내 정보, 랭킹, 로그아웃)로 변경

### 5-3. Ranking System 2.0 (Retrospective Based)
- [ ] **[Logic]** 랭킹 산정 기준 변경: '문제 풀이 수' -> **'회고 작성 수'**
- [ ] **[Infra]** QueryDSL을 활용한 기간별(일/월/연) 집계 쿼리 구현
- [ ] **[UI]** 랭킹 페이지 개편:
  - 상단: "꾸준한 회고가 성장의 지름길입니다" 등 감사/동기부여 배너
  - 리스트: 1~3위 강조형 카드, 4~100위 테이블 리스트
  - 필터: 일간/월간/연간 정렬 탭 구현

### 5-4. User Profile & Settings
- [ ] **[API]** 내 정보 수정 (닉네임 변경) API
- [ ] **[UI]** 마이페이지 내 설정 탭 구현
