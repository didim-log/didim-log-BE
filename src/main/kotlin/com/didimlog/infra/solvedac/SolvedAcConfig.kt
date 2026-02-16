package com.didimlog.infra.solvedac

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

@Configuration
class SolvedAcConfig {

    @Bean
    fun solvedAcClientWebClient(builder: WebClient.Builder): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
            .responseTimeout(Duration.ofSeconds(5))
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(5, TimeUnit.SECONDS))
                conn.addHandlerLast(WriteTimeoutHandler(5, TimeUnit.SECONDS))
            }
            .compress(true)

        return builder
            .baseUrl("https://solved.ac/api/v3")
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
