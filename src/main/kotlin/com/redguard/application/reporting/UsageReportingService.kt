package com.redguard.application.reporting

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.limit.LimitHitLog
import com.redguard.domain.limit.LimitHitLogRepository
import com.redguard.domain.limit.LimitHitReason
import com.redguard.domain.tenant.TenantRepository
import com.redguard.domain.usage.UsageSnapshot
import com.redguard.domain.usage.UsageSnapshotPeriod
import com.redguard.domain.usage.UsageSnapshotRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

interface UsageReportingUseCase {
    fun searchLimitHitLogs(command: LimitHitLogSearchCommand): PagedResult<LimitHitLogInfo>
    fun searchUsageSnapshots(command: UsageSnapshotSearchCommand): PagedResult<UsageSnapshotInfo>
    fun summarizeUsage(command: UsageSummaryCommand): UsageSummaryResult
}

@Service
class UsageReportingService(
    private val tenantRepository: TenantRepository,
    private val limitHitLogRepository: LimitHitLogRepository,
    private val usageSnapshotRepository: UsageSnapshotRepository
) : UsageReportingUseCase {

    /**
     * 테넌트 존재 여부와 조회 구간을 검증한 뒤 조건부 검색을 실행
     */
    @Transactional(readOnly = true)
    override fun searchLimitHitLogs(command: LimitHitLogSearchCommand): PagedResult<LimitHitLogInfo> {
        validateInstantRange(command.from, command.to)
        ensureTenantExists(command.tenantId)

        val pageable = PageRequest.of(
            command.page,
            command.size,
            Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))
        )
        val logs = limitHitLogRepository.search(
            tenantId = command.tenantId,
            reason = command.reason,
            userId = command.userId,
            apiPath = command.apiPath,
            from = command.from,
            to = command.to,
            pageable = pageable
        )
        return logs.toPagedResult { it.toInfo() }
    }

    /**
     * 스냅샷 기간·범위를 검증하고 요청된 정렬 기준으로 결과를 반환
     */
    @Transactional(readOnly = true)
    override fun searchUsageSnapshots(command: UsageSnapshotSearchCommand): PagedResult<UsageSnapshotInfo> {
        validateDateRange(command.startDate, command.endDate)
        ensureTenantExists(command.tenantId)

        val pageable = PageRequest.of(
            command.page,
            command.size,
            Sort.by(Sort.Order.desc("snapshotDate"), Sort.Order.desc("id"))
        )
        val snapshots = usageSnapshotRepository.search(
            tenantId = command.tenantId,
            periodType = command.periodType,
            userId = command.userId,
            apiPath = command.apiPath,
            startDate = command.startDate,
            endDate = command.endDate,
            pageable = pageable
        )
        return snapshots.toPagedResult { it.toInfo() }
    }

    /**
     * 지정 일자의 일/월 합계와 최근 N일 일별 합계를 반환
     */
    @Transactional(readOnly = true)
    override fun summarizeUsage(command: UsageSummaryCommand): UsageSummaryResult {
        validateRecentDays(command.recentDays)
        ensureTenantExists(command.tenantId)

        val recentStart = command.targetDate.minusDays(command.recentDays.toLong() - 1)
        val dailySnapshots = usageSnapshotRepository.findAllByFilters(
            tenantId = command.tenantId,
            periodType = UsageSnapshotPeriod.DAY,
            userId = command.userId,
            apiPath = command.apiPath,
            startDate = recentStart,
            endDate = command.targetDate
        )

        val dailySumByDate = dailySnapshots.groupBy { it.snapshotDate }
            .mapValues { entry -> entry.value.sumOf { it.totalCount } }

        val dailyUsage = dailySumByDate[command.targetDate] ?: 0
        val recentUsages = (0 until command.recentDays).map { index ->
            val date = recentStart.plusDays(index.toLong())
            DailyUsage(date = date, totalCount = dailySumByDate[date] ?: 0)
        }

        val monthStart = command.targetDate.withDayOfMonth(1)
        val monthEnd = command.targetDate.withDayOfMonth(command.targetDate.lengthOfMonth())
        val monthlySnapshots = usageSnapshotRepository.findAllByFilters(
            tenantId = command.tenantId,
            periodType = UsageSnapshotPeriod.MONTH,
            userId = command.userId,
            apiPath = command.apiPath,
            startDate = monthStart,
            endDate = monthEnd
        )
        val monthlyTotalFromSnapshot = monthlySnapshots.sumOf { it.totalCount }
        val monthlyTotalFromDays = usageSnapshotRepository.findAllByFilters(
            tenantId = command.tenantId,
            periodType = UsageSnapshotPeriod.DAY,
            userId = command.userId,
            apiPath = command.apiPath,
            startDate = monthStart,
            endDate = monthEnd
        ).sumOf { it.totalCount }

        val monthlyUsage = if (monthlyTotalFromSnapshot > 0) monthlyTotalFromSnapshot else monthlyTotalFromDays

        return UsageSummaryResult(
            daily = UsageAggregate(date = command.targetDate, periodType = UsageSnapshotPeriod.DAY, totalCount = dailyUsage),
            monthly = UsageAggregate(date = monthStart, periodType = UsageSnapshotPeriod.MONTH, totalCount = monthlyUsage),
            recentDays = recentUsages
        )
    }

    /**
     * 존재하지 않는 테넌트 요청을 사전에 차단
     */
    private fun ensureTenantExists(tenantId: Long) {
        if (!tenantRepository.existsById(tenantId)) {
            throw RedGuardException(ErrorCode.RESOURCE_NOT_FOUND, "테넌트를 찾을 수 없습니다. id=$tenantId")
        }
    }

    /**
     * 조회 시작 시각과 종료 시각의 선후관계를 검증
     */
    private fun validateInstantRange(from: Instant, to: Instant) {
        if (from.isAfter(to)) {
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "조회 시작 시각이 종료 시각보다 이후입니다.")
        }
    }

    /**
     * 일자 기반 조회 요청이 역전되는 것을 방지
     */
    private fun validateDateRange(startDate: LocalDate, endDate: LocalDate) {
        if (startDate.isAfter(endDate)) {
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "조회 시작 일자가 종료 일자보다 이후입니다.")
        }
    }

    private fun validateRecentDays(recentDays: Int) {
        if (recentDays <= 0 || recentDays > 180) {
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "최근 조회 일수는 1~180 사이여야 합니다.")
        }
    }

    private fun LimitHitLog.toInfo() = LimitHitLogInfo(
        id = requireNotNull(id) { "영속화되지 않은 LimitHitLog 엔티티입니다." },
        tenantId = requireNotNull(tenant.id) { "LimitHitLog에 테넌트 식별자가 없습니다." },
        userId = userId,
        apiPath = apiPath,
        reason = reason,
        occurredAt = occurredAt,
        createdAt = createdAt
    )

    private fun UsageSnapshot.toInfo() = UsageSnapshotInfo(
        id = requireNotNull(id) { "영속화되지 않은 UsageSnapshot 엔티티입니다." },
        tenantId = requireNotNull(tenant.id) { "UsageSnapshot에 테넌트 식별자가 없습니다." },
        userId = userId,
        apiPath = apiPath,
        snapshotDate = snapshotDate,
        periodType = periodType,
        totalCount = totalCount,
        createdAt = createdAt
    )

    private fun <T, R> Page<T>.toPagedResult(mapper: (T) -> R): PagedResult<R> =
        PagedResult(
            items = content.map(mapper),
            page = number,
            size = size,
            totalElements = totalElements,
            totalPages = totalPages
        )
}

