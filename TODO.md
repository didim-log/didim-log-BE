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
