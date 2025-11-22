package com.redguard.domain.policy

import com.redguard.domain.common.AuditableEntity
import com.redguard.domain.plan.Plan
import com.redguard.domain.tenant.Tenant
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated

@Entity
@Table(name = "api_policies")
class ApiPolicy(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    var tenant: Tenant? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    var plan: Plan? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "http_method", nullable = false, length = 10)
    var httpMethod: ApiHttpMethod,

    @Column(name = "api_pattern", nullable = false, length = 255)
    var apiPattern: String,

    @Column(name = "description", columnDefinition = "text")
    var description: String? = null,

    @Column(name = "rate_limit_per_second")
    var rateLimitPerSecond: Long? = null,

    @Column(name = "rate_limit_per_minute")
    var rateLimitPerMinute: Long? = null,

    @Column(name = "rate_limit_per_day")
    var rateLimitPerDay: Long? = null,

    @Column(name = "quota_per_day")
    var quotaPerDay: Long? = null,

    @Column(name = "quota_per_month")
    var quotaPerMonth: Long? = null
) : AuditableEntity() {

    @PrePersist
    @PreUpdate
    fun validateTarget() {
        if (tenant == null && plan == null) {
            throw IllegalStateException("ApiPolicy must target at least a tenant or a plan")
        }
    }
}
