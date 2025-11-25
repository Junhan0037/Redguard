package com.redguard.application.policy

import com.redguard.domain.policy.ApiHttpMethod
import java.time.Instant

data class CreateApiPolicyCommand(
    val tenantId: Long?,
    val planId: Long?,
    val httpMethod: ApiHttpMethod,
    val apiPattern: String,
    val description: String?,
    val rateLimitPerSecond: Long?,
    val rateLimitPerMinute: Long?,
    val rateLimitPerDay: Long?,
    val quotaPerDay: Long?,
    val quotaPerMonth: Long?
)

data class UpdateApiPolicyCommand(
    val tenantId: Long?,
    val planId: Long?,
    val httpMethod: ApiHttpMethod,
    val apiPattern: String,
    val description: String?,
    val rateLimitPerSecond: Long?,
    val rateLimitPerMinute: Long?,
    val rateLimitPerDay: Long?,
    val quotaPerDay: Long?,
    val quotaPerMonth: Long?
)

data class ApiPolicyInfo(
    val id: Long,
    val tenantId: Long?,
    val planId: Long?,
    val httpMethod: ApiHttpMethod,
    val apiPattern: String,
    val description: String?,
    val rateLimitPerSecond: Long?,
    val rateLimitPerMinute: Long?,
    val rateLimitPerDay: Long?,
    val quotaPerDay: Long?,
    val quotaPerMonth: Long?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ApiPolicySearchFilter(
    val tenantId: Long?,
    val planId: Long?,
    val apiPattern: String?,
    val httpMethod: ApiHttpMethod?
)
