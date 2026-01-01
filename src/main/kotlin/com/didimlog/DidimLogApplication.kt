package com.didimlog

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class DidimLogApplication {

    companion object {

        @JvmStatic
        fun main(args: Array<String>) {
            runApplication<DidimLogApplication>(*args)
        }
    }
}
