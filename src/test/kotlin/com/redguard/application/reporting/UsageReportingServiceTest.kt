package com.redguard.application.reporting

import com.redguard.common.exception.RedGuardException
import com.redguard.domain.limit.LimitHitLog
import com.redguard.domain.limit.LimitHitLogRepository
import com.redguard.domain.limit.LimitHitReason
import com.redguard.domain.plan.Plan
import com.redguard.domain.plan.PlanRepository
import com.redguard.domain.tenant.Tenant
import com.redguard.domain.tenant.TenantRepository
import com.redguard.domain.tenant.TenantStatus
import com.redguard.domain.usage.UsageSnapshot
import com.redguard.domain.usage.UsageSnapshotPeriod
import com.redguard.domain.usage.UsageSnapshotRepository
import com.redguard.infrastructure.config.JpaConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.LocalDate

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig::class, UsageReportingService::class)
class UsageReportingServiceTest(
    @Autowired private val planRepository: PlanRepository,
    @Autowired private val tenantRepository: TenantRepository,
    @Autowired private val limitHitLogRepository: LimitHitLogRepository,
    @Autowired private val usageSnapshotRepository: UsageSnapshotRepository,
    @Autowired private val usageReportingUseCase: UsageReportingUseCase
) {

    @Test
    fun `사용량과_로그_조회가_필터링과_정렬을_보장한다`() {
        val tenant = prepareTenant()
        val now = Instant.now()
        val earlier = now.minusSeconds(60)

        limitHitLogRepository.saveAll(
            listOf(
                LimitHitLog(
                    tenant = tenant,
                    userId = "reporter",
                    apiPath = "/v1/report/export",
                    reason = LimitHitReason.RATE_LIMIT,
                    occurredAt = now
                ),
                LimitHitLog(
                    tenant = tenant,
                    userId = "skip",
                    apiPath = "/v1/report/export",
                    reason = LimitHitReason.QUOTA,
                    occurredAt = earlier
                )
            )
        )

        usageSnapshotRepository.saveAll(
            listOf(
                UsageSnapshot(
                    tenant = tenant,
                    userId = "reporter",
                    apiPath = "/v1/report/export",
                    snapshotDate = LocalDate.now(),
                    periodType = UsageSnapshotPeriod.DAY,
                    totalCount = 120
                ),
                UsageSnapshot(
                    tenant = tenant,
                    userId = "other",
                    apiPath = "/v1/report/export",
                    snapshotDate = LocalDate.now().minusDays(1),
                    periodType = UsageSnapshotPeriod.MONTH,
                    totalCount = 5_000
                )
            )
        )

        val logResult = usageReportingUseCase.searchLimitHitLogs(
            LimitHitLogSearchCommand(
                tenantId = tenant.id!!,
                reason = LimitHitReason.RATE_LIMIT,
                userId = "reporter",
                apiPath = "/v1/report/export",
                from = earlier.minusSeconds(10),
                to = now.plusSeconds(1),
                page = 0,
                size = 10
            )
        )

        assertThat(logResult.totalElements).isEqualTo(1)
        assertThat(logResult.items.first().reason).isEqualTo(LimitHitReason.RATE_LIMIT)
        assertThat(logResult.items.first().occurredAt).isAfterOrEqualTo(earlier)

        val snapshotResult = usageReportingUseCase.searchUsageSnapshots(
            UsageSnapshotSearchCommand(
                tenantId = tenant.id!!,
                periodType = UsageSnapshotPeriod.DAY,
                userId = "reporter",
                apiPath = "/v1/report/export",
                startDate = LocalDate.now().minusDays(2),
                endDate = LocalDate.now(),
                page = 0,
                size = 10
            )
        )

        assertThat(snapshotResult.totalElements).isEqualTo(1)
        assertThat(snapshotResult.items.first().totalCount).isEqualTo(120)
        assertThat(snapshotResult.items.first().snapshotDate).isEqualTo(LocalDate.now())
    }

    @Test
    fun `조회_기간이_역전되면_예외를_던진다`() {
        val tenant = prepareTenant()
        val now = Instant.now()

        assertThrows<RedGuardException> {
            usageReportingUseCase.searchLimitHitLogs(
                LimitHitLogSearchCommand(
                    tenantId = tenant.id!!,
                    reason = null,
                    userId = null,
                    apiPath = null,
                    from = now,
                    to = now.minusSeconds(1),
                    page = 0,
                    size = 10
                )
            )
        }

        assertThrows<RedGuardException> {
            usageReportingUseCase.searchUsageSnapshots(
                UsageSnapshotSearchCommand(
                    tenantId = tenant.id!!,
                    periodType = null,
                    userId = null,
                    apiPath = null,
                    startDate = LocalDate.now(),
                    endDate = LocalDate.now().minusDays(1),
                    page = 0,
                    size = 10
                )
            )
        }
    }

    private fun prepareTenant(): Tenant {
        val plan = planRepository.save(
            Plan(
                name = "STANDARD",
                description = "기본 요금제",
                rateLimitPerSecond = 10,
                rateLimitPerMinute = 600,
                rateLimitPerDay = 50_000,
                quotaPerDay = 20_000,
                quotaPerMonth = 300_000
            )
        )
        return tenantRepository.save(
            Tenant(
                name = "report-tenant",
                status = TenantStatus.ACTIVE,
                plan = plan
            )
        )
    }
}
