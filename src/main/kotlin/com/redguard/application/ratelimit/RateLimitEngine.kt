package com.redguard.application.ratelimit

import com.redguard.infrastructure.redis.RateLimitLuaExecutor
import com.redguard.infrastructure.redis.RateLimitScriptRequest
import com.redguard.infrastructure.redis.RateLimitScriptResult
import org.springframework.stereotype.Component

/**
 * Rate Limit 엔진 추상화
 * - 현재는 Lua 기반 Redis 엔진을 사용하지만, 테스트나 추후 확장(예: 외부 서비스 연동)을 위해 인터페이스로 분리
 */
fun interface RateLimitEngine {
    fun evaluate(request: RateLimitScriptRequest): RateLimitScriptResult
}

/**
 * 실제 Redis Lua 엔진을 호출하는 어댑터
 */
@Component
class LuaRateLimitEngine(
    private val rateLimitLuaExecutor: RateLimitLuaExecutor
) : RateLimitEngine {
    override fun evaluate(request: RateLimitScriptRequest): RateLimitScriptResult =
        rateLimitLuaExecutor.evaluate(request)
}
