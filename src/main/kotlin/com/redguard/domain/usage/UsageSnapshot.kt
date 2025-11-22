package com.redguard.domain.usage

import com.redguard.domain.common.AuditableEntity
import com.redguard.domain.tenant.Tenant
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

@Entity
@Table(
    name = "usage_snapshots",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_usage_snapshot_scope",
            columnNames = ["tenant_id", "user_id", "api_path", "snapshot_date", "period_type"]
        )
    ]
)
class UsageSnapshot(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    var tenant: Tenant,

    @Column(name = "user_id", length = 64)
    var userId: String? = null,

    @Column(name = "api_path", length = 255)
    var apiPath: String? = null,

    @Column(name = "snapshot_date", nullable = false)
    var snapshotDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 20)
    var periodType: UsageSnapshotPeriod,

    @Column(name = "total_count", nullable = false)
    var totalCount: Long
) : AuditableEntity()
