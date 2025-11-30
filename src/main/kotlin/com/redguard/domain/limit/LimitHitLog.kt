package com.redguard.domain.limit

import com.redguard.domain.common.AuditableEntity
import com.redguard.domain.tenant.Tenant
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "limit_hit_logs",
    indexes = [
        Index(name = "idx_limit_hit_tenant_occurred_at_desc", columnList = "tenant_id, occurred_at DESC, id DESC"),
        Index(name = "idx_limit_hit_tenant_user_occurred_at", columnList = "tenant_id, user_id, occurred_at DESC, id DESC"),
        Index(name = "idx_limit_hit_tenant_api_occurred_at", columnList = "tenant_id, api_path, occurred_at DESC, id DESC")
    ]
)
class LimitHitLog(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    var tenant: Tenant,

    @Column(name = "user_id", length = 64)
    var userId: String? = null,

    @Column(name = "api_path", nullable = false, length = 255)
    var apiPath: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    var reason: LimitHitReason,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant
) : AuditableEntity()
