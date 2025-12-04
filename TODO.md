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
> **Goal:** 사용자 경험 개선, 게이미피케이션 요소 추가, 데이터 조회 기능 강화

### 5-1. Domain & DB Schema Update
- [x] **[Domain]** `Quote` 엔티티 추가 (동기부여 명언 저장)
- [x] **[Domain]** `Retrospective` 엔티티 수정: `mainCategory`(주요 풀이 알고리즘), `isBookmarked`(즐겨찾기) 필드 추가
- [x] **[Domain]** `Student` 엔티티 수정: `consecutiveSolveDays`(연속 풀이 일수), `lastSolvedAt` 필드 추가

### 5-2. User & Profile Feature
- [x] **[Service]** 내 정보 수정 로직 (닉네임 변경, 비밀번호 검증 및 변경)
- [x] **[API]** 회원 정보 수정 API (`PATCH /api/v1/students/me`)

### 5-3. Dashboard & Quote Feature
- [x] **[Service]** 명언(Quote) 랜덤 조회 서비스 및 초기 데이터 시딩(Data Seeding)
- [x] **[Service]** 대시보드 로직 변경: '오늘 푼 문제' 목록 반환 추가
- [x] **[Service]** 통계 API 분리: 잔디(Heatmap) 및 카테고리별 통계 전용 API 구현

### 5-4. Retrospective Enhancement
- [x] **[Infra]** QueryDSL 설정 (동적 쿼리 및 정렬 기능 구현용)
- [x] **[Service]** 회고 목록 조회 고도화: 페이징, 정렬(최신/오래된/즐겨찾기), 카테고리 필터링 적용
- [x] **[API]** 회고 즐겨찾기 토글 API (`POST /.../bookmark`)

### 5-5. Leaderboard (Ranking)
- [x] **[Service]** 랭킹 집계 로직 (Rating 기준 상위 100명 조회)
- [x] **[API]** 랭킹 조회 API (`GET /api/v1/ranks`)
