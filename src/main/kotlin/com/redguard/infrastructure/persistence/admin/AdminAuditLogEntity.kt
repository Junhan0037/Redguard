package com.redguard.infrastructure.persistence.admin

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(
    name = "admin_audit_log",
    indexes = [
        Index(name = "idx_admin_audit_actor", columnList = "actor_id"),
        Index(name = "idx_admin_audit_action", columnList = "action"),
        Index(name = "idx_admin_audit_occurred_at", columnList = "occurred_at")
    ]
)
class AdminAuditLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "actor_id")
    val actorId: Long? = null,

    @Column(name = "action", nullable = false, length = 100)
    val action: String,

    @Column(name = "resource_type", nullable = false, length = 50)
    val resourceType: String,

    @Column(name = "resource_id", length = 100)
    val resourceId: String? = null,

    @Column(name = "payload_diff", columnDefinition = "TEXT")
    val payloadDiff: String? = null,

    @Column(name = "ip", length = 64)
    val ip: String? = null,

    @Column(name = "user_agent", length = 512)
    val userAgent: String? = null,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: LocalDateTime = LocalDateTime.now()
)
