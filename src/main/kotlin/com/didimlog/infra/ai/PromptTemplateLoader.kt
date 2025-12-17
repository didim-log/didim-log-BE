package com.didimlog.infra.ai

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

/**
 * 프롬프트 템플릿 파일을 로드하고 변수를 치환하는 컴포넌트
 * 
 * 템플릿 파일은 `resources/templates/prompts/` 경로에 `.md` 형식으로 저장된다.
 * 플레이스홀더는 `{variableName}` 형식으로 작성하며, `replaceVariables` 메서드로 치환한다.
 */
@Component
class PromptTemplateLoader {

    private val log = LoggerFactory.getLogger(PromptTemplateLoader::class.java)

    companion object {
        private const val TEMPLATE_BASE_PATH = "templates/prompts/"
    }

    /**
     * 템플릿 파일을 로드한다.
     *
     * @param templateFileName 템플릿 파일명 (예: "system-prompt-success.md")
     * @return 템플릿 내용 문자열
     * @throws FileNotFoundException 템플릿 파일을 찾을 수 없는 경우
     */
    fun loadTemplate(templateFileName: String): String {
        val resource = ClassPathResource("$TEMPLATE_BASE_PATH$templateFileName")
        
        if (!resource.exists()) {
            log.error("템플릿 파일을 찾을 수 없습니다: {}", templateFileName)
            throw FileNotFoundException("템플릿 파일을 찾을 수 없습니다: $templateFileName")
        }

        return try {
            resource.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            log.error("템플릿 파일 읽기 실패: {}", templateFileName, e)
            throw IllegalStateException("템플릿 파일을 읽을 수 없습니다: $templateFileName", e)
        }
    }

    /**
     * 템플릿의 플레이스홀더를 변수 값으로 치환한다.
     *
     * @param template 템플릿 문자열
     * @param variables 치환할 변수 맵 (키: 변수명, 값: 치환할 값)
     * @return 치환된 문자열
     */
    fun replaceVariables(template: String, variables: Map<String, String>): String {
        var result = template
        variables.forEach { (key, value) ->
            result = result.replace("{${key}}", value)
        }
        return result
    }
}

