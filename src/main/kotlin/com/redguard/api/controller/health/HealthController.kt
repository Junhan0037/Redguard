package com.redguard.api.controller.health

import com.redguard.api.dto.ApiResponse
import com.redguard.application.health.HealthCheckService
import com.redguard.domain.health.HealthStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/health")
class HealthController(
    private val healthCheckService: HealthCheckService
) {

    @GetMapping
    fun health(): ApiResponse<HealthStatus> = ApiResponse(
        data = healthCheckService.currentStatus()
    )
}
