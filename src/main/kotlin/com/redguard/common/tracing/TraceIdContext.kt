package com.redguard.common.tracing

import org.slf4j.MDC
import java.util.UUID

object TraceIdContext {
    const val HEADER_NAME = "X-Request-Id"
    private const val MDC_KEY = "traceId"

    fun currentTraceId(): String? = MDC.get(MDC_KEY)

    fun bind(traceId: String = UUID.randomUUID().toString()) {
        MDC.put(MDC_KEY, traceId)
    }

    fun clear() {
        MDC.remove(MDC_KEY)
    }
}
