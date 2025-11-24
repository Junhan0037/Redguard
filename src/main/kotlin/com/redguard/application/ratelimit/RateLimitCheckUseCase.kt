package com.redguard.application.ratelimit

import com.redguard.domain.policy.ApiHttpMethod
import com.redguard.domain.ratelimit.RateLimitScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * `/internal/rate-limit/check` 유스케이스 진입점
 * - 입력값을 정책 조회 → Redis 평가 순서로 오케스트레이션
 */
interface RateLimitCheckUseCase {
    fun check(input: RateLimitCheckInput): RateLimitCheckResult
}

@Service
class DefaultRateLimitCheckUseCase(
    private val rateLimitPolicyResolver: RateLimitPolicyResolver,
    private val rateLimitCheckService: RateLimitCheckService
) : RateLimitCheckUseCase {

    /**
     * 정책 스냅샷을 조회한 뒤 Redis 실행 커맨드를 구성해 평가 결과를 반환
     */
    @Transactional(readOnly = true)
    override fun check(input: RateLimitCheckInput): RateLimitCheckResult {
        val policySnapshot = rateLimitPolicyResolver.resolve(
            RateLimitPolicyResolveCommand(
                tenantId = input.tenantId,
                apiPath = input.apiPath,
                httpMethod = input.httpMethod
            )
        )

        val command = RateLimitCheckCommand(
            scope = input.scope,
            tenantId = input.tenantId,
            userId = input.userId,
            apiPath = input.apiPath,
            httpMethod = input.httpMethod,
            timestamp = input.timestamp,
            increment = input.increment,
            policy = policySnapshot
        )

        return rateLimitCheckService.check(command)
    }
}

/**
 * 컨트롤러 계층으로부터 전달받는 RateLimit 평가 입력 값 집합
 */
data class RateLimitCheckInput(
    val scope: RateLimitScope,
    val tenantId: String,
    val userId: String?,
    val apiPath: String?,
    val httpMethod: ApiHttpMethod,
    val timestamp: Instant,
    val increment: Long
)
