package com.didimlog.global.util

import com.didimlog.domain.enums.PrimaryLanguage

/**
 * 코드에서 프로그래밍 언어를 자동 감지하는 유틸리티
 * 백준 온라인 저지 지원 언어와 동기화되어 있다.
 *
 * 점수 기반 시스템을 사용하여 정확도를 향상시켰다.
 * 각 언어별로 고유한 키워드에 가중치를 부여하여 가장 높은 점수의 언어를 반환한다.
 *
 * 지원 언어 (BOJ 기준):
 * - C, CPP, CSHARP, GO, JAVA, JAVASCRIPT, KOTLIN, PYTHON, R, RUBY, SCALA, SWIFT, TEXT
 * - Python2/3는 PYTHON으로 통합
 */
object CodeLanguageDetector {
    private const val DEFAULT_LANGUAGE = "TEXT"
    private const val MIN_SCORE_THRESHOLD = 2  // 짧은 코드도 감지할 수 있도록 임계치를 2로 낮춤

    /**
     * 언어별 키워드와 점수를 정의하는 데이터 클래스
     */
    private data class LanguagePattern(
        val keywords: List<String>,
        val weight: Int = 1
    )

    /**
     * 각 언어의 키워드 패턴 정의
     * 고유한 키워드일수록 높은 가중치를 부여한다.
     * Kotlin은 고유 키워드(fun, val, var)에 높은 가중치를 부여하여 Java와 구분한다.
     */
    private val languagePatterns = mapOf(
        "KOTLIN" to LanguagePattern(
            keywords = listOf(
                "fun ", "val ", "var ", "data class", "package ", "noinline", "crossinline",
                "companion object", "object ", "sealed class", "enum class", "when (",
                "?.", "!!", "let {", "apply {", "run {", "also {", "use {",
                "import kotlin", "kotlinx.", "listOf(", "mapOf(", "setOf(",
                "println(", "fun ", ": Int", ": String", ": Boolean", "= " // Kotlin 함수 시그니처 패턴
            ),
            weight = 1
        ),
        "JAVA" to LanguagePattern(
            keywords = listOf(
                "public class", "private class", "protected class", "class ",
                "public static void main", "static void main", "public static",
                "System.out.println", "System.out.print", "import java.",
                "new ", "extends ", "implements ", "@Override", "@Deprecated",
                "ArrayList<", "HashMap<", "String[]", "int[]"
            ),
            weight = 1
        ),
        "PYTHON" to LanguagePattern(
            keywords = listOf(
                "def ", "elif ", "if __name__ == \"__main__\"", "if __name__ == '__main__'",
                "import sys", "import os", "from ", "lambda ", "yield ", "async def",
                "with ", "as ", "try:", "except ", "raise ", "__init__", "__str__",
                "self.", "range(", "len(", "str(", "int(", "list(", "dict(",
                "print(", "input(", "return ", "pass "
            ),
            weight = 1
        ),
        "CPP" to LanguagePattern(
            keywords = listOf(
                "#include", "using namespace std", "std::", "cout <<", "cin >>",
                "vector<", "string ", "pair<", "map<", "set<", "queue<", "stack<",
                "auto ", "nullptr", "constexpr", "template<", "class "
            ),
            weight = 1
        ),
        "C" to LanguagePattern(
            keywords = listOf(
                "#include", "int main(", "void main(", "printf(", "scanf(",
                "malloc(", "free(", "sizeof(", "typedef ", "struct ", "enum "
            ),
            weight = 1
        ),
        "JAVASCRIPT" to LanguagePattern(
            keywords = listOf(
                "function ", "const ", "let ", "var ", "console.log", "console.error",
                "=> ", "async ", "await ", "Promise", "fetch(", "document.",
                "require(", "module.exports", "export ", "import {", "from '"
            ),
            weight = 1
        ),
        "GO" to LanguagePattern(
            keywords = listOf(
                "package ", "func ", "import \"fmt\"", "fmt.Println", "fmt.Print",
                "fmt.Printf", "defer ", "go ", "chan ", "select ", "interface{}",
                ":= ", "make(", "append("
            ),
            weight = 1
        ),
        "CSHARP" to LanguagePattern(
            keywords = listOf(
                "using System", "namespace ", "class ", "static void Main",
                "Console.WriteLine", "Console.Write", "var ", "public ", "private ",
                "protected ", "List<", "Dictionary<", "using System."
            ),
            weight = 1
        ),
        "SWIFT" to LanguagePattern(
            keywords = listOf(
                "import Swift", "import Foundation", "func ", "var ", "let ",
                "print(", "guard ", "defer ", "if let ", "guard let ", "?",
                "class ", "struct ", "enum ", "extension ", "protocol "
            ),
            weight = 1
        ),
        "R" to LanguagePattern(
            keywords = listOf(
                "<-", "cat(", "print(", "library(", "require(", "data.frame",
                "read.csv", "read.table", "function(", "<- ", "-> ", "%% "
            ),
            weight = 1
        ),
        "RUBY" to LanguagePattern(
            keywords = listOf(
                "def ", "end", "puts ", "require ", "class ", "do ", "each do",
                "each {", "map {", "select {", "reduce {", "|", "nil", "true", "false"
            ),
            weight = 1
        ),
        "SCALA" to LanguagePattern(
            keywords = listOf(
                "object ", "import scala.", "def ", "val ", "var ", "println(",
                "class ", "trait ", "case class", "match {", "case ", "Some(",
                "None", "Option[", "List[", "Map["
            ),
            weight = 1
        )
    )

