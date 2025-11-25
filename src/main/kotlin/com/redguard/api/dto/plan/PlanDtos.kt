package com.redguard.api.dto.plan

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant

data class PlanCreateRequest(
    @field:NotBlank(message = "요금제 이름은 필수입니다.")
    @field:Size(max = 50, message = "요금제 이름은 50자 이하로 입력해주세요.")
    val name: String,
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

data class PlanUpdateRequest(
    @field:NotBlank(message = "요금제 이름은 필수입니다.")
    @field:Size(max = 50, message = "요금제 이름은 50자 이하로 입력해주세요.")
    val name: String,
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
