package com.redguard.domain.policy

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface ApiPolicyRepository : JpaRepository<ApiPolicy, Long>, JpaSpecificationExecutor<ApiPolicy> {
    fun findByTenantIdAndHttpMethodAndApiPattern(tenantId: Long, httpMethod: ApiHttpMethod, apiPattern: String): ApiPolicy?

    fun findByPlanIdAndHttpMethodAndApiPattern(planId: Long, httpMethod: ApiHttpMethod, apiPattern: String): ApiPolicy?

    fun existsByPlanId(planId: Long): Boolean
}
