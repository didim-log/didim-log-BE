package com.didimlog.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * 비밀번호 암호화 설정
 * BCrypt 알고리즘을 사용하여 비밀번호를 암호화한다.
 */
@Configuration
class PasswordEncoderConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }
}


