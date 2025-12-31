package com.didimlog.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor

/**
 * Method Validation 설정
 * - @RequestParam, @PathVariable 등 메서드 파라미터에 선언된 Validation 어노테이션을 활성화한다.
 */
@Configuration
class MethodValidationConfig {

    @Bean
    fun methodValidationPostProcessor(): MethodValidationPostProcessor {
        return MethodValidationPostProcessor()
    }
}


