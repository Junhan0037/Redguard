package com.redguard.domain.usage

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface UsageSnapshotRepository : JpaRepository<UsageSnapshot, Long> {
    fun findTop30ByTenantIdAndPeriodTypeOrderBySnapshotDateDesc(tenantId: Long, periodType: UsageSnapshotPeriod): List<UsageSnapshot>

    fun findByTenantIdAndSnapshotDateBetween(tenantId: Long, startDate: LocalDate, endDate: LocalDate): List<UsageSnapshot>
}
