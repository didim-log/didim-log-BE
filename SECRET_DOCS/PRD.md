# PRD: 이런 나라도 PS 알고리즘을 체계적으로 잘 풀 수 있지 않을까? (DidimLog)

## 1. 프로젝트 개요 (Project Overview)
* **프로젝트명:** 디딤로그 (DidimLog) / 가칭: Algo-LevelUp
* **목적:** 백엔드 개발자 지망생이 코딩테스트 '골드' 난이도까지 체계적으로 성장하도록 돕는 학습 관리 서비스.
* **핵심 가치:**
    * **개인화:** 사용자 실력(Tier)에 맞는 문제 추천 (Cold Start 해결).
    * **학습 효율:** 타이머 및 피드백 시스템.
    * **기록화:** 비전공자도 쉽게 쓰는 블로그 회고 가이드.
    * **고품질 코드:** 우아한 테크코스 스타일의 클린 코드 훈련.

## 2. 사용자 타겟 (Target Audience)
* PS(문제 풀이)에 두려움을 가진 컴공 3~4학년.
* 문제를 풀고 끝내는 것이 아니라, 회고를 통해 성장하고 싶은 취준생.
* BOJ/프로그래머스 사용자.

## 3. 기술 스택 (Tech Stack)
* **Frontend:** React, Firebase Hosting
* **Backend:** Kotlin (Spring Boot), AWS EC2
* **Database:** MongoDB (메인), Redis (캐싱/세션)
* **Infra:** Docker, GitHub Actions

## 4. 기능 요구사항 (Functional Requirements)
### 4.1. 사용자 관리 & 인증
* Solved.ac API를 활용한 BOJ ID 연동.
* 사용자 현재 Tier 정보 동기화.

### 4.2. 문제 추천 시스템 (Core)
* **개념 학습:** 필수 알고리즘(DFS/BFS, DP 등) 개념 페이지.
* **단계별 추천:** 사용자 Tier 기반 맞춤 문제 추천 (User Tier + 1 level).
* **성장 규칙:** 해당 난이도를 수월하게 풀면 다음 단계 잠금 해제.

### 4.3. 문제 풀이 & 타이머
* 타이머 기능 (문제 풀이 소요 시간 기록).
* 성공 시 폭죽(Confetti), 실패 시 화면 흔들림 효과.

### 4.4. 회고 및 블로그 헬퍼
* 문제 해결 후 '회고 작성' 활성화.
* 마크다운 템플릿 자동 생성 (문제 링크, 핵심 로직, 코드 블록 포함).

## 5. 개발 컨벤션 및 품질 가이드 (Strict Rules)
**AI 및 개발자는 다음 규칙을 100% 준수해야 함.**

### 5.1. Git Commit Convention (AngularJS Style)
* `<type>(<scope>): <subject>`
* Body: What & Why 포함.

### 5.2. PR Template
* Title, Description(What/Why), Key Code(Before/After), Reason for Change 필수 포함.

### 5.3. Code Quality (Woowahan Tech Course Standard)
* **Style:** Google Java Style Guide.
* **Constraints:**
    * Indent depth는 1까지만 허용.
    * `else` 예약어 금지 (Early Return).
    * 모든 원시값(Primitive)과 문자열 포장(Wrapping).
    * 일급 컬렉션(First Class Collection) 사용.
    * 메서드 인자 3개 이하.
    * Getter/Setter 지양.

## 6. UI/UX 디자인 가이드
* **Concept:** Minimal & Clean (White/Gray/Blue).
* **Interaction:** 성공/실패에 대한 즉각적이고 감각적인 피드백.

## 7. 예상 일정
* 1~2주: 설계, 세팅, 도메인 구현.
* 3~4주: 문제 수집, 추천 로직, 핵심 기능.
* 5~6주: UI 구현 및 연동.
* 7~8주: 회고 기능, 배포, 리팩토링.
