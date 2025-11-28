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
```

-----

### 📄 파일 2: `SECRET_DOCS/DOMAIN.md`

AI가 비즈니스 로직을 구현할 때 참고할 데이터 구조 및 설계도입니다.

````markdown
# Domain Modeling & Schema Design

## 1. 핵심 도메인 구조도 (Aggregate & Schema)

MongoDB의 Embedding(임베딩)을 활용하여 읽기 성능을 최적화하고, 용량이 큰 회고(Retrospective)는 분리하여 설계함.

```mermaid
erDiagram
    %% Aggregate Root: Student
    STUDENT_COLLECTION {
        ObjectId _id PK
        Object nickname "VO: 닉네임"
        Object boj_id "VO: 백준ID"
        String tier "Enum: GOLD_3"
        Array solutions "First-Class Collection (Embedded)"
    }

    %% Embedded Value Object: Solution
    SOLUTION_VO {
        ObjectId problem_id
        String result "SUCCESS/FAIL"
        Duration time_taken
        DateTime solved_at
    }

    %% Aggregate Root: Problem (Reference Data)
    PROBLEM_COLLECTION {
        ObjectId _id PK
        String title
        String category "DFS/BFS"
        Object difficulty "VO"
        String url
    }

    %% Aggregate Root: Retrospective (Separate for Performance)
    RETROSPECTIVE_COLLECTION {
        ObjectId _id PK
        ObjectId student_id FK
        ObjectId problem_id FK
        String content "Markdown Text"
        DateTime created_at
    }

    STUDENT_COLLECTION ||--o{ SOLUTION_VO : "contains (Embedded)"
    STUDENT_COLLECTION ||--o{ RETROSPECTIVE_COLLECTION : "owns (Referenced)"
    PROBLEM_COLLECTION ||--o{ SOLUTION_VO : "linked by ID"
````

## 2\. 상세 도메인 코드 (Kotlin Reference)

비즈니스 로직은 Service가 아닌 Domain 객체 내부에 위치해야 한다.

### A. Value Objects (원시값 포장)

```kotlin
@JvmInline
value class Nickname(val value: String) {
    init {
        require(value.isNotBlank()) { "닉네임은 필수입니다." }
        require(value.length in 2..20) { "닉네임은 2자 이상 20자 이하여야 합니다." }
    }
}

@JvmInline
value class BojId(val value: String) {
    init {
        require(value.matches(Regex("^[a-zA-Z0-9_]+$"))) { "유효하지 않은 BOJ ID 형식입니다." }
    }
}

enum class Tier(val level: Int) {
    BRONZE(1), SILVER(2), GOLD(3), PLATINUM(4);
    fun next(): Tier = entries.find { it.level == this.level + 1 } ?: this
    fun isNotMax(): Boolean = this != PLATINUM
}

enum class ProblemResult { SUCCESS, FAIL, TIME_OVER }
```

### B. Solution & Solutions (일급 컬렉션)

```kotlin
data class Solution(
    val problemId: String,
    val timeTaken: Long,
    val result: ProblemResult,
    val solvedAt: LocalDateTime = LocalDateTime.now()
) {
    fun isSuccess(): Boolean = result == ProblemResult.SUCCESS
}

class Solutions(
    private val items: MutableList<Solution> = mutableListOf()
) {
    fun add(solution: Solution) { items.add(solution) }
    fun calculateRecentSuccessRate(limit: Int = 10): Double {
        if (items.isEmpty()) return 0.0
        val recentItems = items.takeLast(limit)
        val successCount = recentItems.count { it.isSuccess() }
        return successCount.toDouble() / recentItems.size
    }
    fun getAll(): List<Solution> = items.toList()
}
```

### C. Student (Aggregate Root)

```kotlin
@Document(collection = "students")
class Student(
    @Id val id: String? = null,
    val nickname: Nickname,
    val bojId: BojId,
    private var currentTier: Tier,
    private val solutions: Solutions = Solutions()
) {
    fun solveProblem(problem: Problem, timeTakenSeconds: Long, isSuccess: Boolean) {
        val result = if (isSuccess) ProblemResult.SUCCESS else ProblemResult.FAIL
        solutions.add(Solution(problem.id!!, timeTakenSeconds, result))
        
        if (isSuccess && canLevelUp()) {
            levelUp()
        }
    }

    private fun canLevelUp(): Boolean = solutions.calculateRecentSuccessRate() >= 0.8 && currentTier.isNotMax()
    private fun levelUp() { this.currentTier = this.currentTier.next() }
    fun getTierInfo(): Tier = currentTier
}
```

### D. Problem & Retrospective

* **Problem:** 불변 데이터. `difficultyLevel`을 통해 `Student.tier`와 비교하는 로직 포함.
* **Retrospective:** `Student`와 분리된 Document. `content` 길이 검증 로직 포함.

## 3\. 설계 의도 (Design Rationale)

1.  **Embedding:** `Solution`을 `Student`에 내장하여 트랜잭션 복잡도를 줄이고 쓰기 성능 최적화.
2.  **Cohesion:** 티어 승급 로직(`canLevelUp`)을 데이터를 가진 `Student` 객체 내부에 배치.
3.  **Reference:** 대용량 텍스트인 회고(`Retrospective`)는 분리하여 조회 성능 확보.
4.  **Clean Code:** 모든 원시값 포장, Setter 금지, 생성자 유효성 검사 적용.
