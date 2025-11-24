package com.redguard.api.dto.ratelimit

import com.redguard.domain.policy.ApiHttpMethod
import com.redguard.domain.ratelimit.RateLimitScope
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PastOrPresent
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import kotlin.reflect.KClass

/**
 * `/internal/rate-limit/check` 요청 DTO
 * 스코프별 필수 필드를 엄격히 검증해 잘못된 입력으로 인한 무분별한 키 생성/오버카운트 위험 방지
 */
@ConsistentRateLimitDimensions
data class RateLimitCheckRequest(
    @field:NotNull(message = "scope는 필수입니다.")
    val scope: RateLimitScope,

    @field:NotBlank(message = "tenantId는 필수입니다.")
    @field:Size(max = 128, message = "tenantId는 128자 이하로 입력해야 합니다.")
    @field:Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "tenantId는 영문, 숫자, '.', '_', '-'만 허용됩니다.")
    val tenantId: String,

    @field:Size(max = 128, message = "userId는 128자 이하로 입력해야 합니다.")
    @field:Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "userId는 영문, 숫자, '.', '_', '-'만 허용됩니다.")
    val userId: String? = null,

    @field:Size(max = 512, message = "apiPath는 512자 이하로 입력해야 합니다.")
    @field:Pattern(regexp = "^/[\\w\\-./]*\\*?$", message = "apiPath는 '/'로 시작해야 하며 공백을 포함할 수 없습니다.")
    val apiPath: String? = null,

    @field:NotNull(message = "httpMethod는 필수입니다.")
    val httpMethod: ApiHttpMethod,

    @field:NotNull(message = "timestamp는 필수입니다.")
    @field:PastOrPresent(message = "timestamp는 현재 시각 이후일 수 없습니다.")
    val timestamp: Instant,

    @field:Positive(message = "increment는 1 이상의 값이어야 합니다.")
    val increment: Long = 1
)

/**
 * 스코프별 필수 차원(userId/apiPath) 충족 여부를 검증하는 커스텀 제약 어노테이션
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ConsistentRateLimitDimensionsValidator::class])
annotation class ConsistentRateLimitDimensions(
    val message: String = "Rate Limit 스코프에 필요한 필드가 누락되었습니다.",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

internal class ConsistentRateLimitDimensionsValidator : ConstraintValidator<ConsistentRateLimitDimensions, RateLimitCheckRequest> {
    override fun isValid(value: RateLimitCheckRequest?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true

        var valid = true
        context.disableDefaultConstraintViolation()

        if (value.scope.requiresUserId && value.userId.isNullOrBlank()) {
            addViolation(context, "userId", "scope=${value.scope.name}인 경우 userId는 필수입니다.")
            valid = false
        }

        if (value.scope.requiresApiPath) {
            if (value.apiPath.isNullOrBlank()) {
                addViolation(context, "apiPath", "scope=${value.scope.name}인 경우 apiPath는 필수입니다.")
                valid = false
            }
        } else if (value.apiPath != null && value.apiPath.isBlank()) {
            addViolation(context, "apiPath", "apiPath는 공백일 수 없습니다.")
            valid = false
        }

        if (!value.userId.isNullOrEmpty() && value.userId.isBlank()) {
            addViolation(context, "userId", "userId는 공백일 수 없습니다.")
            valid = false
        }

        return valid
    }

    private fun addViolation(context: ConstraintValidatorContext, property: String, message: String) {
        context.buildConstraintViolationWithTemplate(message)
            .addPropertyNode(property)
            .addConstraintViolation()
    }
}
