package com.didimlog.global.util

import com.didimlog.domain.enums.PrimaryLanguage

/**
 * 코드에서 프로그래밍 언어를 자동 감지하는 유틸리티
 * 백준 온라인 저지 지원 언어와 동기화되어 있다.
 *
 * 지원 언어 (BOJ 기준):
 * - C, CPP, CSHARP, GO, JAVA, JAVASCRIPT, KOTLIN, PYTHON, R, RUBY, SCALA, SWIFT, TEXT
 * - Python2/3는 PYTHON으로 통합
 */
object CodeLanguageDetector {
    private const val DEFAULT_LANGUAGE = "TEXT"

    /**
     * 코드 내용을 분석하여 프로그래밍 언어를 감지한다.
     * 휴리스틱 기반으로 언어를 추론하며, PrimaryLanguage enum과 호환된다.
     *
     * @param code 사용자 코드
     * @return 감지된 언어 (대문자, 예: "JAVA", "PYTHON")
     */
    fun detect(code: String): String {
        val normalizedCode = code.trim()
        if (normalizedCode.isEmpty()) {
            return DEFAULT_LANGUAGE
        }

        // C는 C++보다 먼저 체크 (C++ 패턴이 C 패턴을 포함할 수 있음)
        if (isC(normalizedCode)) {
            return "C"
        }
        if (isCpp(normalizedCode)) {
            return "CPP"
        }
        if (isPython(normalizedCode)) {
            return "PYTHON"
        }
        if (isKotlin(normalizedCode)) {
            return "KOTLIN"
        }
        if (isJava(normalizedCode)) {
            return "JAVA"
        }
        if (isJavaScript(normalizedCode)) {
            return "JAVASCRIPT"
        }
        if (isGo(normalizedCode)) {
            return "GO"
        }
        if (isSwift(normalizedCode)) {
            return "SWIFT"
        }
        if (isCSharp(normalizedCode)) {
            return "CSHARP"
        }
        if (isR(normalizedCode)) {
            return "R"
        }
        if (isRuby(normalizedCode)) {
            return "RUBY"
        }
        if (isScala(normalizedCode)) {
            return "SCALA"
        }

        return DEFAULT_LANGUAGE
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

    private fun isPython(code: String): Boolean {
        return code.contains("def ") ||
            (code.contains("import ") && code.contains("print(")) ||
            code.contains("if __name__")
    }

    private fun isKotlin(code: String): Boolean {
        return code.contains("fun ") ||
            code.contains("val ") ||
            (code.contains("class ") && code.contains(":")) ||
            code.contains("package ") && code.contains("import kotlin")
    }

    private fun isJava(code: String): Boolean {
        return code.contains("public class") ||
            code.contains("public static") ||
            code.contains("System.out.println") ||
            code.contains("import java.")
    }

    private fun isC(code: String): Boolean {
        // C는 C++와 구분하기 위해 C++ 특유의 키워드가 없어야 함
        val hasCppFeatures = code.contains("using namespace") ||
            code.contains("std::") ||
            code.contains("cout") ||
            code.contains("cin") ||
            code.contains("string ") ||
            code.contains("vector<") ||
            code.contains("::")
        
        if (hasCppFeatures) {
            return false
        }
        
        return code.contains("#include") ||
            code.contains("int main") ||
            code.contains("printf(") ||
            code.contains("scanf(") ||
            code.contains("malloc(") ||
            code.contains("free(")
    }

    private fun isCpp(code: String): Boolean {
        return code.contains("#include") ||
            code.contains("int main") ||
            code.contains("using namespace std") ||
            code.contains("std::") ||
            code.contains("cout") ||
            code.contains("cin") ||
            code.contains("vector<") ||
            code.contains("string ")
    }

    private fun isJavaScript(code: String): Boolean {
        return code.contains("function ") ||
            code.contains("const ") ||
            code.contains("let ") ||
            code.contains("var ") ||
            code.contains("console.log")
    }

    private fun isGo(code: String): Boolean {
        return (code.contains("package ") && code.contains("func ")) ||
            code.contains("import \"fmt\"") ||
            code.contains("fmt.Println")
    }

    private fun isRust(code: String): Boolean {
        return (code.contains("fn ") && code.contains("let ")) ||
            code.contains("use std::") ||
            code.contains("println!")
    }

    private fun isSwift(code: String): Boolean {
        return code.contains("import Swift") ||
            code.contains("func ") && code.contains("var ") ||
            code.contains("print(") && code.contains("let ")
    }

    private fun isCSharp(code: String): Boolean {
        return (code.contains("using ") && code.contains("namespace ")) ||
            code.contains("using System") ||
            code.contains("class ") && code.contains("static void Main")
    }

    private fun isR(code: String): Boolean {
        return code.contains("<-") ||
            code.contains("<- ") ||
            code.contains("cat(") ||
            code.contains("print(") && code.contains("<-") ||
            code.contains("library(") ||
            code.contains("data.frame") ||
            code.contains("read.csv")
    }

    private fun isRuby(code: String): Boolean {
        return code.contains("def ") && code.contains("end") ||
            code.contains("puts ") ||
            code.contains("require ") ||
            code.contains("class ") && code.contains("end") ||
            code.contains("do ") && code.contains("end") ||
            code.contains("each do")
    }

    private fun isScala(code: String): Boolean {
        return code.contains("object ") ||
            code.contains("def ") && code.contains(":") ||
            code.contains("val ") && code.contains(":") ||
            code.contains("import scala.") ||
            code.contains("println(") && code.contains("object ")
    }
}

