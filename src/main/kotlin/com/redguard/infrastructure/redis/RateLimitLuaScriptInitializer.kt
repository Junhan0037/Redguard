package com.redguard.infrastructure.redis

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.infrastructure.redis.script.RateLimitLuaScript
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.data.redis.connection.StringRedisConnection
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * 멀티 인스턴스 환경에서 Lua 스크립트 SHA를 일관되게 유지하기 위한 초기화기
 * - 앱 기동 시 SCRIPT LOAD + SHA 기록을 파이프라인으로 실행해 서버별 스크립트 버전을 맞춤
 * - NOSCRIPT 등으로 스크립트가 사라진 경우 재로딩을 지원
 */
interface RateLimitScriptInitializer {
    fun ensureLoaded()
    fun reload()
}

@Component
class RateLimitLuaScriptInitializer(
    private val stringRedisTemplate: StringRedisTemplate
) : RateLimitScriptInitializer {

    private val logger = KotlinLogging.logger {}
    private val scriptSource: String = RateLimitLuaScript.SOURCE // 실제 Lua 스크립트 원본
    private val expectedSha: String = requireNotNull(RateLimitLuaScript.redisScript().sha1) { "RateLimit Lua 스크립트 SHA 계산에 실패했습니다." } // 로드해야 할 목표 SHA
    private val initialized = AtomicBoolean(false) // 다중 인스턴스/스레드 중복 초기화 방지 플래그

    override fun ensureLoaded() {
        // 이미 초기화되었다면 재실행하지 않음
        if (initialized.get()) return

        synchronized(this) {
            if (initialized.get()) return
            loadAndPublishSha()
            initialized.set(true)
        }
    }

    /**
     * NOSCRIPT 등으로 스크립트가 사라졌을 때 강제 재로딩
     */
    override fun reload() {
        initialized.set(false)
        loadAndPublishSha()
        initialized.set(true)
    }

    private fun loadAndPublishSha() {
        try {
            val results = stringRedisTemplate.executePipelined { connection ->
                // SCRIPT LOAD와 SHA 기록/확인까지 한 번에 파이프라인 처리해 경쟁 상태 최소화
                val stringConnection = connection as StringRedisConnection
                stringConnection.scriptLoad(scriptSource)
                stringConnection.set(SCRIPT_SHA_KEY, expectedSha)
                stringConnection.get(SCRIPT_SHA_KEY)
            }

            val loadedSha = results.getOrNull(0)?.toUtf8String()
            val persistedSha = results.getOrNull(2)?.toUtf8String()

            if (loadedSha != null && loadedSha != expectedSha) {
                logger.error { "로드된 Lua 스크립트 SHA가 기대값과 다릅니다. loaded=$loadedSha, expected=$expectedSha" }
                throw RedGuardException(ErrorCode.INTERNAL_SERVER_ERROR, "Redis Lua 스크립트 SHA 검증에 실패했습니다.")
            }

            // 동시 기동 시 최신 SHA로 덮어써 일관성을 유지
            if (persistedSha != expectedSha) {
                stringRedisTemplate.opsForValue().set(SCRIPT_SHA_KEY, expectedSha)
            }

            logger.info { "RateLimit Lua 스크립트 로딩 완료 sha=$expectedSha" }
        } catch (ex: Exception) {
            logger.error(ex) { "RateLimit Lua 스크립트 초기화에 실패했습니다." }
            throw RedGuardException(ErrorCode.INTERNAL_SERVER_ERROR, "Redis Lua 스크립트 초기화 중 오류가 발생했습니다.", ex)
        }
    }

    private fun Any?.toUtf8String(): String? = when (this) {
        null -> null
        is ByteArray -> String(this, Charsets.UTF_8)
        else -> this.toString()
    }

    companion object {
        private const val SCRIPT_SHA_KEY = "rl:script:rate-limit:sha"
    }
}
