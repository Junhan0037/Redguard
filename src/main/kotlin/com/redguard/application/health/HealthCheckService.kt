package com.redguard.application.health

import com.redguard.domain.health.HealthStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
class HealthCheckService(
    private val clock: Clock
) {

    fun currentStatus(): HealthStatus {
        val now = Instant.now(clock)
        // 추후 Redis/DB 등 실제 의존성 상태 진단 로직을 여기에 추가한다.
        return HealthStatus(
            status = HealthStatus.Status.UP,
            message = "RedGuard Rate Limit 플랫폼이 정상 동작 중입니다.",
            timestamp = now,
            components = mapOf(
                "redis" to HealthStatus.ComponentStatus(HealthStatus.Status.UP, "연동 예정"),
                "database" to HealthStatus.ComponentStatus(HealthStatus.Status.UP, "연동 예정")
            )
        )
    }
}
