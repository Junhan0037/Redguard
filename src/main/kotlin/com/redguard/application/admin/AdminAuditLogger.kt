package com.redguard.application.admin

import com.redguard.infrastructure.persistence.admin.AdminAuditLogEntity
import com.redguard.infrastructure.persistence.admin.AdminAuditLogJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminAuditLogger(
    private val adminAuditLogJpaRepository: AdminAuditLogJpaRepository
) {
    @Transactional
    fun log(
        actorId: Long?,
        action: AdminAuditAction,
        resourceType: String,
        resourceId: String?,
        payloadDiff: String?,
        ip: String?,
        userAgent: String?
    ) {
        val entry = AdminAuditLogEntity(
            actorId = actorId,
            action = action.name,
            resourceType = resourceType,
            resourceId = resourceId,
            payloadDiff = payloadDiff,
            ip = ip,
            userAgent = userAgent
        )
        adminAuditLogJpaRepository.save(entry)
    }
}

enum class AdminAuditAction {
    AUTH_LOGIN,
    AUTH_LOGIN_FAILED,
    AUTH_REFRESH,
    AUTH_LOGOUT,
    ADMIN_ACCOUNT_UPDATE,
    POLICY_CHANGE,
    TENANT_CHANGE
}
