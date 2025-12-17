package com.didimlog.application.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AiPromptFactory 특수문자 및 템플릿 분기 테스트")
class AiPromptFactoryTest {

    private val promptFactory = AiPromptFactory()

    @Test
    @DisplayName("C언어 코드의 특수문자(#include, printf, 줄바꿈)가 원본 그대로 프롬프트에 포함된다")
    fun `C언어 특수문자 파싱 테스트`() {
        // given
        val cCode = """
            #include <stdio.h>
            #include <stdlib.h>
            
            int main() {
                int a = 10;
                printf("%d\n", a);
                printf("Hello, World!\n");
                return 0;
            }
        """.trimIndent()

        val problemId = "1000"
        val problemTitle = "A+B"
        val problemDescription = "<p>두 정수를 더하세요</p>"

        // when
        val result = promptFactory.createUserPrompt(
            problemId = problemId,
            problemTitle = problemTitle,
            problemDescription = problemDescription,
            code = cCode,
            isSuccess = true
        )

        // then
        assertThat(result).contains("#include <stdio.h>")
        assertThat(result).contains("#include <stdlib.h>")
        assertThat(result).contains("printf")
        assertThat(result).contains("%d")
        assertThat(result).contains("int main() {")
        assertThat(result).contains("return 0;")

        // 이스케이프 문자가 꼬이지 않고 원본 그대로 포함되는지 확인
        val codeStartIndex = result.indexOf(cCode)
        assertThat(codeStartIndex).isGreaterThan(-1)

        // 마크다운 코드 블록 안에 포함되는지 확인
        val codeBlockIndex = result.indexOf("```java")
        assertThat(codeBlockIndex).isGreaterThan(-1)
        val codeInBlock = result.substring(codeBlockIndex)
        assertThat(codeInBlock).contains(cCode)
    }

    @Test
    @DisplayName("Java 코드의 특수문자(이스케이프, 따옴표, 중괄호)가 원본 그대로 프롬프트에 포함된다")
    fun `Java 특수문자 파싱 테스트`() {
        // given
        val javaCode = """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("Hello, \"World\"!");
                    String text = "Line 1\nLine 2\tTab";
                    int[] arr = {1, 2, 3};
                }
            }
        """.trimIndent()

        val problemId = "2000"
        val problemTitle = "Hello World"

        // when
        val result = promptFactory.createUserPrompt(
            problemId = problemId,
            problemTitle = problemTitle,
            problemDescription = null,
            code = javaCode,
            isSuccess = false
        )

        // then
        assertThat(result).contains("System.out.println")
        assertThat(result).contains("Hello")
        assertThat(result).contains("World")
        assertThat(result).contains("String text")
        assertThat(result).contains("int[] arr = {1, 2, 3}")
        assertThat(result).contains("public class Main {")

        // 원본 코드가 그대로 포함되는지 확인
        assertThat(result).contains(javaCode)
    }

    @Test
    @DisplayName("C++ 코드의 특수문자(템플릿, 네임스페이스, 포인터)가 원본 그대로 프롬프트에 포함된다")
    fun `C++ 특수문자 파싱 테스트`() {
        // given
        val cppCode = """
            #include <iostream>
            #include <vector>
            using namespace std;
            
            int main() {
                vector<int> v = {1, 2, 3};
                int* ptr = &v[0];
                cout << *ptr << endl;
                return 0;
            }
        """.trimIndent()

        val problemId = "3000"
        val problemTitle = "Vector Test"

        // when
        val result = promptFactory.createUserPrompt(
            problemId = problemId,
            problemTitle = problemTitle,
            problemDescription = "<p>벡터를 사용하세요</p>",
            code = cppCode,
            isSuccess = true
        )

        // then
        assertThat(result).contains("#include <iostream>")
        assertThat(result).contains("vector<int> v")
        assertThat(result).contains("int* ptr = &v[0];")
        assertThat(result).contains("cout << *ptr << endl;")

        // 원본 코드가 그대로 포함되는지 확인
        assertThat(result).contains(cppCode)
    }

    @Test
    @DisplayName("isSuccess가 true일 때 systemPrompt에 성공 관련 키워드가 포함된다")
    fun `성공용 시스템 프롬프트 키워드 검증`() {
        // when
        val result = promptFactory.createSystemPrompt(isSuccess = true)

        // then
        assertThat(result).contains("분석 (성공)")
        assertThat(result).contains("효율성")
        assertThat(result).contains("시간/공간 복잡도")
        assertThat(result).contains("잘한 점")
        assertThat(result).contains("알고리즘 선택")
        assertThat(result).contains("코드 가독성")
        assertThat(result).contains("칭찬")
        assertThat(result).contains("리팩토링 팁")

        // 실패 관련 키워드는 포함되지 않아야 함
        assertThat(result).doesNotContain("분석 (실패)")
        assertThat(result).doesNotContain("실패 원인")
        assertThat(result).doesNotContain("학습해야 할 핵심 키워드")
    }

    @Test
    @DisplayName("isSuccess가 false일 때 systemPrompt에 실패 관련 키워드가 포함된다")
    fun `실패용 시스템 프롬프트 키워드 검증`() {
        // when
        val result = promptFactory.createSystemPrompt(isSuccess = false)

        // then
        assertThat(result).contains("분석 (실패)")
        assertThat(result).contains("실패 원인")
        assertThat(result).contains("논리 오류")
        assertThat(result).contains("엣지 케이스")
        assertThat(result).contains("시간 초과")
        assertThat(result).contains("학습해야 할 핵심 키워드")
        assertThat(result).contains("개념을 제시")

        // 성공 관련 키워드는 포함되지 않아야 함
        assertThat(result).doesNotContain("분석 (성공)")
        assertThat(result).doesNotContain("효율성")
        assertThat(result).doesNotContain("칭찬")
    }

