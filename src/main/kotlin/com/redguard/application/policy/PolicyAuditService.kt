package com.redguard.application.policy

import com.fasterxml.jackson.databind.ObjectMapper
import com.redguard.application.admin.AdminAuditContext
import com.redguard.application.admin.AdminAuditLogger
import com.redguard.application.admin.AdminAuditAction
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

/**
 * 정책 변경 시 감사 로그(DB)와 구조화 로그를 함께 남기는 서비스
 * - AdminAuditLogger를 통해 영속 레이어에 기록하면서, 운영 모니터링을 위한 JSON 형태 로그를 남김
 * - 로깅 실패가 본업무 흐름을 막지 않도록 모든 작업은 안전하게 처리
 */
@Service
class PolicyAuditService(
    private val adminAuditLogger: AdminAuditLogger,
    private val objectMapper: ObjectMapper
) {

    private val logger = KotlinLogging.logger {}

    /**
     * 정책 리소스 변경을 감사 로그 및 구조화 로그로 남김
     */
    fun logPolicyChange(
        resourceType: PolicyResourceType,
        resourceId: String,
        changeType: PolicyChangeType,
        before: Any?,
        after: Any?,
        auditContext: AdminAuditContext?
    ) {
        val diffPayload = mapOf(
            "changeType" to changeType.name.lowercase(),
            "resourceType" to resourceType.name,
            "resourceId" to resourceId,
            "before" to before,
            "after" to after
        )

        runCatching {
            adminAuditLogger.log(
                actorId = auditContext?.actorId,
                action = AdminAuditAction.POLICY_CHANGE,
                resourceType = resourceType.name,
                resourceId = resourceId,
                payloadDiff = serialize(diffPayload),
                ip = auditContext?.ip,
                userAgent = auditContext?.userAgent
            )
        }.onFailure { ex ->
            logger.warn(ex) { "정책 변경 감사 로그 적재 실패 resourceType=${resourceType.name} resourceId=$resourceId changeType=${changeType.name}" }
        }

        val structuredLog = mapOf(
            "event" to "policy_change",
            "resourceType" to resourceType.name,
            "resourceId" to resourceId,
            "changeType" to changeType.name.lowercase(),
            "actorId" to auditContext?.actorId,
            "ip" to auditContext?.ip,
            "userAgent" to auditContext?.userAgent,
            "payload" to mapOf(
                "before" to before,
                "after" to after
            )
        )
        logger.info { serialize(structuredLog) }
    }

    /**
     * ObjectMapper로 JSON 직렬화하며, 실패 시 toString() 결과로 대체
     */
    private fun serialize(payload: Any?): String =
        runCatching { objectMapper.writeValueAsString(payload) }
            .getOrElse { payload.toString() }
}

enum class PolicyResourceType {
    API_POLICY,
    PLAN,
    TENANT_PLAN
}

enum class PolicyChangeType {
    CREATED,
    UPDATED,
    DELETED
}
