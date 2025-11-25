package com.redguard.api.controller.tenant

import com.redguard.api.dto.ApiResponse
import com.redguard.api.dto.tenant.PlanResponse
import com.redguard.api.dto.tenant.TenantCreateRequest
import com.redguard.api.dto.tenant.TenantPlanChangeRequest
import com.redguard.api.dto.tenant.TenantResponse
import com.redguard.api.dto.tenant.TenantUpdateRequest
import com.redguard.application.plan.PlanInfo
import com.redguard.application.tenant.ChangeTenantPlanCommand
import com.redguard.application.tenant.CreateTenantCommand
import com.redguard.application.tenant.TenantInfo
import com.redguard.application.tenant.TenantManagementUseCase
import com.redguard.application.tenant.UpdateTenantCommand
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/admin/tenants")
class TenantAdminController(
    private val tenantManagementUseCase: TenantManagementUseCase
) {

    @PostMapping
    fun create(
        @Valid @RequestBody request: TenantCreateRequest
    ): ResponseEntity<ApiResponse<TenantResponse>> {
        val result = tenantManagementUseCase.create(request.toCommand())
        val location = URI.create("/admin/tenants/${result.id}")
        return ResponseEntity.created(location).body(ApiResponse(data = result.toResponse()))
    }

    @GetMapping("/{tenantId}")
    fun get(
        @PathVariable tenantId: Long
    ): ApiResponse<TenantResponse> {
        val result = tenantManagementUseCase.get(tenantId)
        return ApiResponse(data = result.toResponse())
    }

    @GetMapping
    fun list(): ApiResponse<List<TenantResponse>> {
        val results = tenantManagementUseCase.list()
        return ApiResponse(data = results.map { it.toResponse() })
    }

    @PutMapping("/{tenantId}")
    fun update(
        @PathVariable tenantId: Long,
        @Valid @RequestBody request: TenantUpdateRequest
    ): ApiResponse<TenantResponse> {
        val result = tenantManagementUseCase.update(tenantId, request.toCommand())
        return ApiResponse(data = result.toResponse())
    }

    @PatchMapping("/{tenantId}/plan")
    fun changePlan(
        @PathVariable tenantId: Long,
        @Valid @RequestBody request: TenantPlanChangeRequest
    ): ApiResponse<TenantResponse> {
        val result = tenantManagementUseCase.changePlan(tenantId, ChangeTenantPlanCommand(planId = request.planId))
        return ApiResponse(data = result.toResponse())
    }

    @DeleteMapping("/{tenantId}")
    fun delete(
        @PathVariable tenantId: Long
    ): ApiResponse<String> {
        tenantManagementUseCase.delete(tenantId)
        return ApiResponse.empty()
    }

    private fun TenantCreateRequest.toCommand() = CreateTenantCommand(
        name = name.trim(),
        planId = planId,
        status = status
    )

    private fun TenantUpdateRequest.toCommand() = UpdateTenantCommand(
        name = name.trim(),
        status = status
    )

    private fun TenantInfo.toResponse() = TenantResponse(
        id = id,
        name = name,
        status = status,
        plan = plan.toResponse(),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun PlanInfo.toResponse() = PlanResponse(
        id = id,
        name = name,
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
