package com.redguard.api.controller.plan

import com.redguard.api.dto.ApiResponse
import com.redguard.api.dto.plan.PlanCreateRequest
import com.redguard.api.dto.plan.PlanResponse
import com.redguard.api.dto.plan.PlanUpdateRequest
import com.redguard.application.admin.AdminAuditContext
import com.redguard.application.plan.CreatePlanCommand
import com.redguard.application.plan.PlanInfo
import com.redguard.application.plan.PlanManagementUseCase
import com.redguard.application.plan.UpdatePlanCommand
import com.redguard.infrastructure.security.AdminPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/admin/plans")
class PlanAdminController(
    private val planManagementUseCase: PlanManagementUseCase
) {

    @PostMapping
    fun create(
        @Valid @RequestBody request: PlanCreateRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
        httpServletRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<PlanResponse>> {
        val result = planManagementUseCase.create(request.toCommand(), buildAuditContext(principal, httpServletRequest))
        val location = URI.create("/admin/plans/${result.id}")
        return ResponseEntity.created(location).body(ApiResponse(data = result.toResponse()))
    }

    @GetMapping("/{planId}")
    fun get(
        @PathVariable planId: Long
    ): ApiResponse<PlanResponse> = ApiResponse(data = planManagementUseCase.get(planId).toResponse())

    @GetMapping
    fun list(): ApiResponse<List<PlanResponse>> =
        ApiResponse(data = planManagementUseCase.list().map { it.toResponse() })

    @PutMapping("/{planId}")
    fun update(
        @PathVariable planId: Long,
        @Valid @RequestBody request: PlanUpdateRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
        httpServletRequest: HttpServletRequest
    ): ApiResponse<PlanResponse> =
        ApiResponse(data = planManagementUseCase.update(planId, request.toCommand(), buildAuditContext(principal, httpServletRequest)).toResponse())

    @DeleteMapping("/{planId}")
    fun delete(
        @PathVariable planId: Long,
        @AuthenticationPrincipal principal: AdminPrincipal?,
        httpServletRequest: HttpServletRequest
    ): ApiResponse<String> {
        planManagementUseCase.delete(planId, buildAuditContext(principal, httpServletRequest))
        return ApiResponse.empty()
    }

    private fun PlanCreateRequest.toCommand() = CreatePlanCommand(
        name = name.trim(),
        description = description?.trim(),
        rateLimitPerSecond = rateLimitPerSecond,
        rateLimitPerMinute = rateLimitPerMinute,
        rateLimitPerDay = rateLimitPerDay,
        quotaPerDay = quotaPerDay,
        quotaPerMonth = quotaPerMonth
    )

    private fun PlanUpdateRequest.toCommand() = UpdatePlanCommand(
        name = name.trim(),
        description = description?.trim(),
        rateLimitPerSecond = rateLimitPerSecond,
        rateLimitPerMinute = rateLimitPerMinute,
        rateLimitPerDay = rateLimitPerDay,
        quotaPerDay = quotaPerDay,
        quotaPerMonth = quotaPerMonth
    )

    private fun PlanInfo.toResponse(): PlanResponse = PlanResponse(
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

    /**
     * 감사 로그에 필요한 요청 컨텍스트를 구성
     */
    private fun buildAuditContext(principal: AdminPrincipal?, request: HttpServletRequest) = AdminAuditContext(
        actorId = principal?.id,
        ip = clientIp(request),
        userAgent = request.getHeader("User-Agent")
    )

    private fun clientIp(request: HttpServletRequest): String? =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.remoteAddr
}
