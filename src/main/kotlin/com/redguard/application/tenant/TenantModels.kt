package com.redguard.application.tenant

import com.redguard.domain.tenant.TenantStatus
import java.time.Instant

data class CreateTenantCommand(
    val name: String,
    val planId: Long,
    val status: TenantStatus = TenantStatus.ACTIVE
)

data class UpdateTenantCommand(
    val name: String,
    val status: TenantStatus
)

data class ChangeTenantPlanCommand(
    val planId: Long
)

data class TenantInfo(
    val id: Long,
    val name: String,
    val status: TenantStatus,
    val plan: PlanInfo,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class PlanInfo(
    val id: Long,
    val name: String,
    val description: String?,
    val rateLimitPerSecond: Long?,
    val rateLimitPerMinute: Long?,
    val rateLimitPerDay: Long?,
    val quotaPerDay: Long?,
    val quotaPerMonth: Long?,
    val createdAt: Instant,
    val updatedAt: Instant
)
