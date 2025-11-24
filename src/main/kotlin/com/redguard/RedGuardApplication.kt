package com.redguard

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class RedGuardApplication

fun main(args: Array<String>) {
    runApplication<RedGuardApplication>(*args)
}
