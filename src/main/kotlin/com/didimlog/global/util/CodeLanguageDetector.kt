package com.didimlog.global.util

import com.didimlog.domain.enums.PrimaryLanguage

/**
 * 코드에서 프로그래밍 언어를 자동 감지하는 유틸리티
 * 프론트엔드와 백엔드 간 언어 목록을 통일하기 위해 사용한다.
 *
 * 지원 언어:
 * - JAVA, PYTHON, KOTLIN, JAVASCRIPT, CPP, GO, RUST, SWIFT, CSHARP, TEXT
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

        if (isPython(normalizedCode)) {
            return "PYTHON"
        }
        if (isKotlin(normalizedCode)) {
            return "KOTLIN"
        }
        if (isJava(normalizedCode)) {
            return "JAVA"
        }
        if (isCpp(normalizedCode)) {
            return "CPP"
        }
        if (isJavaScript(normalizedCode)) {
            return "JAVASCRIPT"
        }
        if (isGo(normalizedCode)) {
            return "GO"
        }
        if (isRust(normalizedCode)) {
            return "RUST"
        }
        if (isSwift(normalizedCode)) {
            return "SWIFT"
        }
        if (isCSharp(normalizedCode)) {
            return "CSHARP"
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
            "JAVA" -> PrimaryLanguage.JAVA
            "PYTHON" -> PrimaryLanguage.PYTHON
            "KOTLIN" -> PrimaryLanguage.KOTLIN
            "JAVASCRIPT" -> PrimaryLanguage.JAVASCRIPT
            "CPP" -> PrimaryLanguage.CPP
            "GO" -> PrimaryLanguage.GO
            "RUST" -> PrimaryLanguage.RUST
            "SWIFT" -> PrimaryLanguage.SWIFT
            "CSHARP" -> PrimaryLanguage.CSHARP
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

    private fun isCpp(code: String): Boolean {
        return code.contains("#include") ||
            code.contains("int main") ||
            code.contains("using namespace std")
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
}

