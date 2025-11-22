package com.redguard.domain.plan

import com.redguard.domain.common.AuditableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "plans")
class Plan(
    @Column(name = "name", nullable = false, unique = true, length = 50)
    var name: String,

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
) : AuditableEntity()
