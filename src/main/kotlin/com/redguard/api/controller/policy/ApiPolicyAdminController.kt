package com.redguard.api.controller.policy

import com.redguard.api.dto.ApiResponse
import com.redguard.api.dto.policy.ApiPolicyCreateRequest
import com.redguard.api.dto.policy.ApiPolicyResponse
import com.redguard.api.dto.policy.ApiPolicyUpdateRequest
import com.redguard.application.policy.ApiPolicyInfo
import com.redguard.application.policy.ApiPolicyManagementUseCase
import com.redguard.application.policy.ApiPolicySearchFilter
import com.redguard.application.policy.CreateApiPolicyCommand
import com.redguard.application.policy.UpdateApiPolicyCommand
import com.redguard.domain.policy.ApiHttpMethod
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/admin/api-policies")
class ApiPolicyAdminController(
    private val apiPolicyManagementUseCase: ApiPolicyManagementUseCase
) {

    @PostMapping
    fun create(
        @Valid @RequestBody request: ApiPolicyCreateRequest
    ): ResponseEntity<ApiResponse<ApiPolicyResponse>> {
        val result = apiPolicyManagementUseCase.create(request.toCommand())
        val location = URI.create("/admin/api-policies/${result.id}")
        return ResponseEntity.created(location).body(ApiResponse(data = result.toResponse()))
    }

    @PutMapping("/{policyId}")
    fun update(
        @PathVariable policyId: Long,
        @Valid @RequestBody request: ApiPolicyUpdateRequest
    ): ApiResponse<ApiPolicyResponse> =
        ApiResponse(data = apiPolicyManagementUseCase.update(policyId, request.toCommand()).toResponse())

    @GetMapping("/{policyId}")
    fun get(
        @PathVariable policyId: Long
    ): ApiResponse<ApiPolicyResponse> =
        ApiResponse(data = apiPolicyManagementUseCase.get(policyId).toResponse())

    @GetMapping
    fun search(
        @RequestParam(required = false) tenantId: Long?,
        @RequestParam(required = false) planId: Long?,
        @RequestParam(required = false) apiPattern: String?,
        @RequestParam(required = false) httpMethod: ApiHttpMethod?
    ): ApiResponse<List<ApiPolicyResponse>> {
        val results = apiPolicyManagementUseCase.search(
            ApiPolicySearchFilter(
                tenantId = tenantId,
                planId = planId,
                apiPattern = apiPattern,
                httpMethod = httpMethod
            )
        )
        return ApiResponse(data = results.map { it.toResponse() })
    }

    @DeleteMapping("/{policyId}")
    fun delete(
        @PathVariable policyId: Long
    ): ApiResponse<String> {
        apiPolicyManagementUseCase.delete(policyId)
        return ApiResponse.empty()
    }

    private fun ApiPolicyCreateRequest.toCommand() = CreateApiPolicyCommand(
        tenantId = tenantId,
        planId = planId,
        httpMethod = httpMethod,
        apiPattern = apiPattern.trim(),
        description = description?.trim(),
        rateLimitPerSecond = rateLimitPerSecond,
        rateLimitPerMinute = rateLimitPerMinute,
        rateLimitPerDay = rateLimitPerDay,
        quotaPerDay = quotaPerDay,
        quotaPerMonth = quotaPerMonth
    )

    private fun ApiPolicyUpdateRequest.toCommand() = UpdateApiPolicyCommand(
        tenantId = tenantId,
        planId = planId,
        httpMethod = httpMethod,
        apiPattern = apiPattern.trim(),
        description = description?.trim(),
        rateLimitPerSecond = rateLimitPerSecond,
        rateLimitPerMinute = rateLimitPerMinute,
        rateLimitPerDay = rateLimitPerDay,
        quotaPerDay = quotaPerDay,
        quotaPerMonth = quotaPerMonth
    )

    private fun ApiPolicyInfo.toResponse(): ApiPolicyResponse = ApiPolicyResponse(
        id = id,
        tenantId = tenantId,
        planId = planId,
        httpMethod = httpMethod,
        apiPattern = apiPattern,
        description = description,
        rateLimitPerSecond = rateLimitPerSecond,
        rateLimitPerMinute = rateLimitPerMinute,
        rateLimitPerDay = rateLimitPerDay,
        quotaPerDay = quotaPerDay,
        quotaPerMonth = quotaPerMonth,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
