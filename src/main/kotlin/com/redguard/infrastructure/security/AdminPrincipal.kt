package com.redguard.infrastructure.security

import com.redguard.domain.admin.AdminRole
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

data class AdminPrincipal(
    val id: Long,
    val loginId: String,
    val roles: Set<AdminRole>
) : UserDetails {
    override fun getAuthorities(): Collection<GrantedAuthority> = roles.map { SimpleGrantedAuthority("ROLE_${it.name}") }

    override fun getPassword(): String? = null

    override fun getUsername(): String = loginId

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean = true

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = true
}
