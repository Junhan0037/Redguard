package com.redguard.application.ratelimit

import com.redguard.domain.ratelimit.RateLimitScope
import com.redguard.infrastructure.redis.QuotaResult
import com.redguard.infrastructure.redis.RateLimitScriptRequest
import com.redguard.infrastructure.redis.RateLimitScriptResult
import com.redguard.infrastructure.redis.WindowResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.time.Instant

class RateLimitCheckServiceTest {

    @Test
    fun `윈도우와_쿼터가_모두_허용되면_ALLOWED를_반환한다`() {
        val engine = StubRateLimitEngine(
            RateLimitScriptResult(
                second = WindowResult(true, 1, 1, 0),
                minute = WindowResult(true, 10, 10, 0),
                day = WindowResult(true, 20, 20, 0),
                dailyQuota = QuotaResult(true, 5),
                monthlyQuota = QuotaResult(true, 50),
                fallbackApplied = false
            )
        )
        val service = RateLimitCheckService(engine)
        val command = RateLimitCheckCommand(
            scope = RateLimitScope.TENANT_API,
            tenantId = "tenant-1",
            userId = "user-1",
            apiPath = "/v1/report",
            httpMethod = HttpMethod.POST,
            timestamp = Instant.parse("2025-01-01T00:00:00Z"),
            increment = 1,
            policy = RateLimitPolicySnapshot(
                limitPerSecond = 5,
                limitPerMinute = 50,
                limitPerDay = 500,
                quotaPerDay = 1000,
                quotaPerMonth = 5000
            )
        )

        val result = service.check(command)

        assertTrue(result.allowed)
        assertEquals(RateLimitDecision.ALLOWED, result.decision)
        assertEquals(5, result.windowUsages.second?.limit)
        assertEquals("tenant-1", engine.lastRequest?.dimensions?.tenantId)
    }

    @Test
    fun `쿼터가_초과되면_QUOTA_EXCEEDED를_반환한다`() {
        val engine = StubRateLimitEngine(
            RateLimitScriptResult(
                second = null,
                minute = null,
                day = null,
                dailyQuota = QuotaResult(false, 1001),
                monthlyQuota = QuotaResult(true, 5000),
                fallbackApplied = false
            )
        )
        val service = RateLimitCheckService(engine)
        val command = RateLimitCheckCommand(
            scope = RateLimitScope.TENANT,
            tenantId = "tenant-1",
            userId = null,
            apiPath = null,
            httpMethod = HttpMethod.GET,
            timestamp = Instant.now(),
            increment = 1,
            policy = RateLimitPolicySnapshot(
                quotaPerDay = 1000,
                quotaPerMonth = 10000
            )
        )

        val result = service.check(command)

        assertFalse(result.allowed)
        assertEquals(RateLimitDecision.QUOTA_EXCEEDED, result.decision)
        assertEquals(1000, result.quotaUsages.daily?.limit)
    }

    @Test
    fun `폴백이_적용되어_허용되면_FALLBACK_ALLOW를_반환한다`() {
        val engine = StubRateLimitEngine(
            RateLimitScriptResult(
                second = WindowResult(true, 1, 1, 0),
                minute = null,
                day = null,
                dailyQuota = null,
                monthlyQuota = null,
                fallbackApplied = true
            )
        )
        val service = RateLimitCheckService(engine)
        val command = RateLimitCheckCommand(
            scope = RateLimitScope.TENANT_API,
            tenantId = "tenant-1",
            userId = null,
            apiPath = "/v1/report",
            httpMethod = HttpMethod.GET,
            timestamp = Instant.now(),
            policy = RateLimitPolicySnapshot(limitPerSecond = 10)
        )

        val result = service.check(command)

        assertTrue(result.allowed)
        assertTrue(result.fallbackApplied)
        assertEquals(RateLimitDecision.FALLBACK_ALLOW, result.decision)
    }

    private class StubRateLimitEngine(
        private val scriptResult: RateLimitScriptResult
    ) : RateLimitEngine {
        var lastRequest: RateLimitScriptRequest? = null

        override fun evaluate(request: RateLimitScriptRequest): RateLimitScriptResult {
            lastRequest = request
            return scriptResult
        }
    }
}
