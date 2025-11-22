package com.redguard.infrastructure.logging

import com.redguard.common.tracing.TraceIdContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val incomingTraceId = request.getHeader(TraceIdContext.HEADER_NAME)?.takeIf { it.isNotBlank() }
        val traceId = incomingTraceId ?: UUID.randomUUID().toString()
        TraceIdContext.bind(traceId)
        response.addHeader(TraceIdContext.HEADER_NAME, traceId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            TraceIdContext.clear()
        }
    }
}
