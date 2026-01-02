# 지원 프로그래밍 언어 목록 (Supported Programming Languages)

이 문서는 DidimLog에서 지원하는 프로그래밍 언어 목록을 정의합니다.
프론트엔드와 백엔드 간 언어 목록을 통일하여 일관된 사용자 경험을 제공합니다.

---

## 지원 언어 목록

백준 온라인 저지(BOJ) 지원 언어와 동기화되어 있습니다.

| 언어 코드 | 표시명 | 설명 | BOJ 지원 |
|---------|--------|------|---------|
| `C` | C | C 프로그래밍 언어 | ✅ |
| `CPP` | C++ | C++ 프로그래밍 언어 | ✅ |
| `CSHARP` | C# | C# 프로그래밍 언어 | ✅ |
| `GO` | Go | Go 프로그래밍 언어 | ✅ |
| `JAVA` | Java | Java 프로그래밍 언어 | ✅ |
| `JAVASCRIPT` | JavaScript | JavaScript 프로그래밍 언어 | ✅ |
| `KOTLIN` | Kotlin | Kotlin 프로그래밍 언어 | ✅ |
| `PYTHON` | Python | Python 프로그래밍 언어 (Python2/3 통합) | ✅ |
| `R` | R | R 프로그래밍 언어 | ✅ |
| `RUBY` | Ruby | Ruby 프로그래밍 언어 | ✅ |
| `SCALA` | Scala | Scala 프로그래밍 언어 | ✅ |
| `SWIFT` | Swift | Swift 프로그래밍 언어 | ✅ |
| `TEXT` | Text | 언어를 특정할 수 없는 경우 (기본값) | - |

**총 13개 언어 지원 (BOJ 기준)**

---

## 언어 감지 로직

백엔드의 `CodeLanguageDetector` 유틸리티가 코드 내용을 분석하여 언어를 자동 감지합니다.

### 감지 규칙

- **C**: `#include`, `int main`, `printf(`, `scanf(`, `malloc(` (C++ 특유 키워드 없음)
- **C++**: `#include`, `int main`, `using namespace std`, `std::`, `cout`, `cin`, `vector<`
- **C#**: `using ` + `namespace `, `using System`, `class ` + `static void Main`
- **Go**: `package ` + `func `, `import "fmt"`, `fmt.Println`
- **Java**: `public class`, `public static`, `System.out.println`, `import java.`
- **JavaScript**: `function `, `const `, `let `, `var `, `console.log`
- **Kotlin**: `fun `, `val `, `class ` + `:`, `package ` + `import kotlin`
- **Python**: `def `, `import ` + `print(`, `if __name__` (Python2/3 통합)
- **R**: `<-`, `cat(`, `library(`, `data.frame`, `read.csv`
- **Ruby**: `def ` + `end`, `puts `, `require `, `class ` + `end`, `each do`
- **Scala**: `object `, `def ` + `:`, `val ` + `:`, `import scala.`
- **Swift**: `import Swift`, `func ` + `var `, `print(` + `let `

---

## 프론트엔드 동기화

프론트엔드의 `LANGUAGE_LABELS` 객체는 다음 형식을 따라야 합니다:

```typescript
const LANGUAGE_LABELS: Record<string, string> = {
    C: 'C',
    CPP: 'C++',
    CSHARP: 'C#',
    GO: 'Go',
    JAVA: 'Java',
    JAVASCRIPT: 'JavaScript',
    KOTLIN: 'Kotlin',
    PYTHON: 'Python',
    R: 'R',
    RUBY: 'Ruby',
    SCALA: 'Scala',
    SWIFT: 'Swift',
    TEXT: 'Text',
};
```

---

## 백엔드 구현

### PrimaryLanguage Enum

```kotlin
enum class PrimaryLanguage(val value: String) {
    C("c"),
    CPP("cpp"),
    CSHARP("csharp"),
    GO("go"),
    JAVA("java"),
    JAVASCRIPT("javascript"),
    KOTLIN("kotlin"),
    PYTHON("python"),
    R("r"),
    RUBY("ruby"),
    SCALA("scala"),
    SWIFT("swift"),
    TEXT("text");
}
```

### CodeLanguageDetector

```kotlin
object CodeLanguageDetector {
    fun detect(code: String): String {
        // 언어 감지 로직
        // 반환값: "JAVA", "PYTHON", "KOTLIN", etc. (대문자)
    }
}
```

---

## AI 리뷰 지원

모든 지원 언어에 대해 AI 한 줄 리뷰가 생성됩니다.
언어별 특성을 고려한 리뷰가 제공됩니다 (예: Java의 Stream API, Python의 리스트 컴프리헨션 등).

---

## 마크다운 코드 블록 형식

템플릿 생성 시 언어 코드는 마크다운 코드 블록 형식으로 변환됩니다:

- `C` → `c`
- `CPP` → `cpp`
- `CSHARP` → `csharp`
- `GO` → `go`
- `JAVA` → `java`
- `JAVASCRIPT` → `javascript`
- `KOTLIN` → `kotlin`
- `PYTHON` → `python`
- `R` → `r`
- `RUBY` → `ruby`
- `SCALA` → `scala`
- `SWIFT` → `swift`
- `TEXT` → `text`

---

## 참고사항

- 언어 감지는 휴리스틱 기반이므로, 100% 정확하지 않을 수 있습니다.
- 감지 실패 시 기본값으로 `TEXT`가 사용됩니다.
- 사용자는 필요 시 수동으로 언어를 선택할 수 있습니다.


