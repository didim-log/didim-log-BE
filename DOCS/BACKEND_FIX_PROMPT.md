# 백엔드 수정 요청: 템플릿 렌더링 시 코드 블록 언어 태그 처리

## 문제 상황

현재 템플릿 렌더링 시 `{{language}}` 플레이스홀더가 문제 설명 언어(KO/EN)로 치환되고 있습니다.
하지만 마크다운 코드 블록의 언어 태그(예: ````kotlin`, ````java`)에서도 `{{language}}`를 사용하면,
프로그래밍 언어가 아닌 문제 언어(KO/EN)로 치환되어 구문 강조가 제대로 작동하지 않습니다.

**예시:**
- 템플릿에 ````{{language}}`가 있으면 → ````KO` 또는 ````EN`으로 렌더링됨
- 하지만 코드 블록은 프로그래밍 언어(java, kotlin, python 등)를 사용해야 함

## 요구사항

템플릿 렌더링 시 코드 블록 내의 언어 태그는 프로그래밍 언어로 치환되도록 수정해야 합니다.

### 해결 방법 제안

#### 방법 1: 코드 블록 감지 후 언어 태그는 프로그래밍 언어로 치환 (권장)

템플릿 렌더링 로직에서:
1. 마크다운 코드 블록 패턴을 감지 (예: ````language` 또는 ````language\n`)
2. 코드 블록 내의 `{{language}}`는 문제 언어가 아닌 **프로그래밍 언어**로 치환
3. 프로그래밍 언어는 사용자가 선택한 언어 또는 기본값 사용

**구현 예시:**
```kotlin
// 템플릿 렌더링 시
fun renderTemplate(templateContent: String, problem: Problem, programmingLanguage: String?): String {
    var rendered = templateContent
    
    // 일반 플레이스홀더 치환 (문제 언어 사용)
    rendered = rendered.replace("{{language}}", problem.language) // "ko" or "en"
    
    // 코드 블록 내의 언어 태그는 프로그래밍 언어로 치환
    // 패턴: ```{{language}} 또는 ```{{language}}\n
    val codeBlockPattern = Regex("```\\{\\{language\\}\\}(\\n|$)")
    val programmingLang = programmingLanguage?.lowercase() ?: "text"
    rendered = codeBlockPattern.replace(rendered) { matchResult ->
        "```$programmingLang${matchResult.groupValues[1]}"
    }
    
    // 나머지 플레이스홀더 치환
    rendered = rendered.replace("{{problemId}}", problem.id.toString())
    rendered = rendered.replace("{{problemTitle}}", problem.title)
    // ...
    
    return rendered
}
```

#### 방법 2: 별도의 플레이스홀더 사용

코드 블록에는 별도의 플레이스홀더를 사용:
- `{{language}}`: 문제 설명 언어 (ko, en) - 기존 유지
- `{{codeLanguage}}` 또는 `{{programmingLanguage}}`: 프로그래밍 언어 (java, kotlin, python 등)

**템플릿 예시:**
```markdown
# {{problemTitle}} ({{language}})

## 제출한 코드

