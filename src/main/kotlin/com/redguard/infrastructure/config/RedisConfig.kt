package com.redguard.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.redguard.common.exception.ErrorCode
import com.redguard.common.exception.RedGuardException
import io.lettuce.core.ClientOptions
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisClusterConfiguration
import org.springframework.data.redis.connection.RedisConfiguration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisNode
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisSentinelConfiguration
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.GenericToStringSerializer
import org.springframework.data.redis.serializer.RedisSerializer
import java.time.Duration

@Configuration
class RedisConfig(
    private val redisProperties: RedisProperties,
    private val objectMapper: ObjectMapper
) {

    /**
     * Lettuce 공용 리소스 빈
     * 이벤트 루프/스레드풀을 공유해 커넥션 팩토리 간 불필요한 리소스 중복 방지
     */
    @Bean(destroyMethod = "shutdown")
    fun lettuceClientResources(): ClientResources = DefaultClientResources.create()

    /**
     * 프로필 설정에 따라 스탠드얼론/센티넬/클러스터 중 적절한 커넥션 팩토리를 생성
     */
    @Bean
    fun redisConnectionFactory(clientResources: ClientResources): LettuceConnectionFactory {
        // 클라이언트 측 옵션(타임아웃, 풀 등)을 먼저 적용
        val clientConfiguration = buildClientConfiguration(clientResources)

        // 서버 토폴로지(standalone/sentinel/cluster)를 프로퍼티에 따라 결정
        val serverConfiguration = redisConfiguration()

        return LettuceConnectionFactory(serverConfiguration, clientConfiguration)
    }

    /**
     * Lua 파라미터 전달 등 문자열 기반 키-값 저장용 템플릿
     */
    @Bean
    fun stringRedisTemplate(connectionFactory: RedisConnectionFactory): StringRedisTemplate {
        return StringRedisTemplate(connectionFactory).apply {
            // 키/값/해시 모두 문자열 직렬화로 고정
            keySerializer = RedisSerializer.string()
            valueSerializer = RedisSerializer.string()
            hashKeySerializer = RedisSerializer.string()
            hashValueSerializer = RedisSerializer.string()
        }
    }

    /**
     * Rate Limit 카운터/슬라이딩 윈도우 계산에 맞춘 Long 전용 템플릿
     */
    @Bean
    fun longRedisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, Long> {
        return RedisTemplate<String, Long>().apply {
            setConnectionFactory(connectionFactory)
            // 키는 문자열, 값은 숫자 직렬화로 지정해 CLI/모니터링 호환성 향상
            keySerializer = RedisSerializer.string()
            valueSerializer = GenericToStringSerializer(Long::class.java)
            hashKeySerializer = RedisSerializer.string()
            hashValueSerializer = GenericToStringSerializer(Long::class.java)
        }
    }

    /**
     * 정책 캐싱 등 복합 객체를 저장할 때 사용하는 JSON 직렬화 템플릿.
     */
    @Bean
    fun jsonRedisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, Any> {
        val serializer = GenericJackson2JsonRedisSerializer(objectMapper)
        return RedisTemplate<String, Any>().apply {
            setConnectionFactory(connectionFactory)
            // 키는 문자열, 값은 JSON 직렬화로 복합 객체 캐싱에 사용한다.
            keySerializer = RedisSerializer.string()
            valueSerializer = serializer
            hashKeySerializer = RedisSerializer.string()
            hashValueSerializer = serializer
        }
    }

    /**
     * 타임아웃,풀 정책을 중앙화해 네트워크 장애 시에도 예측 가능한 동작을 보장
     */
    private fun buildClientConfiguration(clientResources: ClientResources): LettuceClientConfiguration {
        val poolConfig = buildPoolConfig(redisProperties.lettuce?.pool)
        val builder = if (poolConfig != null) {
            // 풀 설정이 있으면 Pooling 클라이언트 구성
            LettucePoolingClientConfiguration.builder().poolConfig(poolConfig)
        } else {
            // 기본(non-pooling) 클라이언트
            LettuceClientConfiguration.builder()
        }

        val commandTimeout = redisProperties.timeout ?: DEFAULT_COMMAND_TIMEOUT
        val shutdownTimeout = redisProperties.lettuce?.shutdownTimeout ?: DEFAULT_SHUTDOWN_TIMEOUT

        builder
            .clientResources(clientResources) // 이벤트 루프/스레드풀 공유
            .commandTimeout(commandTimeout) // 커맨드별 타임아웃
            .shutdownTimeout(shutdownTimeout) // 셧다운 시 대기 시간
            .clientOptions(
                ClientOptions.builder()
                    .timeoutOptions(TimeoutOptions.enabled()) // 타임아웃 적용
                    .autoReconnect(true) // 네트워크 단절 시 자동 재연결
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS) // 연결 끊김 시 명령 거부
                    .build()
            )

        if (redisProperties.ssl?.isEnabled == true) {
            builder.useSsl() // SSL 활성화
        }

        redisProperties.clientName?.let { builder.clientName(it) } // 모니터링용 클라이언트 이름

        return builder.build()
    }

    /**
     * 프로퍼티에 따라 Standalone/Sentinel/Cluster 중 하나의 RedisConfiguration을 반환
     */
    private fun redisConfiguration(): RedisConfiguration {
        val sentinel = redisProperties.sentinel
        if (sentinel != null) {
            return sentinelConfiguration(sentinel) // Sentinel 설정 우선
        }

        val cluster = redisProperties.cluster
        if (cluster != null) {
            return clusterConfiguration(cluster) // Cluster 설정 적용
        }

        // 그 외에는 스탠드얼론
        return standaloneConfiguration()
    }

    /**
     * Sentinel 목록과 인증 정보를 반영한 Sentinel 구성을 빌드
     */
    private fun sentinelConfiguration(sentinel: RedisProperties.Sentinel): RedisSentinelConfiguration {
        val configuration = RedisSentinelConfiguration(sentinel.master, emptySet<String>()).apply {
            sentinel.nodes.forEach { node ->
                val (host, port) = parseHostAndPort(node)
                sentinels.add(RedisNode(host, port)) // Sentinel 노드 등록
            }
            database = redisProperties.database
        }
        redisProperties.username?.takeIf { it.isNotBlank() }?.let { configuration.username = it }
        redisProperties.password?.takeIf { it.isNotBlank() }?.let { configuration.setPassword(RedisPassword.of(it)) }
        sentinel.password?.takeIf { it.isNotBlank() }?.let { configuration.setSentinelPassword(RedisPassword.of(it)) }
        return configuration
    }

    /**
     * 클러스터 노드 목록을 구성하고 리다이렉트 설정을 적용
     */
    private fun clusterConfiguration(cluster: RedisProperties.Cluster): RedisClusterConfiguration {
        val configuration = RedisClusterConfiguration(cluster.nodes)
        cluster.maxRedirects?.let { configuration.maxRedirects = it } // 리다이렉트 허용 횟수
        redisProperties.username?.takeIf { it.isNotBlank() }?.let { configuration.username = it }
        redisProperties.password?.takeIf { it.isNotBlank() }?.let { configuration.setPassword(RedisPassword.of(it)) }
        return configuration
    }

    /**
     * 단일 노드 Redis 설정을 생성
     */
    private fun standaloneConfiguration(): RedisStandaloneConfiguration =
        RedisStandaloneConfiguration().apply {
            // 기본 호스트/포트/DB 선택
            hostName = redisProperties.host
            port = redisProperties.port
            database = redisProperties.database
            // 인증 정보가 설정된 경우 적용
            redisProperties.username?.takeIf { it.isNotBlank() }?.let { username = it }
            redisProperties.password?.takeIf { it.isNotBlank() }?.let { password = RedisPassword.of(it) }
        }

    /**
     * Lettuce 풀 설정을 생성해 커넥션 재사용을 제어
     */
    private fun buildPoolConfig(pool: RedisProperties.Pool?): GenericObjectPoolConfig<StatefulConnection<*, *>>? {
        if (pool == null) return null
        return GenericObjectPoolConfig<StatefulConnection<*, *>>().apply {
            maxTotal = pool.maxActive // 풀 전체 최대 커넥션
            maxIdle = pool.maxIdle // 최대 유휴 커넥션
            minIdle = pool.minIdle // 최소 유휴 커넥션
            setMaxWait(pool.maxWait) // 커넥션 대여 대기 시간
            testOnBorrow = true // 대여 시 유효성 검사
            testWhileIdle = true // 유휴 검사로 죽은 커넥션 제거
        }
    }

    /**
     * "host:port" 문자열을 파싱해 유효성을 검사한 뒤 튜플로 반환
     */
    private fun parseHostAndPort(node: String): Pair<String, Int> {
        val parts = node.split(":")
        if (parts.size != 2) {
            throw RedGuardException(
                errorCode = ErrorCode.INTERNAL_SERVER_ERROR,
                message = "잘못된 Redis 노드 형식입니다. host:port 형태로 입력하세요. 입력값=$node"
            )
        }
        val port = parts[1].toIntOrNull()
            ?: throw RedGuardException(
                errorCode = ErrorCode.INTERNAL_SERVER_ERROR,
                message = "Redis 노드 포트가 올바르지 않습니다. 입력값=$node"
            )
        return parts[0] to port
    }

    companion object {
        private val DEFAULT_COMMAND_TIMEOUT: Duration = Duration.ofSeconds(1)
        private val DEFAULT_SHUTDOWN_TIMEOUT: Duration = Duration.ofMillis(100)
    }
}