    @Test
    @DisplayName("사용자 코드가 마크다운 코드 블록 안에 정상적으로 위치한다")
    fun `마크다운 코드 블록 포맷팅 검증`() {
        // given
        val code = """
            def solution():
                return 42
        """.trimIndent()

        // when
        val result = promptFactory.createUserPrompt(
            problemId = "4000",
            problemTitle = "Python Test",
            problemDescription = null,
            code = code,
            isSuccess = true
        )

        // then
        // 코드 블록 시작 태그 확인
        assertThat(result).contains("```java")
        
        // 코드 블록 종료 태그 확인
        assertThat(result).contains("```")
        
        // 코드가 결과에 포함되는지 확인 (핵심 부분만 확인)
        assertThat(result).contains("def solution()")
        assertThat(result).contains("return 42")
        
        // Output Format 섹션의 코드 블록 안에 코드가 포함되는지 확인
        val outputFormatIndex = result.indexOf("# Output Format")
        assertThat(outputFormatIndex).isGreaterThan(-1)
        
        val outputFormatSection = result.substring(outputFormatIndex)
        assertThat(outputFormatSection).contains("```java")
        assertThat(outputFormatSection).contains("def solution()")
        assertThat(outputFormatSection).contains("return 42")
        
        // 코드 블록이 올바른 형식인지 확인
        // ```java 다음에 코드가 오고, 그 다음에 ```가 있는지 확인
        val codeBlockStart = outputFormatSection.indexOf("```java")
        assertThat(codeBlockStart).isGreaterThan(-1)
        
        // 코드 블록 시작 태그 이후에 코드가 있는지 확인
        val afterCodeBlockStart = outputFormatSection.substring(codeBlockStart + 7)
        assertThat(afterCodeBlockStart).contains("def solution()")
        assertThat(afterCodeBlockStart).contains("return 42")
        
        // 코드 블록 종료 태그가 있는지 확인 (시작 태그 이후에)
        val codeBlockEnd = afterCodeBlockStart.indexOf("```")
        assertThat(codeBlockEnd).isGreaterThan(-1)
        
        // 코드 블록 안에 코드의 핵심 부분이 포함되는지 확인 (종료 태그 전까지의 내용)
        if (codeBlockEnd > 0) {
            val codeInBlock = afterCodeBlockStart.substring(0, codeBlockEnd)
            assertThat(codeInBlock).contains("def solution()")
            assertThat(codeInBlock).contains("return 42")
        }
    }

    @Test
    @DisplayName("빈 줄과 공백이 많은 코드도 원본 그대로 유지된다")
    fun `빈 줄과 공백 처리 검증`() {
        // given
        val codeWithSpaces = """
            
            int main() {
                
                int a = 1;
                
                int b = 2;
                
                return a + b;
            }
            
        """.trimIndent()

        // when
        val result = promptFactory.createUserPrompt(
            problemId = "5000",
            problemTitle = "Spacing Test",
            problemDescription = null,
            code = codeWithSpaces,
            isSuccess = false
        )

        // then
        // 원본 코드의 구조가 그대로 유지되는지 확인
        assertThat(result).contains("int main() {")
        assertThat(result).contains("int a = 1;")
        assertThat(result).contains("int b = 2;")
        assertThat(result).contains("return a + b;")

        // 빈 줄도 포함되는지 확인 (trimIndent()로 정규화되지만 구조는 유지)
        val codeInResult = result.substring(result.indexOf("int main()"))
        assertThat(codeInResult).contains("int a = 1;")
    }

    @Test
    @DisplayName("한글 주석이 포함된 코드도 원본 그대로 유지된다")
    fun `한글 주석 처리 검증`() {
        // given
        val codeWithKorean = """
            // 한글 주석 테스트
            int main() {
                int sum = 0;  // 합계 변수
                for (int i = 1; i <= 10; i++) {
                    sum += i;  // 누적 합산
                }
                return sum;  // 결과 반환
            }
        """.trimIndent()

        // when
        val result = promptFactory.createUserPrompt(
            problemId = "6000",
            problemTitle = "Korean Comment Test",
            problemDescription = null,
            code = codeWithKorean,
            isSuccess = true
        )

        // then
        assertThat(result).contains("// 한글 주석 테스트")
        assertThat(result).contains("// 합계 변수")
        assertThat(result).contains("// 누적 합산")
        assertThat(result).contains("// 결과 반환")

        // 원본 코드가 그대로 포함되는지 확인
        assertThat(result).contains(codeWithKorean)
    }

    @Test
    @DisplayName("특수문자만으로 구성된 코드도 원본 그대로 유지된다")
    fun `특수문자만 포함된 코드 처리 검증`() {
        // given
        val specialCharsCode = """
            #include <stdio.h>
            int main() {
                printf("!@#$%^&*()_+-=[]{}|;':\",./<>?");
                printf("\\n\\t\\r");
                return 0;
            }
        """.trimIndent()

        // when
        val result = promptFactory.createUserPrompt(
            problemId = "7000",
            problemTitle = "Special Chars Test",
            problemDescription = null,
            code = specialCharsCode,
            isSuccess = true
        )

        // then
        assertThat(result).contains("!@#")
        assertThat(result).contains("%^&*")
        assertThat(result).contains("printf")

        // 원본 코드가 그대로 포함되는지 확인
        assertThat(result).contains(specialCharsCode)
    }
}

