package com.redguard.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint

class RestAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException
    ) {
        if (response.isCommitted) {
            return
        }
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        val body = mapOf(
            "code" to "UNAUTHORIZED",
            "message" to (authException.message ?: "인증이 필요합니다.")
        )
        response.writer.use { it.write(objectMapper.writeValueAsString(body)) }
    }
}
