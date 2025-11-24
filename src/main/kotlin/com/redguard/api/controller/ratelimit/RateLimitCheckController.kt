package com.redguard.api.controller.ratelimit

import com.redguard.api.dto.ApiResponse
import com.redguard.api.dto.ratelimit.DecisionReason
import com.redguard.api.dto.ratelimit.QuotaEvaluation
import com.redguard.api.dto.ratelimit.QuotaEvaluations
import com.redguard.api.dto.ratelimit.RateLimitCheckRequest
import com.redguard.api.dto.ratelimit.RateLimitCheckResponse
import com.redguard.api.dto.ratelimit.WindowEvaluation
import com.redguard.api.dto.ratelimit.WindowEvaluations
import com.redguard.application.ratelimit.RateLimitCheckInput
import com.redguard.application.ratelimit.RateLimitCheckResult
import com.redguard.application.ratelimit.RateLimitCheckUseCase
import com.redguard.application.ratelimit.RateLimitDecision
import com.redguard.application.ratelimit.QuotaUsage
import com.redguard.application.ratelimit.WindowUsage
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 내부 Rate Limit 체크 API
 * - 요청 값을 검증한 뒤 정책 조회 및 Redis 평가 결과를 HTTP 상태 코드에 매핑해 반환
 */
@RestController
@RequestMapping("/internal/rate-limit")
class RateLimitCheckController(
    private val rateLimitCheckUseCase: RateLimitCheckUseCase
) {

    @PostMapping("/check")
    fun check(
        @Valid @RequestBody request: RateLimitCheckRequest
    ): ResponseEntity<ApiResponse<RateLimitCheckResponse>> {
        val result = rateLimitCheckUseCase.check(request.toInput())
        val response = result.toResponse()
        val status = mapStatus(result.decision)

        return ResponseEntity.status(status).body(ApiResponse(success = result.allowed, data = response))
    }

    /**
     * 외부 요청 DTO를 애플리케이션 유스케이스 입력 모델로 변환
     */
    private fun RateLimitCheckRequest.toInput(): RateLimitCheckInput = RateLimitCheckInput(
        scope = scope,
        tenantId = tenantId,
        userId = userId,
        apiPath = apiPath,
        httpMethod = httpMethod,
        timestamp = timestamp,
        increment = increment
    )

    /**
     * 유스케이스 결과를 API 응답 DTO로 매핑
     */
    private fun RateLimitCheckResult.toResponse(): RateLimitCheckResponse = RateLimitCheckResponse(
        allowed = allowed,
        decision = decision.toReason(),
        fallbackApplied = fallbackApplied,
        windows = WindowEvaluations(
            second = windowUsages.second?.toResponse(),
            minute = windowUsages.minute?.toResponse(),
            day = windowUsages.day?.toResponse()
        ),
        quotas = QuotaEvaluations(
            daily = quotaUsages.daily?.toResponse(),
            monthly = quotaUsages.monthly?.toResponse()
        )
    )

    /**
     * 도메인 결정 사유를 API 응답용 결정 코드로 변환
     */
    private fun RateLimitDecision.toReason(): DecisionReason = when (this) {
        RateLimitDecision.ALLOWED -> DecisionReason.ALLOWED
        RateLimitDecision.RATE_LIMIT_EXCEEDED -> DecisionReason.RATE_LIMIT_EXCEEDED
        RateLimitDecision.QUOTA_EXCEEDED -> DecisionReason.QUOTA_EXCEEDED
        RateLimitDecision.FALLBACK_ALLOW -> DecisionReason.FALLBACK_ALLOW
        RateLimitDecision.FALLBACK_BLOCK -> DecisionReason.FALLBACK_BLOCK
    }

    /**
     * 윈도우 사용량 도메인 모델을 응답 DTO로 변환
     */
    private fun WindowUsage.toResponse(): WindowEvaluation = WindowEvaluation(
        allowed = allowed,
        limit = limit,
        effectiveCount = effectiveCount,
        currentBucketCount = currentBucketCount,
        previousBucketCount = previousBucketCount
    )

    /**
     * 쿼터 사용량 도메인 모델을 응답 DTO로 변환
     */
    private fun QuotaUsage.toResponse(): QuotaEvaluation = QuotaEvaluation(
        allowed = allowed,
        limit = limit,
        totalCount = totalCount
    )

    /**
     * RateLimit 결정 결과를 HTTP 상태 코드로 매핑
     */
    private fun mapStatus(decision: RateLimitDecision): HttpStatus = when (decision) {
        RateLimitDecision.ALLOWED, RateLimitDecision.FALLBACK_ALLOW -> HttpStatus.OK
        RateLimitDecision.RATE_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS
        RateLimitDecision.QUOTA_EXCEEDED -> HttpStatus.FORBIDDEN
        RateLimitDecision.FALLBACK_BLOCK -> HttpStatus.SERVICE_UNAVAILABLE
    }
}
