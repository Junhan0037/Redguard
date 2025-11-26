package com.redguard.domain

import com.redguard.domain.limit.LimitHitLog
import com.redguard.domain.limit.LimitHitLogRepository
import com.redguard.domain.limit.LimitHitReason
import com.redguard.domain.plan.Plan
import com.redguard.domain.plan.PlanRepository
import com.redguard.domain.policy.ApiHttpMethod
import com.redguard.domain.policy.ApiPolicy
import com.redguard.domain.policy.ApiPolicyRepository
import com.redguard.domain.tenant.Tenant
import com.redguard.domain.tenant.TenantRepository
import com.redguard.domain.tenant.TenantStatus
import com.redguard.domain.usage.UsageSnapshot
import com.redguard.domain.usage.UsageSnapshotPeriod
import com.redguard.domain.usage.UsageSnapshotRepository
import com.redguard.infrastructure.config.JpaConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.LocalDate

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig::class)
class RepositoryIntegrationTest(
    @Autowired private val planRepository: PlanRepository,
    @Autowired private val tenantRepository: TenantRepository,
    @Autowired private val apiPolicyRepository: ApiPolicyRepository,
    @Autowired private val usageSnapshotRepository: UsageSnapshotRepository,
    @Autowired private val limitHitLogRepository: LimitHitLogRepository
) {

    @Test
    fun `레포지토리_저장_조회가_정상적으로_동작한다`() {
        val plan = planRepository.save(
            Plan(
                name = "PRO",
                description = "Pro plan",
                rateLimitPerSecond = 20,
                rateLimitPerMinute = 600,
                rateLimitPerDay = 100_000,
                quotaPerDay = 50_000,
                quotaPerMonth = 1_000_000
            )
        )

        val tenant = tenantRepository.save(
            Tenant(
                name = "tenant-a",
                status = TenantStatus.ACTIVE,
                plan = plan
            )
        )

        val apiPolicy = apiPolicyRepository.save(
            ApiPolicy(
                tenant = tenant,
                plan = plan,
                httpMethod = ApiHttpMethod.POST,
                apiPattern = "/v1/report/export",
                rateLimitPerMinute = 30,
                quotaPerDay = 1000
            )
        )

        val snapshot = usageSnapshotRepository.save(
            UsageSnapshot(
                tenant = tenant,
                userId = "user-1",
                apiPath = "/v1/report/export",
                snapshotDate = LocalDate.now(),
                periodType = UsageSnapshotPeriod.DAY,
                totalCount = 150
            )
        )

        val hitLog = limitHitLogRepository.save(
            LimitHitLog(
                tenant = tenant,
                userId = "user-1",
                apiPath = "/v1/report/export",
                reason = LimitHitReason.RATE_LIMIT,
                occurredAt = Instant.now()
            )
        )

        assertThat(planRepository.findByName("PRO")).isNotNull
        assertThat(tenantRepository.findByName("tenant-a")).isNotNull

        val retrievedPolicy = apiPolicyRepository.findByTenantIdAndHttpMethodAndApiPattern(
            tenant.id!!,
            ApiHttpMethod.POST,
            "/v1/report/export"
        )
        assertThat(retrievedPolicy?.id).isEqualTo(apiPolicy.id)

        val snapshots = usageSnapshotRepository.findTop30ByTenantIdAndPeriodTypeOrderBySnapshotDateDesc(
            tenant.id!!,
            UsageSnapshotPeriod.DAY
        )
        assertThat(snapshots).containsExactly(snapshot)

        val logs = limitHitLogRepository.findByTenantIdAndOccurredAtBetween(
            tenant.id!!,
            hitLog.occurredAt.minusSeconds(1),
            hitLog.occurredAt.plusSeconds(1)
        )
        assertThat(logs).hasSize(1)
    }

    @Test
    fun `LimitHitLog와_UsageSnapshot_검색이_필터와_페이지네이션을_지원한다`() {
        val plan = planRepository.save(
            Plan(
                name = "ENTERPRISE",
                description = "Enterprise plan",
                rateLimitPerSecond = 100,
                rateLimitPerMinute = 6000,
                rateLimitPerDay = 1_000_000,
                quotaPerDay = 1_000_000,
                quotaPerMonth = 10_000_000
            )
        )

        val tenant = tenantRepository.save(
            Tenant(
                name = "tenant-b",
                status = TenantStatus.ACTIVE,
                plan = plan
            )
        )

        val now = Instant.now()
        val firstLog = limitHitLogRepository.save(
            LimitHitLog(
                tenant = tenant,
                userId = "user-a",
                apiPath = "/v1/report/export",
                reason = LimitHitReason.RATE_LIMIT,
                occurredAt = now.minusSeconds(30)
            )
        )
        limitHitLogRepository.save(
            LimitHitLog(
                tenant = tenant,
                userId = "user-b",
                apiPath = "/v1/report/export",
                reason = LimitHitReason.QUOTA,
                occurredAt = now
            )
        )

        usageSnapshotRepository.saveAll(
            listOf(
                UsageSnapshot(
                    tenant = tenant,
                    userId = "user-a",
                    apiPath = "/v1/report/export",
                    snapshotDate = LocalDate.now(),
                    periodType = UsageSnapshotPeriod.DAY,
                    totalCount = 500
                ),
                UsageSnapshot(
                    tenant = tenant,
                    userId = "user-a",
                    apiPath = "/v1/report/export",
                    snapshotDate = LocalDate.now().minusDays(1),
                    periodType = UsageSnapshotPeriod.MONTH,
                    totalCount = 10_000
                )
            )
        )

        val logPage = limitHitLogRepository.search(
            tenantId = tenant.id!!,
            reason = LimitHitReason.RATE_LIMIT,
            userId = "user-a",
            apiPath = "/v1/report/export",
            from = now.minusSeconds(300),
            to = now.plusSeconds(10),
            pageable = PageRequest.of(0, 1, Sort.by(Sort.Order.desc("occurredAt")))
        )
        assertThat(logPage.totalElements).isEqualTo(1)
        assertThat(logPage.content.first().id).isEqualTo(firstLog.id)

        val snapshotPage = usageSnapshotRepository.search(
            tenantId = tenant.id!!,
            periodType = UsageSnapshotPeriod.DAY,
            userId = "user-a",
            apiPath = "/v1/report/export",
            startDate = LocalDate.now().minusDays(7),
            endDate = LocalDate.now(),
            pageable = PageRequest.of(0, 5, Sort.by(Sort.Order.desc("snapshotDate")))
        )
        assertThat(snapshotPage.totalElements).isEqualTo(1)
        assertThat(snapshotPage.content.first().periodType).isEqualTo(UsageSnapshotPeriod.DAY)
    }
}
