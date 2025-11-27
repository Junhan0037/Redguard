package com.redguard.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler

class RestAccessDeniedHandler(
    private val objectMapper: ObjectMapper
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException
    ) {
        if (response.isCommitted) {
            return
        }
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        val body = mapOf(
            "code" to "FORBIDDEN",
            "message" to (accessDeniedException.message ?: "접근이 거부되었습니다.")
        )
        response.writer.use { it.write(objectMapper.writeValueAsString(body)) }
    }
}
