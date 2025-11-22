package com.redguard.domain.health

import java.time.Instant

data class HealthStatus(
    val status: Status,
    val message: String,
    val timestamp: Instant,
    val components: Map<String, ComponentStatus> = emptyMap()
) {
    enum class Status {
        UP,
        DOWN,
        DEGRADED
    }

    data class ComponentStatus(
        val status: Status,
        val detail: String
    )
}