    /**
     * 코드 내용을 분석하여 프로그래밍 언어를 감지한다.
     * 점수 기반 시스템을 사용하여 가장 높은 점수의 언어를 반환한다.
     *
     * @param code 사용자 코드
     * @return 감지된 언어 (대문자, 예: "JAVA", "PYTHON", "KOTLIN")
     */
    fun detect(code: String): String {
        val normalizedCode = code.trim()
        if (normalizedCode.isEmpty()) {
            return DEFAULT_LANGUAGE
        }

        // 각 언어별 점수 계산
        val scores = mutableMapOf<String, Int>()
        
        languagePatterns.forEach { (language, pattern) ->
            var score = 0
            pattern.keywords.forEach { keyword ->
                // 키워드 등장 횟수에 가중치를 곱하여 점수 계산
                val count = normalizedCode.split(keyword).size - 1
                score += count * pattern.weight
            }
            scores[language] = score
        }

        // 특별한 패턴 처리
        adjustScoresForSpecialCases(normalizedCode, scores)

        // 가장 높은 점수의 언어 찾기
        val maxScore = scores.values.maxOrNull() ?: 0
        
        // 임계치 미만이면 TEXT 반환
        if (maxScore < MIN_SCORE_THRESHOLD) {
            return DEFAULT_LANGUAGE
        }

        // 동점인 경우 우선순위 적용
        val candidates = scores.filter { it.value == maxScore }.keys
        if (candidates.size > 1) {
            return resolveTie(candidates)
        }

        return candidates.firstOrNull() ?: DEFAULT_LANGUAGE
    }

