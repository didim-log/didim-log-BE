package com.didimlog.infra.solvedac

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
class SolvedAcConfig {

    @Bean
    fun solvedAcClientWebClient(builder: WebClient.Builder): WebClient {
        val httpClient = HttpClient.create()
            .responseTimeout(Duration.ofSeconds(5))
            .compress(true)

        return builder
            .baseUrl("https://solved.ac/api/v3")
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
