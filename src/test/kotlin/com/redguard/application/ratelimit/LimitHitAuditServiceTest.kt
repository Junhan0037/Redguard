package com.redguard.application.ratelimit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.redguard.domain.limit.LimitHitLog
import com.redguard.domain.limit.LimitHitLogRepository
import com.redguard.domain.limit.LimitHitReason
import com.redguard.domain.plan.Plan
import com.redguard.domain.ratelimit.RateLimitScope
import com.redguard.domain.tenant.Tenant
import com.redguard.domain.tenant.TenantRepository
import com.redguard.domain.tenant.TenantStatus
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.time.Instant

class LimitHitAuditServiceTest {

    private val tenantRepository: TenantRepository = Mockito.mock(TenantRepository::class.java)
    private val limitHitLogRepository: LimitHitLogRepository = Mockito.mock(LimitHitLogRepository::class.java)
    private val objectMapper = jacksonObjectMapper()

    private val service = LimitHitAuditService(
        tenantRepository = tenantRepository,
        limitHitLogRepository = limitHitLogRepository,
        objectMapper = objectMapper
    )

    @Test
    fun `차단된_요청이_저장되고_구조화로그가_발행된다`() {
        val tenant = sampleTenant()
        Mockito.`when`(tenantRepository.findByName("tenant-1")).thenReturn(tenant)

        val command = RateLimitCheckCommand(
            scope = RateLimitScope.TENANT_API,
            tenantId = "tenant-1",
            userId = "user-1",
            apiPath = "/v1/report",
            httpMethod = com.redguard.domain.policy.ApiHttpMethod.GET,
            timestamp = Instant.now(),
            policy = RateLimitPolicySnapshot(limitPerSecond = 1)
        )
        val result = RateLimitCheckResult(
            decision = RateLimitDecision.RATE_LIMIT_EXCEEDED,
            allowed = false,
            windowUsages = WindowUsages(),
            quotaUsages = QuotaUsages(),
            fallbackApplied = false
        )

        service.recordLimitExceeded(command, result)

        val captor = ArgumentCaptor.forClass(LimitHitLog::class.java)
        Mockito.verify(limitHitLogRepository).save(captor.capture())
        val saved = captor.value
        assert(saved.reason == LimitHitReason.RATE_LIMIT)
        assert(saved.tenant == tenant)
        assert(saved.apiPath == "/v1/report")
    }

    @Test
    fun `테넌트를_찾지_못하면_저장하지_않는다`() {
        Mockito.`when`(tenantRepository.findByName("missing")).thenReturn(null)

        val command = RateLimitCheckCommand(
            scope = RateLimitScope.TENANT,
            tenantId = "missing",
            userId = null,
            apiPath = null,
            httpMethod = com.redguard.domain.policy.ApiHttpMethod.GET,
            timestamp = Instant.now(),
            policy = RateLimitPolicySnapshot(quotaPerDay = 1)
        )
        val result = RateLimitCheckResult(
            decision = RateLimitDecision.QUOTA_EXCEEDED,
            allowed = false,
            windowUsages = WindowUsages(),
            quotaUsages = QuotaUsages(),
            fallbackApplied = false
        )

        service.recordLimitExceeded(command, result)

        Mockito.verify(limitHitLogRepository, Mockito.never()).save(Mockito.any(LimitHitLog::class.java))
    }

    private fun sampleTenant(): Tenant {
        val plan = Plan(
            name = "PLAN",
            description = "desc",
            rateLimitPerSecond = 1,
            rateLimitPerMinute = 10,
            rateLimitPerDay = 100,
            quotaPerDay = 1000,
            quotaPerMonth = 2000
        ).apply { id = 10L }

        return Tenant(
            name = "tenant-1",
            status = TenantStatus.ACTIVE,
            plan = plan
        ).apply { id = 1L }
    }
}
