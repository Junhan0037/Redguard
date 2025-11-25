package com.redguard.api.dto.tenant

import com.redguard.domain.tenant.TenantStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant

data class TenantCreateRequest(
    @field:NotBlank(message = "테넌트 이름은 필수입니다.")
    @field:Size(max = 100, message = "테넌트 이름은 100자 이하로 입력해주세요.")
    val name: String,

    @field:Positive(message = "유효한 요금제 ID를 입력해주세요.")
    val planId: Long,

    val status: TenantStatus = TenantStatus.ACTIVE
)

data class TenantUpdateRequest(
    @field:NotBlank(message = "테넌트 이름은 필수입니다.")
    @field:Size(max = 100, message = "테넌트 이름은 100자 이하로 입력해주세요.")
    val name: String,

    val status: TenantStatus
)

data class TenantPlanChangeRequest(
    @field:Positive(message = "유효한 요금제 ID를 입력해주세요.")
    val planId: Long
)

data class TenantResponse(
    val id: Long,
    val name: String,
    val status: TenantStatus,
    val plan: PlanResponse,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class PlanResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val rateLimitPerSecond: Long?,
    val rateLimitPerMinute: Long?,
    val rateLimitPerDay: Long?,
    val quotaPerDay: Long?,
    val quotaPerMonth: Long?,
    val createdAt: Instant,
    val updatedAt: Instant
)
