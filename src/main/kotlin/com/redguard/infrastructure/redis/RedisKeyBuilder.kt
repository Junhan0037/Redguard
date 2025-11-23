package com.redguard.infrastructure.redis

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.domain.ratelimit.QuotaPeriod
import com.redguard.domain.ratelimit.RateLimitWindow
import com.redguard.domain.ratelimit.RedisKeyDimensions
import java.time.Instant

/**
 * Redis 키 네이밍 규칙을 만족하는 키를 생성
 * - 스코프별 필수 파라미터 검증
 * - API 경로 정규화 및 구분자(`:`) 차단
 * - 미사용 필드 플레이스홀더(`-`, `*`) 적용
 */
class RedisKeyBuilder(
    private val notApplicablePlaceholder: String = "-",
    private val apiWildcardPlaceholder: String = "*"
) {

    /**
     * rl:{scope}:{tenantId}:{userId}:{apiPath}:{timeBucket}
     */
    fun rateLimitKey(
        dimensions: RedisKeyDimensions,
        window: RateLimitWindow,
        timestamp: Instant
    ): String {
        val normalized = normalize(dimensions)
        val bucket = window.bucket(timestamp)
        return compose(prefix = "rl", normalized = normalized, suffix = bucket)
    }

    /**
     * qt:{scope}:{tenantId}:{userId}:{apiPath}:{period}
     */
    fun quotaKey(
        dimensions: RedisKeyDimensions,
        period: QuotaPeriod,
        timestamp: Instant
    ): String {
        val normalized = normalize(dimensions)
        val bucket = period.bucket(timestamp)
        return compose(prefix = "qt", normalized = normalized, suffix = bucket)
    }

    private fun normalize(dimensions: RedisKeyDimensions): NormalizedDimensions {
        val scope = dimensions.scope

        // 테넌트 식별자는 항상 필수
        val tenantSegment = sanitizeIdentifier("tenantId", dimensions.tenantId)

        // 스코프가 요구하면 userId를 강제, 아니면 옵션 값이나 플레이스홀더 사용
        val userSegment = when {
            scope.requiresUserId -> sanitizeIdentifier("userId", dimensions.userId)
            dimensions.userId != null -> sanitizeIdentifier("userId", dimensions.userId)
            else -> notApplicablePlaceholder
        }

        // apiPath는 스코프 요구 여부에 따라 필수/옵션으로 처리, 없으면 와일드카드
        val apiPathSegment = when {
            scope.requiresApiPath -> sanitizeApiPath(dimensions.apiPath)
            dimensions.apiPath != null -> sanitizeApiPath(dimensions.apiPath)
            else -> apiWildcardPlaceholder
        }

        return NormalizedDimensions(
            scopeCode = scope.code,
            tenantSegment = tenantSegment,
            userSegment = userSegment,
            apiPathSegment = apiPathSegment
        )
    }

    /**
     * tenantId/userId 등 일반 식별자 검증
     */
    private fun sanitizeIdentifier(fieldName: String, rawValue: String?): String {
        val trimmed = rawValue?.trim() ?: throw invalidKey(fieldName, "$fieldName 값이 없어 Redis 키를 생성할 수 없습니다.")

        if (trimmed.isEmpty()) {
            throw invalidKey(fieldName, "$fieldName 값이 없어 Redis 키를 생성할 수 없습니다.")
        }

        if (trimmed.contains(':')) {
            throw invalidKey(fieldName, "${fieldName}에는 ':'를 포함할 수 없습니다.")
        }

        if (!IDENTIFIER_PATTERN.matches(trimmed)) {
            throw invalidKey(fieldName, "${fieldName}에 허용되지 않는 문자가 포함되어 있습니다.")
        }

        return trimmed
    }

    /**
     * API 경로 검증 및 슬래시 정규화
     */
    private fun sanitizeApiPath(rawValue: String?): String {
        val trimmed = rawValue?.trim() ?: throw invalidKey("apiPath", "apiPath 값이 없어 Redis 키를 생성할 수 없습니다.")

        if (trimmed == apiWildcardPlaceholder) {
            return apiWildcardPlaceholder
        }

        if (trimmed.contains(':')) {
            throw invalidKey("apiPath", "apiPath에는 ':'를 포함할 수 없습니다.")
        }

        val collapsedSlash = trimmed.replace(PATH_SEPARATOR_PATTERN, "/")
        val withLeadingSlash = if (collapsedSlash.startsWith("/")) collapsedSlash else "/$collapsedSlash"

        val normalized = if (withLeadingSlash.length > 1 && withLeadingSlash.endsWith("/")) {
            withLeadingSlash.dropLast(1)
        } else {
            withLeadingSlash
        }

        if (!API_PATH_PATTERN.matches(normalized)) {
            throw invalidKey("apiPath", "apiPath에 허용되지 않는 문자가 포함되어 있습니다.")
        }

        return normalized
    }

    private fun compose(prefix: String, normalized: NormalizedDimensions, suffix: String): String {
        return listOf(
            prefix,
            normalized.scopeCode,
            normalized.tenantSegment,
            normalized.userSegment,
            normalized.apiPathSegment,
            suffix
        ).joinToString(":")
    }

    private data class NormalizedDimensions(
        val scopeCode: String,
        val tenantSegment: String,
        val userSegment: String,
        val apiPathSegment: String
    )

    private fun invalidKey(fieldName: String, detail: String): RedGuardException {
        return RedGuardException(
            errorCode = ErrorCode.INVALID_REQUEST,
            message = "Redis 키 검증 실패 ($fieldName): $detail"
        )
    }

    companion object {
        private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9._-]+$")
        private val API_PATH_PATTERN = Regex("^/[A-Za-z0-9._*/-]*$")
        private val PATH_SEPARATOR_PATTERN = Regex("/+")
    }
}
