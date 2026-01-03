package com.didimlog.infra.solvedac

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class SolvedAcConfig {

    @Bean
    fun solvedAcClientWebClient(builder: WebClient.Builder): WebClient {
        return builder
            .baseUrl("https://solved.ac/api/v3")
            .build()
    }
}




