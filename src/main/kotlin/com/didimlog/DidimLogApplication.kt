package com.didimlog

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class DidimLogApplication

fun main(args: Array<String>) {
	runApplication<DidimLogApplication>(*args)
}
