package com.redguard.infrastructure.redis

import com.redguard.domain.ratelimit.RateLimitScope
import com.redguard.domain.ratelimit.RedisKeyDimensions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RateLimitPayloadBuilderTest {
    private val keyBuilder = RedisKeyBuilder()
    private val payloadBuilder = RateLimitPayloadBuilder(keyBuilder)
    private val fixedInstant = Instant.parse("2025-11-23T10:15:30Z")
    private val dimensions = RedisKeyDimensions(
        scope = RateLimitScope.TENANT_API,
        tenantId = "tenant-01",
        apiPath = "/v1/report"
    )

    @Test
    fun `빌드된_페이로드는_키와_인자를_모두_채운다`() {
        val payload = payloadBuilder.build(
            RateLimitScriptRequest(
                dimensions = dimensions,
                timestamp = fixedInstant,
                limitPerSecond = 10,
                limitPerMinute = 100,
                limitPerDay = 1000,
                quotaPerDay = 5000,
                quotaPerMonth = 10000,
                increment = 1
            )
        )

        assertEquals(8, payload.keys.size)
        assertEquals("rl:tenant_api:tenant-01:-:/v1/report:20251123101530", payload.keys[0])
        assertEquals("rl:tenant_api:tenant-01:-:/v1/report:20251123101529", payload.keys[1])
        assertEquals(14, payload.args.size)
        assertEquals("10", payload.args[0]) // 초당 리밋
        assertEquals("100", payload.args[1]) // 분당 리밋
        assertEquals("1000", payload.args[2]) // 일 리밋
        assertEquals("5000", payload.args[3]) // 일 쿼터
        assertEquals("10000", payload.args[4]) // 월 쿼터
        assertEquals("2", payload.args[5]) // 초 버킷 TTL (2-버킷)
        assertEquals("120", payload.args[6]) // 분 버킷 TTL (2-버킷)
        assertEquals("172800", payload.args[7]) // 일 버킷 TTL (2-버킷)

        val minuteWeight = payload.args[11].toDouble()
        val dayWeight = payload.args[12].toDouble()
        assertEquals(0.5, minuteWeight, 0.0001)
        assertTrue(dayWeight in 0.0..1.0)
    }

    @Test
    fun `리밋이_null이면_비활성화로_전달된다`() {
        val payload = payloadBuilder.build(
            RateLimitScriptRequest(
                dimensions = dimensions,
                timestamp = fixedInstant,
                limitPerSecond = null,
                limitPerMinute = null,
                limitPerDay = null,
                quotaPerDay = null,
                quotaPerMonth = 20000,
                increment = 2
            )
        )

        // limit null -> -1로 비활성화
        assertEquals("-1", payload.args[0])
        assertEquals("-1", payload.args[1])
        assertEquals("-1", payload.args[2])
        assertEquals("-1", payload.args[3]) // 일 쿼터
        assertEquals("20000", payload.args[4]) // 월 쿼터 활성
        assertEquals("2", payload.args[13]) // 증가량
    }
}
