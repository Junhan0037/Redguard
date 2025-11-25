package com.redguard.api.dto.policy

import com.redguard.domain.policy.ApiHttpMethod
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant

data class ApiPolicyCreateRequest(
    @field:Positive(message = "유효한 테넌트 ID를 입력하거나 null을 전달하세요.")
    val tenantId: Long? = null,
    @field:Positive(message = "유효한 요금제 ID를 입력하거나 null을 전달하세요.")
    val planId: Long? = null,
    @field:NotNull(message = "HTTP 메서드는 필수입니다.")
    val httpMethod: ApiHttpMethod,
    @field:NotBlank(message = "API 패턴은 필수입니다.")
    @field:Size(max = 255, message = "API 패턴은 255자 이하로 입력해주세요.")
    val apiPattern: String,
    @field:Size(max = 255, message = "설명은 255자 이하로 입력해주세요.")
    val description: String? = null,
    @field:PositiveOrZero(message = "0 이상을 입력해주세요.")
    val rateLimitPerSecond: Long? = null,
    @field:PositiveOrZero(message = "0 이상을 입력해주세요.")
    val rateLimitPerMinute: Long? = null,
    @field:PositiveOrZero(message = "0 이상을 입력해주세요.")
    val rateLimitPerDay: Long? = null,
    @field:PositiveOrZero(message = "0 이상을 입력해주세요.")
    val quotaPerDay: Long? = null,
    @field:PositiveOrZero(message = "0 이상을 입력해주세요.")
    val quotaPerMonth: Long? = null
)

data class ApiPolicyUpdateRequest(
    @field:Positive(message = "유효한 테넌트 ID를 입력하거나 null을 전달하세요.")
    val tenantId: Long? = null,
    @field:Positive(message = "유효한 요금제 ID를 입력하거나 null을 전달하세요.")
    val planId: Long? = null,
    @field:NotNull(message = "HTTP 메서드는 필수입니다.")
    val httpMethod: ApiHttpMethod,
    @field:NotBlank(message = "API 패턴은 필수입니다.")
    @field:Size(max = 255, message = "API 패턴은 255자 이하로 입력해주세요.")
    val apiPattern: String,
    @field:Size(max = 255, message = "설명은 255자 이하로 입력해주세요.")
    val description: String? = null,
    @field:PositiveOrZero(message = "0 이상을 입력해주세요.")
    val rateLimitPerSecond: Long? = null,
    @field:PositiveOrZero(message = "0 이상을 입력해주세요.")
    val rateLimitPerMinute: Long? = null,
    @field:PositiveOrZero(message = "0 이상을 입력해주세요.")
    val rateLimitPerDay: Long? = null,
    @field:PositiveOrZero(message = "0 이상을 입력해주세요.")
    val quotaPerDay: Long? = null,
    @field:PositiveOrZero(message = "0 이상을 입력해주세요.")
    val quotaPerMonth: Long? = null
)

data class ApiPolicyResponse(
    val id: Long,
    val tenantId: Long?,
    val planId: Long?,
    val httpMethod: ApiHttpMethod,
    val apiPattern: String,
    val description: String?,
    val rateLimitPerSecond: Long?,
    val rateLimitPerMinute: Long?,
    val rateLimitPerDay: Long?,
    val quotaPerDay: Long?,
    val quotaPerMonth: Long?,
    val createdAt: Instant,
    val updatedAt: Instant
)
