package com.redguard.infrastructure.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ApplicationClockConfig {

    @Bean
    fun systemClock(): Clock = Clock.systemUTC()
}
