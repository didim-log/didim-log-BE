package com.didimlog.global.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CodeLanguageDetector 테스트")
class CodeLanguageDetectorTest {

    @Test
    @DisplayName("Kotlin 코드를 정확하게 감지한다")
    fun `Kotlin 코드 감지`() {
        // given
        val kotlinCode = """
            fun main() {
                val x = 10
                var y = 20
                data class Person(val name: String)
                println(x + y)
            }
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(kotlinCode)

        // then
        assertThat(result).isEqualTo("KOTLIN")
    }

    @Test
    @DisplayName("긴 Kotlin 코드를 Python으로 오감지하지 않는다")
    fun `긴 Kotlin 코드 감지 - Python 오감지 방지`() {
        // given - import가 많아도 Kotlin으로 감지되어야 함
        val kotlinCode = """
            import java.util.*
            import kotlin.collections.*
            import kotlinx.coroutines.*
            
            fun solve(n: Int): Int {
                val list = listOf(1, 2, 3)
                val map = mapOf("a" to 1, "b" to 2)
                var result = 0
                
                list.forEach { num ->
                    result += num
                }
                
                return result
            }
            
            fun main() {
                val scanner = Scanner(System.`in`)
                val n = scanner.nextInt()
                println(solve(n))
            }
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(kotlinCode)

        // then
        assertThat(result).isEqualTo("KOTLIN")
    }

    @Test
    @DisplayName("Java 코드를 정확하게 감지한다")
    fun `Java 코드 감지`() {
        // given
        val javaCode = """
            import java.util.*;
            
            public class Main {
                public static void main(String[] args) {
                    System.out.println("Hello");
                }
            }
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(javaCode)

        // then
        assertThat(result).isEqualTo("JAVA")
    }

    @Test
    @DisplayName("긴 Java 코드를 Python으로 오감지하지 않는다")
    fun `긴 Java 코드 감지 - Python 오감지 방지`() {
        // given
        val javaCode = """
            import java.util.*;
            import java.io.*;
            
            public class Solution {
                private int n;
                private List<Integer> list;
                
                public Solution(int n) {
                    this.n = n;
                    this.list = new ArrayList<>();
                }
                
                public void process() {
                    for (int i = 0; i < n; i++) {
                        list.add(i);
                    }
                }
                
                public static void main(String[] args) {
                    Scanner sc = new Scanner(System.in);
                    int n = sc.nextInt();
                    Solution sol = new Solution(n);
                    sol.process();
                }
            }
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(javaCode)

        // then
        assertThat(result).isEqualTo("JAVA")
    }

    @Test
    @DisplayName("Python 코드를 정확하게 감지한다")
    fun `Python 코드 감지`() {
        // given
        val pythonCode = """
            def solve(n):
                result = 0
                for i in range(n):
                    result += i
                return result
            
            if __name__ == "__main__":
                n = int(input())
                print(solve(n))
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(pythonCode)

        // then
        assertThat(result).isEqualTo("PYTHON")
    }

    @Test
    @DisplayName("import만 있는 경우 Python으로 오감지하지 않는다")
    fun `import만 있는 경우 처리`() {
        // given - import만 있고 Python 특유 키워드가 없으면 TEXT 반환
        val codeWithOnlyImport = """
            import java.util.*
            import java.io.*
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(codeWithOnlyImport)

        // then
        assertThat(result).isNotEqualTo("PYTHON")
    }

    @Test
    @DisplayName("Kotlin의 companion object를 감지한다")
    fun `Kotlin companion object 감지`() {
        // given
        val kotlinCode = """
            class Test {
                companion object {
                    const val VALUE = 10
                }
            }
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(kotlinCode)

        // then
        assertThat(result).isEqualTo("KOTLIN")
    }

    @Test
    @DisplayName("Kotlin의 when 문을 감지한다")
    fun `Kotlin when 문 감지`() {
        // given
        val kotlinCode = """
            fun test(x: Int) {
                when (x) {
                    1 -> println("one")
                    2 -> println("two")
                    else -> println("other")
                }
            }
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(kotlinCode)

        // then
        assertThat(result).isEqualTo("KOTLIN")
    }

    @Test
    @DisplayName("C++ 코드를 정확하게 감지한다")
    fun `C++ 코드 감지`() {
        // given
        val cppCode = """
            #include <iostream>
            #include <vector>
            using namespace std;
            
            int main() {
                vector<int> v;
                cout << "Hello" << endl;
                return 0;
            }
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(cppCode)

        // then
        assertThat(result).isEqualTo("CPP")
    }

    @Test
    @DisplayName("JavaScript 코드를 정확하게 감지한다")
    fun `JavaScript 코드 감지`() {
        // given
        val jsCode = """
            function solve(n) {
                const result = [];
                for (let i = 0; i < n; i++) {
                    result.push(i);
                }
                console.log(result);
            }
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(jsCode)

        // then
        assertThat(result).isEqualTo("JAVASCRIPT")
    }

    @Test
    @DisplayName("빈 코드는 TEXT를 반환한다")
    fun `빈 코드 처리`() {
        // when
        val result = CodeLanguageDetector.detect("")

        // then
        assertThat(result).isEqualTo("TEXT")
    }

    @Test
    @DisplayName("인식할 수 없는 코드는 TEXT를 반환한다")
    fun `인식 불가능한 코드 처리`() {
        // given
        val unknownCode = "This is just plain text without any programming keywords."

        // when
        val result = CodeLanguageDetector.detect(unknownCode)

        // then
        assertThat(result).isEqualTo("TEXT")
    }

    @Test
    @DisplayName("Python의 lambda를 감지한다")
    fun `Python lambda 감지`() {
        // given
        val pythonCode = """
            numbers = [1, 2, 3, 4]
            result = list(map(lambda x: x * 2, numbers))
            print(result)
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(pythonCode)

        // then
        assertThat(result).isEqualTo("PYTHON")
    }

    @Test
    @DisplayName("Kotlin과 Java가 혼재된 경우 Kotlin을 우선한다")
    fun `Kotlin과 Java 혼재 시 Kotlin 우선`() {
        // given - Kotlin과 Java 패턴이 모두 있지만 Kotlin 키워드가 더 많음
        val mixedCode = """
            import java.util.*
            
            fun main() {
                val list = ArrayList<Int>()
                println("Kotlin")
            }
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(mixedCode)

        // then
        assertThat(result).isEqualTo("KOTLIN")
    }

    @Test
    @DisplayName("Go 코드를 정확하게 감지한다")
    fun `Go 코드 감지`() {
        // given
        val goCode = """
            package main
            import "fmt"
            
            func main() {
                fmt.Println("Hello")
            }
        """.trimIndent()

        // when
        val result = CodeLanguageDetector.detect(goCode)

        // then
        assertThat(result).isEqualTo("GO")
    }
}
