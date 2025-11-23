package com.redguard.infrastructure.redis.script

import org.springframework.data.redis.core.script.DefaultRedisScript

/**
 * 초/분/일 Rate Limit 슬라이딩 윈도우와 일/월 Quota 증가를 한 번에 처리하는 Lua 스크립트
 */
object RateLimitLuaScript {
    val SOURCE: String =
        """
        -- ARGV 및 KEYS 인덱스 매핑
        -- KEYS: [1]secCurr, [2]secPrev, [3]minCurr, [4]minPrev, [5]dayCurr, [6]dayPrev, [7]quotaDay, [8]quotaMonth
        -- ARGV: [1]limitSec, [2]limitMin, [3]limitDay, [4]quotaDay, [5]quotaMonth,
        --       [6]ttlSec, [7]ttlMin, [8]ttlDay, [9]ttlQuotaDay, [10]ttlQuotaMonth,
        --       [11]weightSec, [12]weightMin, [13]weightDay, [14]increment

        -- 입력 파라미터 로드
        local LIMIT_SEC = tonumber(ARGV[1])
        local LIMIT_MIN = tonumber(ARGV[2])
        local LIMIT_DAY = tonumber(ARGV[3])
        local QUOTA_DAY = tonumber(ARGV[4])
        local QUOTA_MONTH = tonumber(ARGV[5])

        local TTL_SEC = tonumber(ARGV[6])
        local TTL_MIN = tonumber(ARGV[7])
        local TTL_DAY = tonumber(ARGV[8])
        local TTL_QUOTA_DAY = tonumber(ARGV[9])
        local TTL_QUOTA_MONTH = tonumber(ARGV[10])

        local WEIGHT_SEC = tonumber(ARGV[11]) or 0
        local WEIGHT_MIN = tonumber(ARGV[12]) or 0
        local WEIGHT_DAY = tonumber(ARGV[13]) or 0

        local INCREMENT = tonumber(ARGV[14]) or 1

        -- 슬라이딩 윈도우 Rate Limit 처리
        local function handle_rate(idxCurrent, idxPrevious, limit, ttl, weight)
          if not limit or limit <= 0 then
            return {1, 0, 0, 0}
          end

          local current = redis.call('INCRBY', KEYS[idxCurrent], INCREMENT)
          if current == INCREMENT and ttl and ttl > 0 then
            redis.call('EXPIRE', KEYS[idxCurrent], ttl)
          end

          local previous = tonumber(redis.call('GET', KEYS[idxPrevious]) or "0")
          local effective = current + (previous * weight)
          local allowed = (effective <= limit) and 1 or 0
          return {allowed, effective, current, previous}
        end

        -- Quota 누적 처리
        local function handle_quota(idxKey, limit, ttl)
          if not limit or limit <= 0 then
            return {1, 0}
          end

          local total = redis.call('INCRBY', KEYS[idxKey], INCREMENT)
          if total == INCREMENT and ttl and ttl > 0 then
            redis.call('EXPIRE', KEYS[idxKey], ttl)
          end

          local allowed = (total <= limit) and 1 or 0
          return {allowed, total}
        end

        local sec = handle_rate(1, 2, LIMIT_SEC, TTL_SEC, WEIGHT_SEC)
        local min = handle_rate(3, 4, LIMIT_MIN, TTL_MIN, WEIGHT_MIN)
        local day = handle_rate(5, 6, LIMIT_DAY, TTL_DAY, WEIGHT_DAY)

        local quotaDay = handle_quota(7, QUOTA_DAY, TTL_QUOTA_DAY)
        local quotaMonth = handle_quota(8, QUOTA_MONTH, TTL_QUOTA_MONTH)

        -- 반환값: 각 윈도우/쿼터별 허용 여부 및 현재 카운트
        -- [secAllowed, secEffective, secCurr, secPrev, min..., day..., quotaDayAllowed, quotaDayTotal, quotaMonthAllowed, quotaMonthTotal]
        return {
          sec[1], sec[2], sec[3], sec[4],
          min[1], min[2], min[3], min[4],
          day[1], day[2], day[3], day[4],
          quotaDay[1], quotaDay[2],
          quotaMonth[1], quotaMonth[2]
        }
        """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    fun redisScript(): DefaultRedisScript<List<Long>> {
        return DefaultRedisScript(SOURCE, List::class.java as Class<List<Long>>)
    }
}
