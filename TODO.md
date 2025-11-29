# 🚀 DidimLog (Algo-LevelUp) Development Roadmap

이 문서는 AI 기반 자동화 개발(Cursor + Gemini)을 위한 마일스톤 관리 문서입니다.
각 체크박스는 하나의 독립적인 작업 단위(Commit)를 의미합니다.

---

## Phase 0: Environment & Infrastructure (Week 1)
> **Goal:** Spring Boot, MongoDB, Redis 환경 구축 및 기본 프로젝트 세팅

- [x] **[Setup]** Spring Boot 3.x (Kotlin) 프로젝트 초기화 (Dependencies: Web, MongoDB, Redis, Validation, Actuator, Test)
- [x] **[Config]** `docker-compose.yml` 작성 (MongoDB, Redis 컨테이너 구성, 데이터 영속성 설정)
- [x] **[Config]** MongoDB 연결 설정 (`application.yml`) 및 JpaAuditing(`@EnableMongoAuditing`) 설정
- [ ] **[Config]** 코드 품질 도구 설정 (Ktlint 또는 Checkstyle - 우아한 테크코스 컨벤션 적용)
- [x] **[Docs]** Swagger (OpenAPI 3.0) 설정

## Phase 1: Core Domain Implementation (Week 1-2)
> **Goal:** `SECRET_DOCS/DOMAIN.md`의 설계를 100% 코드로 구현 (클린 코드 원칙 준수)
> **Ref:** 모든 원시값 포장, 일급 컬렉션 사용, Setter 금지, 생성자 유효성 검사

### 1-1. Value Objects & Enums
- [x] **[Domain]** `Nickname` VO 구현 (유효성 검사: NotBlank, 길이 2~20)
- [x] **[Domain]** `BojId` VO 구현 (유효성 검사: Regex)
- [x] **[Domain]** `Tier` Enum 구현 (로직: `next()`, `isNotMax()`)
- [x] **[Domain]** `ProblemResult` Enum 구현 (SUCCESS, FAIL, TIME_OVER)

### 1-2. Solution & First-Class Collection
// 원시값 포장을 적용한 Solution & Solutions 구현 완료
- [x] **[Domain]** `Solution` 객체 구현 (불변 데이터, `isSuccess()` 메서드)
- [x] **[Domain]** `Solutions` 일급 컬렉션 구현 (MutableList 캡슐화, `calculateRecentSuccessRate` 로직)

### 1-3. Aggregate Roots
- [x] **[Domain]** `Problem` Document 구현 (난이도 비교 로직 `isHarderThan` 포함)
- [x] **[Domain]** `Student` Document 구현 (핵심 로직: `solveProblem`, `canLevelUp`, `levelUp`)
- [x] **[Domain]** `Retrospective` Document 구현 (회고 작성 및 내용 수정 로직)

### 1-4. Repository Layer
- [x] **[Infra]** `StudentRepository` (MongoRepository) 인터페이스 정의
- [x] **[Infra]** `ProblemRepository` (MongoRepository) 인터페이스 정의
- [x] **[Infra]** `RetrospectiveRepository` (MongoRepository) 인터페이스 정의

## Phase 2: Core Feature - Problem Solving (Week 3-4)
> **Goal:** 문제 데이터 수집, 문제 풀이 로직, 추천 시스템 구현

### 2-1. Problem & Data Sync
- [x] **[Infra]** Solved.ac 비공식 API 연동 클라이언트 (`WebClient` 활용) 구현
- [x] **[Service]** `ProblemService`: Solved.ac에서 문제 데이터 수집 및 DB 저장 (`upsert` 로직)
- [x] **[Service]** 사용자 초기 가입 시 Solved.ac 정보로 Tier 및 초기 데이터 동기화

### 2-2. Study Logic (Core)
- [x] **[Service]** `StudyService`: `solveProblem()` 트랜잭션 구현 (Student 상태 변경 및 저장)
- [x] **[Test]** `Student`가 문제를 풀고 조건 충족 시 티어가 승급되는지 검증하는 통합 테스트

### 2-3. Recommendation System
- [x] **[Service]** 문제 추천 알고리즘 구현 (Rule: User Tier + 1단계 난이도 중 안 푼 문제 랜덤 추천, 무한 성장 로직)
- [x] **[API]** 문제 추천 API (`GET /api/v1/problems/recommend`)
- [x] **[API]** 문제 풀이 결과 제출 API (`POST /api/v1/study/submit`)

## Phase 3: Retrospective & Dashboard (Week 5-6)
> **Goal:** 회고 작성 기능 및 메인 대시보드 API

- [x] **[Service]** `RetrospectiveService`: 회고 생성 및 수정 (Student ID 검증 포함)
- [x] **[API]** 회고 CRUD API 구현
- [x] **[Feature]** 회고 템플릿 생성기 (문제 정보 + 코드 블록 기본 마크다운 생성)
- [x] **[API]** 메인 대시보드 API (현재 티어, 최근 풀이 그래프, 추천 문제 요약)

## Phase 4: Frontend & Interaction (Week 7-8)
> **Goal:** React 기반 UI 및 인터랙션 구현

- [ ] **[FE]** React(Vite) + TypeScript 프로젝트 세팅 및 Firebase Hosting 설정
- [ ] **[FE]** 로그인 페이지 (BOJ ID 입력) 및 메인 대시보드 UI
- [ ] **[FE]** 문제 풀이 화면 (타이머 기능, 종료 시 모달 팝업)
- [ ] **[FE]** 성공 시 `canvas-confetti` 이펙트 / 실패 시 Shake 애니메이션 구현
- [ ] **[FE]** Markdown 에디터 연동 (회고 작성용)
