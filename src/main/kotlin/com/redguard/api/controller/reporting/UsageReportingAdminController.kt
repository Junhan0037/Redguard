package com.redguard.api.controller.reporting

import com.redguard.api.dto.ApiResponse
import com.redguard.api.dto.reporting.LimitHitLogResponse
import com.redguard.api.dto.reporting.PagedResponse
import com.redguard.api.dto.reporting.UsageSnapshotResponse
import com.redguard.application.reporting.LimitHitLogInfo
import com.redguard.application.reporting.LimitHitLogSearchCommand
import com.redguard.application.reporting.PagedResult
import com.redguard.application.reporting.UsageReportingUseCase
import com.redguard.application.reporting.UsageSnapshotInfo
import com.redguard.application.reporting.UsageSnapshotSearchCommand
import com.redguard.domain.limit.LimitHitReason
import com.redguard.domain.usage.UsageSnapshotPeriod
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

@RestController
@RequestMapping("/admin/usage")
@Validated
class UsageReportingAdminController(
    private val usageReportingUseCase: UsageReportingUseCase
) {

    @GetMapping("/limit-hit-logs")
    fun searchLimitHitLogs(
        @RequestParam tenantId: Long,
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        from: Instant,
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        to: Instant,
        @RequestParam(required = false) reason: LimitHitReason?,
        @RequestParam(required = false)
        @Size(max = 64, message = "userId는 64자 이하로 입력해주세요.")
        userId: String?,
        @RequestParam(required = false)
        @Size(max = 255, message = "apiPath는 255자 이하로 입력해주세요.")
        apiPath: String?,
        @RequestParam(defaultValue = "0")
        @Min(value = 0, message = "page는 0 이상이어야 합니다.")
        page: Int,
        @RequestParam(defaultValue = "50")
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
        @Max(value = 500, message = "size는 최대 500까지 허용됩니다.")
        size: Int
    ): ApiResponse<PagedResponse<LimitHitLogResponse>> {
        val command = LimitHitLogSearchCommand(
            tenantId = tenantId,
            reason = reason,
            userId = sanitize(userId),
            apiPath = sanitize(apiPath),
            from = from,
            to = to,
            page = page,
            size = size
        )

        val result = usageReportingUseCase.searchLimitHitLogs(command)
        return ApiResponse(data = result.toResponse { it.toResponse() })
    }

    @GetMapping("/snapshots")
    fun searchUsageSnapshots(
        @RequestParam tenantId: Long,
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        startDate: LocalDate,
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        endDate: LocalDate,
        @RequestParam(required = false) periodType: UsageSnapshotPeriod?,
        @RequestParam(required = false)
        @Size(max = 64, message = "userId는 64자 이하로 입력해주세요.")
        userId: String?,
        @RequestParam(required = false)
        @Size(max = 255, message = "apiPath는 255자 이하로 입력해주세요.")
        apiPath: String?,
        @RequestParam(defaultValue = "0")
        @Min(value = 0, message = "page는 0 이상이어야 합니다.")
        page: Int,
        @RequestParam(defaultValue = "50")
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
        @Max(value = 500, message = "size는 최대 500까지 허용됩니다.")
        size: Int
    ): ApiResponse<PagedResponse<UsageSnapshotResponse>> {
        val command = UsageSnapshotSearchCommand(
            tenantId = tenantId,
            periodType = periodType,
            userId = sanitize(userId),
            apiPath = sanitize(apiPath),
            startDate = startDate,
            endDate = endDate,
            page = page,
            size = size
        )

        val result = usageReportingUseCase.searchUsageSnapshots(command)
        return ApiResponse(data = result.toResponse { it.toResponse() })
    }

    private fun sanitize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun LimitHitLogInfo.toResponse() = LimitHitLogResponse(
        id = id,
        tenantId = tenantId,
        userId = userId,
        apiPath = apiPath,
        reason = reason,
        occurredAt = occurredAt,
        createdAt = createdAt
    )

    private fun UsageSnapshotInfo.toResponse() = UsageSnapshotResponse(
        id = id,
        tenantId = tenantId,
        userId = userId,
        apiPath = apiPath,
        snapshotDate = snapshotDate,
        periodType = periodType,
        totalCount = totalCount,
        createdAt = createdAt
    )

    private fun <T, R> PagedResult<T>.toResponse(mapper: (T) -> R) = PagedResponse(
        items = items.map(mapper),
        page = page,
        size = size,
        totalElements = totalElements,
        totalPages = totalPages
    )
}
