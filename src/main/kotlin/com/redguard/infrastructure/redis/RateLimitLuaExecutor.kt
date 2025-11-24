package com.redguard.infrastructure.redis

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Rate Limit/Quota Lua 스크립트 실행 orchestrator
 * - 페이로드 생성, 실행, 결과 매핑을 조합
 * - Redis 장애 시 폴백 정책 적용
 */
@Component
class RateLimitLuaExecutor(
    private val scriptInitializer: RateLimitScriptInitializer,
    private val payloadBuilder: RateLimitPayloadBuilder,
    private val scriptExecutor: RateLimitScriptExecutor,
    private val resultMapper: RateLimitResultMapper,
    private val fallbackHandler: RateLimitFallbackHandler
) {

    private val logger = KotlinLogging.logger {}

    /**
     * 요청을 검증하고 Lua 스크립트를 실행한 뒤 결과를 매핑한다. Redis 장애 시 폴백 정책을 적용
     */
    @Suppress("SENSELESS_COMPARISON")
    fun evaluate(request: RateLimitScriptRequest): RateLimitScriptResult {
        if (request.increment <= 0) {
            throw RedGuardException(ErrorCode.INVALID_REQUEST, "증가량은 1 이상이어야 합니다.")
        }

        scriptInitializer.ensureLoaded()

        val payload = payloadBuilder.build(request)

        return try {
            val rawResult = scriptExecutor.execute(payload)
            resultMapper.map(request, rawResult)
        } catch (ex: Exception) {
            if (!scriptExecutor.isRedisFailure(ex)) {
                throw ex
            }
            logger.error(ex) { "Redis 연산 실패로 Fallback 정책을 적용합니다. policy=${fallbackHandler.policy()}" }
            fallbackHandler.handle(request)
        }
    }
}
