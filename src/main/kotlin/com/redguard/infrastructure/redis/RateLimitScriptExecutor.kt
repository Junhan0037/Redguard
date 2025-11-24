package com.redguard.infrastructure.redis

import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import com.redguard.infrastructure.redis.script.RateLimitLuaScript
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.RedisException
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Lua 스크립트 실행 및 NOSCRIPT 재시도를 담당하는 실행기
 */
@Component
class RateLimitScriptExecutor(
    private val redisTemplate: StringRedisTemplate,
    private val scriptInitializer: RateLimitScriptInitializer
) {

    private val logger = KotlinLogging.logger {}
    private val script = RateLimitLuaScript.redisScript()

    /**
     * Lua 스크립트를 실행하고 NOSCRIPT 발생 시 재로딩 후 재실행
     */
    fun execute(payload: RateLimitScriptPayload): List<Long> {
        val args = payload.args.toTypedArray()
        try {
            val rawResult = redisTemplate.execute(script, payload.keys, *args)
            return validateResult(rawResult)
        } catch (ex: RedisSystemException) {
            if (!isNoScript(ex)) {
                throw RedGuardException(ErrorCode.INTERNAL_SERVER_ERROR, "Redis Lua 스크립트 실행 중 오류가 발생했습니다.", ex)
            }
            logger.warn(ex) { "Redis에 Lua 스크립트 SHA가 존재하지 않아 재로딩 후 재시도합니다." }

            scriptInitializer.reload()

            val retryResult = redisTemplate.execute(script, payload.keys, *args)
            return validateResult(retryResult)
        }
    }

    /**
     * 스크립트 반환값이 null이거나 기대 길이보다 짧으면 예외로 변환
     */
    private fun validateResult(rawResult: List<Long>?): List<Long> {
        if (rawResult == null) {
            throw RedGuardException(ErrorCode.INTERNAL_SERVER_ERROR, "Redis Lua 스크립트 실행 결과가 없습니다.")
        }
        if (rawResult.size < 16) {
            throw RedGuardException(ErrorCode.INTERNAL_SERVER_ERROR, "Redis Lua 스크립트 결과 형식이 올바르지 않습니다.")
        }
        return rawResult
    }

    /**
     * RedisSystemException이 NOSCRIPT 계열인지 판별
     */
    private fun isNoScript(exception: RedisSystemException): Boolean {
        val message = exception.message?.lowercase() ?: ""
        val causeMessage = exception.cause?.message?.lowercase() ?: ""
        return message.contains("noscript") || causeMessage.contains("noscript")
    }

    /**
     * Redis 계열 장애인지 재귀적으로 확인
     */
    fun isRedisFailure(exception: Throwable?): Boolean {
        if (exception == null) return false
        return when (exception) {
            is RedisSystemException -> true
            is RedisException -> true
            is DataAccessException -> true
            else -> isRedisFailure(exception.cause)
        }
    }
}
