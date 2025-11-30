package com.redguard.domain.usage

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface UsageSnapshotRepository : JpaRepository<UsageSnapshot, Long> {
    fun findTop30ByTenantIdAndPeriodTypeOrderBySnapshotDateDesc(tenantId: Long, periodType: UsageSnapshotPeriod): List<UsageSnapshot>

    @Query(
        """
        select u
        from UsageSnapshot u
        where u.tenant.id = :tenantId
          and u.snapshotDate >= :startDate
          and u.snapshotDate <= :endDate
          and (:periodType is null or u.periodType = :periodType)
          and (:userId is null or u.userId = :userId)
          and (:apiPath is null or u.apiPath = :apiPath)
    """
    )
    fun search(
        @Param("tenantId") tenantId: Long,
        @Param("periodType") periodType: UsageSnapshotPeriod?,
        @Param("userId") userId: String?,
        @Param("apiPath") apiPath: String?,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
        pageable: Pageable
    ): Page<UsageSnapshot>

    @Query(
        """
        select u
        from UsageSnapshot u
        where u.tenant.id = :tenantId
          and u.snapshotDate >= :startDate
          and u.snapshotDate <= :endDate
          and u.periodType = :periodType
          and (:userId is null or u.userId = :userId)
          and (:apiPath is null or u.apiPath = :apiPath)
    """
    )
    fun findAllByFilters(
        @Param("tenantId") tenantId: Long,
        @Param("periodType") periodType: UsageSnapshotPeriod,
        @Param("userId") userId: String?,
        @Param("apiPath") apiPath: String?,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate
    ): List<UsageSnapshot>
}
