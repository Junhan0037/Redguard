package com.redguard

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RedGuardApplication

fun main(args: Array<String>) {
    runApplication<RedGuardApplication>(*args)
}