    /**
     * 특별한 케이스를 처리하여 점수를 조정한다.
     * - import 키워드는 여러 언어에서 사용되므로 가중치를 낮춘다.
     * - Python의 import만 있는 경우 점수를 낮춘다.
     * - Python의 def 뒤에 : 가 있으면 Python 점수 증가 (Ruby/Scala와 구분)
     */
    private fun adjustScoresForSpecialCases(code: String, scores: MutableMap<String, Int>) {
        // Python: def 뒤에 : 가 있으면 Python 점수 증가 (Ruby/Scala와 구분)
        val hasPythonDefPattern = code.contains(Regex("def\\s+\\w+.*:"))
        if (hasPythonDefPattern) {
            scores["PYTHON"] = (scores["PYTHON"] ?: 0) + 3
        }

        // Python: import만 있고 def나 다른 Python 특유 키워드가 없으면 점수 감소
        val hasPythonSpecific = code.contains("def ") || code.contains("elif ") || 
                                 code.contains("if __name__") || code.contains("lambda ") ||
                                 code.contains("pass ") || code.contains("return ")
        val hasOnlyImport = code.contains("import ") && !hasPythonSpecific
        
        if (hasOnlyImport) {
            scores["PYTHON"] = (scores["PYTHON"] ?: 0) - 2
        }

        // Kotlin: import만 있는 경우 점수 감소 (Java와 구분)
        val hasKotlinSpecific = code.contains("fun ") || code.contains("val ") || 
                                code.contains("var ") || code.contains("data class") ||
                                code.contains("companion object") || code.contains("when (")
        val hasOnlyKotlinImport = code.contains("import ") && !hasKotlinSpecific
        
        if (hasOnlyKotlinImport) {
            scores["KOTLIN"] = (scores["KOTLIN"] ?: 0) - 1
        }

        // Java: import만 있는 경우 점수 감소
        val hasJavaSpecific = code.contains("public class") || code.contains("public static") ||
                              code.contains("System.out.println") || code.contains("import java.")
        val hasOnlyJavaImport = code.contains("import ") && !hasJavaSpecific
        
        if (hasOnlyJavaImport) {
            scores["JAVA"] = (scores["JAVA"] ?: 0) - 1
        }

        // C와 C++ 구분: C++ 특유의 키워드가 있으면 C 점수 감소
        val hasCppFeatures = code.contains("using namespace") || code.contains("std::") ||
                             code.contains("cout") || code.contains("vector<") ||
                             code.contains("string ") || code.contains("::")
        
        if (hasCppFeatures) {
            scores["C"] = (scores["C"] ?: 0) - 5 // C++가 더 강하게 우선
        }
    }

    /**
     * 동점인 경우 우선순위를 적용하여 결정한다.
     * 더 구체적인 패턴을 가진 언어를 우선한다.
     */
    private fun resolveTie(candidates: Set<String>): String {
        // 우선순위: Kotlin > Java > Python > C++ > C > 기타
        val priority = listOf("KOTLIN", "JAVA", "PYTHON", "CPP", "C", "JAVASCRIPT", "GO", "CSHARP", "SWIFT", "R", "RUBY", "SCALA")
        
        for (lang in priority) {
            if (candidates.contains(lang)) {
                return lang
            }
        }
        
        return candidates.firstOrNull() ?: DEFAULT_LANGUAGE
    }

    /**
     * 감지된 언어를 PrimaryLanguage enum으로 변환한다.
     *
     * @param code 사용자 코드
     * @return PrimaryLanguage enum (없으면 TEXT)
     */
    fun detectAsPrimaryLanguage(code: String): PrimaryLanguage {
        val detected = detect(code)
        return when (detected) {
            "C" -> PrimaryLanguage.C
            "CPP" -> PrimaryLanguage.CPP
            "CSHARP" -> PrimaryLanguage.CSHARP
            "GO" -> PrimaryLanguage.GO
            "JAVA" -> PrimaryLanguage.JAVA
            "JAVASCRIPT" -> PrimaryLanguage.JAVASCRIPT
            "KOTLIN" -> PrimaryLanguage.KOTLIN
            "PYTHON" -> PrimaryLanguage.PYTHON
            "R" -> PrimaryLanguage.R
            "RUBY" -> PrimaryLanguage.RUBY
            "SCALA" -> PrimaryLanguage.SCALA
            "SWIFT" -> PrimaryLanguage.SWIFT
            else -> PrimaryLanguage.TEXT
        }
    }
}
