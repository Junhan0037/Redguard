package com.redguard.application.plan

import java.time.Instant

data class CreatePlanCommand(
    val name: String,
    val description: String?,
    val rateLimitPerSecond: Long?,
    val rateLimitPerMinute: Long?,
    val rateLimitPerDay: Long?,
    val quotaPerDay: Long?,
    val quotaPerMonth: Long?
)

data class UpdatePlanCommand(
    val name: String,
    val description: String?,
    val rateLimitPerSecond: Long?,
    val rateLimitPerMinute: Long?,
    val rateLimitPerDay: Long?,
    val quotaPerDay: Long?,
    val quotaPerMonth: Long?
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