data class LimitHitLogSearchCommand(
    val tenantId: Long,
    val reason: LimitHitReason?,
    val userId: String?,
    val apiPath: String?,
    val from: Instant,
    val to: Instant,
    val page: Int,
    val size: Int
)

data class UsageSnapshotSearchCommand(
    val tenantId: Long,
    val periodType: UsageSnapshotPeriod?,
    val userId: String?,
    val apiPath: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val page: Int,
    val size: Int
)

data class UsageSummaryCommand(
    val tenantId: Long,
    val targetDate: LocalDate,
    val recentDays: Int,
    val userId: String?,
    val apiPath: String?
)

data class LimitHitLogInfo(
    val id: Long,
    val tenantId: Long,
    val userId: String?,
    val apiPath: String,
    val reason: LimitHitReason,
    val occurredAt: Instant,
    val createdAt: Instant
)

data class UsageSnapshotInfo(
    val id: Long,
    val tenantId: Long,
    val userId: String?,
    val apiPath: String?,
    val snapshotDate: LocalDate,
    val periodType: UsageSnapshotPeriod,
    val totalCount: Long,
    val createdAt: Instant
)

data class UsageSummaryResult(
    val daily: UsageAggregate,
    val monthly: UsageAggregate,
    val recentDays: List<DailyUsage>
)

data class UsageAggregate(
    val date: LocalDate,
    val periodType: UsageSnapshotPeriod,
    val totalCount: Long
)

data class DailyUsage(
    val date: LocalDate,
    val totalCount: Long
)

data class PagedResult<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)
