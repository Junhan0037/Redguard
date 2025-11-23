package com.redguard.infrastructure.redis

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.ratelimit.QuotaPeriod
import com.redguard.domain.ratelimit.RateLimitScope
import com.redguard.domain.ratelimit.RateLimitWindow
import com.redguard.domain.ratelimit.RedisKeyDimensions
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RedisKeyBuilderTest {
    private val builder = RedisKeyBuilder()
    private val fixedInstant = Instant.parse("2025-11-23T10:15:30Z")

    @Test
    fun `테넌트 스코프는 user와 apiPath 플레이스홀더를 사용한다`() {
        val dimensions = RedisKeyDimensions(
            scope = RateLimitScope.TENANT,
            tenantId = "tenant-01"
        )

        val key = builder.rateLimitKey(dimensions, RateLimitWindow.SECOND, fixedInstant)

        assertEquals("rl:tenant:tenant-01:-:*:20251123101530", key)
    }

    @Test
    fun `테넌트_유저 스코프는 userId를 요구하고 apiPath 와일드카드를 기본값으로 사용한다`() {
        val dimensions = RedisKeyDimensions(
            scope = RateLimitScope.TENANT_USER,
            tenantId = "tenant-01",
            userId = "user-99"
        )

        val key = builder.rateLimitKey(dimensions, RateLimitWindow.MINUTE, fixedInstant)

        assertEquals("rl:tenant_user:tenant-01:user-99:*:202511231015", key)
    }

    @Test
    fun `테넌트_API 스코프는 경로를 정규화하고 중복 슬래시를 제거한다`() {
        val dimensions = RedisKeyDimensions(
            scope = RateLimitScope.TENANT_API,
            tenantId = "acme",
            apiPath = "/v1/report/usage///"
        )

        val key = builder.rateLimitKey(dimensions, RateLimitWindow.DAY, fixedInstant)

        assertEquals("rl:tenant_api:acme:-:/v1/report/usage:20251123", key)
    }

    @Test
    fun `Quota 키는 월간 period에 대해 올바른 연월 버킷을 사용한다`() {
        val dimensions = RedisKeyDimensions(
            scope = RateLimitScope.TENANT,
            tenantId = "tenant-01"
        )

        val key = builder.quotaKey(dimensions, QuotaPeriod.MONTHLY, fixedInstant)

        assertEquals("qt:tenant:tenant-01:-:*:202511", key)
    }

    @Test
    fun `필수 userId 누락 시 예외를 던진다`() {
        val dimensions = RedisKeyDimensions(
            scope = RateLimitScope.TENANT_USER,
            tenantId = "tenant-01"
        )

        val ex = assertThrows<RedGuardException> {
            builder.rateLimitKey(dimensions, RateLimitWindow.SECOND, fixedInstant)
        }

        assertEquals(ErrorCode.INVALID_REQUEST, ex.errorCode)
    }

    @Test
    fun `경로에 예약 구분자 콜론이 포함되면 예외를 던진다`() {
        val dimensions = RedisKeyDimensions(
            scope = RateLimitScope.TENANT_API,
            tenantId = "tenant-01",
            apiPath = "/v1/report:export"
        )

        val ex = assertThrows<RedGuardException> {
            builder.rateLimitKey(dimensions, RateLimitWindow.SECOND, fixedInstant)
        }

        assertEquals(ErrorCode.INVALID_REQUEST, ex.errorCode)
    }
}
