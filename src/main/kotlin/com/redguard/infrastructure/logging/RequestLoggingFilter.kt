package com.redguard.infrastructure.logging

import com.redguard.common.tracing.TraceIdContext
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private val logger = KotlinLogging.logger {}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestLoggingFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val startAt = System.currentTimeMillis()
        logger.debug {
            "Request traceId=${TraceIdContext.currentTraceId()} method=${request.method} uri=${request.requestURI} query=${request.queryString ?: ""}"
        }
        filterChain.doFilter(request, response)
        val duration = System.currentTimeMillis() - startAt
        logger.debug {
            "Response traceId=${TraceIdContext.currentTraceId()} status=${response.status} durationMs=$duration"
        }
    }
}
