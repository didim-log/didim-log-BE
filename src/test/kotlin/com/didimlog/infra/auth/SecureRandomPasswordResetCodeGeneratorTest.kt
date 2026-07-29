package com.didimlog.infra.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("보안 난수 비밀번호 재설정 코드 생성기 테스트")
class SecureRandomPasswordResetCodeGeneratorTest {

    private val generator = SecureRandomPasswordResetCodeGenerator()

    @Test
    @DisplayName("8자리 영문 대문자와 숫자로만 코드를 생성한다")
    fun `generate returns eight uppercase alphanumeric characters`() {
        val generatedCodes = List(100) { generator.generate() }

        assertThat(generatedCodes).allMatch { code ->
            code.matches(Regex("[A-Z0-9]{8}"))
        }
    }
}
