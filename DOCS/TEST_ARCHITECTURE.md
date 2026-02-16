# 테스트/패키지 구조 가이드

## 1) 목표
- 단위 테스트(`src/test`)와 통합 테스트(`src/integrationTest`)를 분리해 실행 시간과 책임을 명확히 한다.
- 테스트 패키지를 프로덕션 패키지(`src/main/kotlin`)와 동일한 구조로 맞춘다.
- 커버리지 리포트를 기본 제공해 누락 영역을 빠르게 확인한다.

## 2) 디렉토리 규칙
- 프로덕션 코드: `src/main/kotlin/com/didimlog/**`
- 단위 테스트: `src/test/kotlin/com/didimlog/**`
- 통합 테스트: `src/integrationTest/kotlin/com/didimlog/**`
- 테스트 설정 리소스: `src/test/resources/**`
- 통합 테스트 전용 리소스(필요 시): `src/integrationTest/resources/**`

## 3) 테스트 분류 기준
- 단위 테스트 (`*Test`)
  - Spring Context 없이 실행 가능해야 한다.
  - 외부 IO(DB, 네트워크, Redis, 실제 API) 없이 Mock/Fake로 검증한다.
- 통합 테스트 (`*IntegrationTest` 또는 `*IT`)
  - `@SpringBootTest`, `@DataMongoTest`, 실제 인프라 연동, 보안 체인, 리포지토리 쿼리 검증을 포함한다.

## 4) 패키지 정렬 규칙
- 테스트 파일은 반드시 대상 클래스와 같은 루트 패키지에 둔다.
  - 예: `com.didimlog.application.study.StudyService` -> `src/test/kotlin/com/didimlog/application/study/StudyServiceTest.kt`
  - 예: `com.didimlog.application.problem.ProblemService` -> `src/test/kotlin/com/didimlog/application/problem/ProblemServiceTest.kt`
  - 예: `com.didimlog.application.student.StudentSignupService` -> `src/test/kotlin/com/didimlog/application/student/StudentSignupServiceTest.kt`
  - 예: 통합 시나리오 -> `src/integrationTest/kotlin/com/didimlog/application/study/StudyIntegrationTest.kt`
- `src/test`에 통합 테스트를 두지 않는다.
- `application` 루트에는 기능 분류가 어려운 파일을 두지 않는다. 아래처럼 하위 패키지로 분리한다.
  - 문제 조회/동기화: `application.problem`
  - 대량 수집 배치/잡 상태: `application.problem.collector`
  - 학생 가입/프로필: `application.student`

## 5) 실행 커맨드
- 단위 테스트: `./gradlew test`
- 통합 테스트: `./gradlew integrationTest`
- 전체 검증: `./gradlew check`
- 커버리지 리포트
  - 단위: `./gradlew jacocoTestReport`
  - 통합: `./gradlew jacocoIntegrationTestReport`
  - 병합: `./gradlew jacocoMergedReport`

## 6) 커버리지 기준
- 집계 범위(Core Coverage Scope)
  - `application`: `admin`, `auth`, `dashboard`, `feedback`, `log`, `member`, `notice`, `problem(collector 제외)`, `quote`, `ranking`, `recommendation`, `retrospective`, `storage`, `student`, `study`, `utils`
  - `domain`: 루트, `enums`, `repository`, `template`, `validation`, `valueobject`
  - `global`: `auth`, `config`, `system`, `util`
- 기준선(Baseline, 2026-02-16, Core Scope)
  - Unit(Line): 69.01%
  - Integration(Line): 28.81%
  - Merged(Line): 77.39%
- 단기 목표
  - Merged(Line) 75~80 유지
- 원칙
  - 신규 기능 추가 시 해당 패키지의 Unit Line 커버리지를 기존보다 낮추지 않는다.
  - 버그 수정 시 회귀 테스트를 반드시 추가한다.

## 7) 리포트 확인 경로
- 단위: `build/reports/jacoco/test/html/index.html`
- 통합: `build/reports/jacoco/jacocoIntegrationTestReport/html/index.html`
- 병합: `build/reports/jacoco/jacocoMergedReport/html/index.html`

## 8) 신규 코드 체크리스트
- 새 서비스/유틸/도메인 로직 추가 시 단위 테스트를 같은 패키지에 추가했는가?
- DB 쿼리/보안/컨텍스트 기반 동작이면 통합 테스트를 `src/integrationTest`에 추가했는가?
- 테스트 파일명이 규칙(`*Test`, `*IntegrationTest`)을 따르는가?
- 변경 후 `./gradlew test integrationTest`와 커버리지 리포트를 확인했는가?
