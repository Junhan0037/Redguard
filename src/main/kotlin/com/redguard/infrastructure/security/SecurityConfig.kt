package com.redguard.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.redguard.infrastructure.persistence.admin.AdminUserJpaRepository
import org.springframework.core.env.Environment
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val adminAuthProperties: AdminAuthProperties,
    private val adminUserJpaRepository: AdminUserJpaRepository,
    private val adminTokenStore: AdminTokenStore,
    private val objectMapper: ObjectMapper,
    private val environment: Environment
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun jwtTokenProvider(): JwtTokenProvider = JwtTokenProvider(adminAuthProperties)

    @Bean
    fun authenticationEntryPoint(): AuthenticationEntryPoint = RestAuthenticationEntryPoint(objectMapper)

    @Bean
    fun accessDeniedHandler(): AccessDeniedHandler = RestAccessDeniedHandler(objectMapper)

    @Bean
    fun jwtAuthenticationFilter(
        jwtTokenProvider: JwtTokenProvider,
        authenticationEntryPoint: AuthenticationEntryPoint
    ): JwtAuthenticationFilter =
        JwtAuthenticationFilter(jwtTokenProvider, adminUserJpaRepository, adminTokenStore, authenticationEntryPoint)

    @Bean
    fun filterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
        authenticationEntryPoint: AuthenticationEntryPoint,
        accessDeniedHandler: AccessDeniedHandler
    ): SecurityFilterChain {
        if (isTestProfile()) {
            http
                .csrf { it.disable() }
                .cors { }
                .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
                .authorizeHttpRequests { auth -> auth.anyRequest().permitAll() }
                .exceptionHandling { exception ->
                    exception.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                }
            return http.build()
        }

        http
            .csrf { it.disable() }
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/admin/auth/login", "/admin/auth/refresh").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { exception ->
                exception.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    private fun isTestProfile(): Boolean = environment.activeProfiles.any { it.equals("test", ignoreCase = true) }
}
