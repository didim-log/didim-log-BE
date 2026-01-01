package com.didimlog.domain.enums

/**
 * 사용자가 주로 사용하는 프로그래밍 언어
 * 회고 작성 시 코드 블록의 기본 언어를 결정하는 데 사용된다.
 *
 * 지원 언어 목록 (프론트엔드와 동기화):
 * - JAVA, PYTHON, KOTLIN, JAVASCRIPT, CPP, GO, RUST, SWIFT, CSHARP, TEXT
 */
enum class PrimaryLanguage(val value: String) {
    JAVA("java"),
    PYTHON("python"),
    KOTLIN("kotlin"),
    JAVASCRIPT("javascript"),
    CPP("cpp"),
    GO("go"),
    RUST("rust"),
    SWIFT("swift"),
    CSHARP("csharp"),
    TEXT("text"); // 언어를 특정할 수 없는 경우

    companion object {
        /**
         * 문자열 값을 받아서 해당하는 PrimaryLanguage를 반환한다.
         *
         * @param value 언어 문자열 (대소문자 무시)
         * @return 해당하는 PrimaryLanguage (없으면 TEXT)
         */
        fun fromValue(value: String?): PrimaryLanguage {
            if (value == null || value.isBlank()) {
                return TEXT
            }
            return values().find { it.value.equals(value, ignoreCase = true) } ?: TEXT
        }
    }
}



