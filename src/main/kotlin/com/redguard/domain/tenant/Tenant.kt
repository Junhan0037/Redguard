package com.redguard.domain.tenant

import com.redguard.domain.common.AuditableEntity
import com.redguard.domain.plan.Plan
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "tenants")
class Tenant(
    @Column(name = "name", nullable = false, unique = true, length = 100)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: TenantStatus,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    var plan: Plan
) : AuditableEntity()
