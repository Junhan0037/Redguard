package com.redguard.domain.tenant

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TenantRepository : JpaRepository<Tenant, Long> {
    fun findByName(name: String): Tenant?

    @EntityGraph(attributePaths = ["plan"])
    fun findWithPlanById(id: Long): Tenant?

    @EntityGraph(attributePaths = ["plan"])
    fun findAllBy(): List<Tenant>
}
