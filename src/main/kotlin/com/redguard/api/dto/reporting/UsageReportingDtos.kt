package com.redguard.api.dto.reporting

import com.redguard.domain.limit.LimitHitReason
import com.redguard.domain.usage.UsageSnapshotPeriod
import java.time.Instant
import java.time.LocalDate

data class LimitHitLogResponse(
    val id: Long,
    val tenantId: Long,
    val userId: String?,
    val apiPath: String,
    val reason: LimitHitReason,
    val occurredAt: Instant,
    val createdAt: Instant
)

data class UsageSnapshotResponse(
    val id: Long,
    val tenantId: Long,
    val userId: String?,
    val apiPath: String?,
    val snapshotDate: LocalDate,
    val periodType: UsageSnapshotPeriod,
    val totalCount: Long,
    val createdAt: Instant
)

data class UsageSummaryResponse(
    val daily: UsageAggregateResponse,
    val monthly: UsageAggregateResponse,
    val recentDays: List<UsageAggregateResponse>
)

data class UsageAggregateResponse(
    val date: LocalDate,
    val periodType: UsageSnapshotPeriod,
    val totalCount: Long
)

data class PagedResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)
