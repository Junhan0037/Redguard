package com.redguard.domain.ratelimit

/**
 * Redis 키 생성 시 지원하는 스코프 조합을 정의
 */
enum class RateLimitScope(
    val code: String, // 키 세그먼트에 들어갈 식별자
    val requiresUserId: Boolean, // 필수 여부를 명시해 미입력 방지
    val requiresApiPath: Boolean // 필수 여부를 명시해 미입력 방지
) {
    TENANT(code = "tenant", requiresUserId = false, requiresApiPath = false),
    TENANT_USER(code = "tenant_user", requiresUserId = true, requiresApiPath = false),
    TENANT_API(code = "tenant_api", requiresUserId = false, requiresApiPath = true)
}
