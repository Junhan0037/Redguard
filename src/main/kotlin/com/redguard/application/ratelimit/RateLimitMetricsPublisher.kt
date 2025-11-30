package com.redguard.application.ratelimit

/**
 * Rate Limit 평가 결과를 관측 지표로 전달하기 위한 포트
 * - Micrometer 등 구체 구현체로부터 애플리케이션 레이어를 분리
 */
interface RateLimitMetricsPublisher {
    fun record(command: RateLimitCheckCommand, result: RateLimitCheckResult)
}