```{{codeLanguage}}
여기에 코드를 작성하세요.
```
```

#### 방법 3: 코드 블록 내의 {{language}}는 치환하지 않음

코드 블록 내부의 `{{language}}`는 치환하지 않고 그대로 유지하거나,
사용자가 직접 프로그래밍 언어를 입력하도록 안내.

---

## 권장 해결 방법

**방법 1을 권장합니다.** 이유:
1. 기존 템플릿과의 호환성 유지
2. 사용자가 별도의 플레이스홀더를 기억할 필요 없음
3. 코드 블록 패턴을 감지하여 자동으로 처리 가능

## 지원 프로그래밍 언어 목록

`SUPPORTED_LANGUAGES.md`에 정의된 언어 코드를 사용:

| 언어 코드 | 마크다운 태그 | 설명 |
|---------|-------------|------|
| `C` | `c` | C 프로그래밍 언어 |
| `CPP` | `cpp` | C++ 프로그래밍 언어 |
| `CSHARP` | `csharp` | C# 프로그래밍 언어 |
| `GO` | `go` | Go 프로그래밍 언어 |
| `JAVA` | `java` | Java 프로그래밍 언어 |
| `JAVASCRIPT` | `javascript` | JavaScript 프로그래밍 언어 |
| `KOTLIN` | `kotlin` | Kotlin 프로그래밍 언어 |
| `PYTHON` | `python` | Python 프로그래밍 언어 |
| `R` | `r` | R 프로그래밍 언어 |
| `RUBY` | `ruby` | Ruby 프로그래밍 언어 |
| `SCALA` | `scala` | Scala 프로그래밍 언어 |
| `SWIFT` | `swift` | Swift 프로그래밍 언어 |
| `TEXT` | `text` | 언어를 특정할 수 없는 경우 (기본값) |

**언어 코드 → 마크다운 태그 변환 규칙:**
- 대문자 Enum 값 → 소문자 마크다운 태그
- 예: `JAVA` → `java`, `CPP` → `cpp`, `CSHARP` → `csharp`

## API 수정 사항

### TemplateController 수정

#### 1. `GET /api/v1/templates/{id}/render` 엔드포인트

**Request에 프로그래밍 언어 추가 (선택사항):**
```kotlin
// Query Parameters에 추가
- `programmingLanguage` (String, optional): 프로그래밍 언어 코드
  - 값: "JAVA", "KOTLIN", "PYTHON", "CPP" 등 (SUPPORTED_LANGUAGES.md 참고)
  - 기본값: "TEXT"
```

**또는 Request Body에 추가:**
```kotlin
data class TemplateRenderRequest(
    val problemId: Long,
    val programmingLanguage: String? = null // "JAVA", "KOTLIN", etc.
)
```

#### 2. `POST /api/v1/templates/preview` 엔드포인트

**Request Body에 프로그래밍 언어 추가:**
```kotlin
data class TemplatePreviewRequest(
    val templateContent: String,
    val problemId: Long,
    val programmingLanguage: String? = null // "JAVA", "KOTLIN", etc.
)
```

## 구현 체크리스트

- [ ] 템플릿 렌더링 로직에서 코드 블록 패턴 감지 (````language`)
- [ ] 코드 블록 내의 `{{language}}`를 프로그래밍 언어로 치환
- [ ] 일반 텍스트의 `{{language}}`는 문제 언어로 치환 (기존 동작 유지)
- [ ] 프로그래밍 언어가 제공되지 않으면 기본값 "text" 사용
- [ ] 언어 코드를 마크다운 태그로 변환 (대문자 → 소문자)
- [ ] API 엔드포인트에 프로그래밍 언어 파라미터 추가 (선택사항)
- [ ] 기존 템플릿과의 호환성 테스트
- [ ] 다양한 프로그래밍 언어로 테스트

## 테스트 케이스

### 테스트 1: 코드 블록이 있는 템플릿
**템플릿:**
```markdown
# {{problemTitle}}

## 제출한 코드

```{{language}}
여기에 코드를 작성하세요.
```
```

**입력:**
- problemId: 1000
- problemTitle: "A+B"
- language (문제 언어): "ko"
- programmingLanguage: "KOTLIN"

**예상 출력:**
```markdown
# A+B

## 제출한 코드

```kotlin
여기에 코드를 작성하세요.
```
```

### 테스트 2: 일반 텍스트의 {{language}}
**템플릿:**
```markdown
# {{problemTitle}} ({{language}})
```

**입력:**
- problemTitle: "A+B"
- language: "ko"

**예상 출력:**
```markdown
# A+B (ko)
```

### 테스트 3: 프로그래밍 언어가 없는 경우
**템플릿:**
```markdown
```{{language}}
코드
```
```

**입력:**
- programmingLanguage: null

**예상 출력:**
```markdown
```text
코드
```
```

## 참고 문서

- `SUPPORTED_LANGUAGES.md`: 지원 프로그래밍 언어 목록
- `API_SPECIFICATION.md`: 템플릿 API 명세서 (1280-1289줄 참고)

## 배포 전 확인사항

1. 기존 템플릿이 정상적으로 렌더링되는지 확인
2. 코드 블록의 구문 강조가 올바르게 작동하는지 확인
3. 다양한 프로그래밍 언어로 테스트
4. API 문서(Swagger) 업데이트

---

**작성일:** 2025-01-19
**우선순위:** 높음
**예상 작업 시간:** 2-3시간
