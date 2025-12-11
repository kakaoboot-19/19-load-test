package com.ktb.chatapp.service.ratelimit;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 RateLimit store
 * - INCR + (첫 요청 시) EXPIRE + TTL 조회를 Lua로 원자 처리
 */
@Component
@RequiredArgsConstructor
public class RedisRateLimitStore {

    private final StringRedisTemplate redisTemplate;

    // returns: {currentCount, ttlSeconds}
    private static final DefaultRedisScript<List> INCR_WITH_TTL_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local key = KEYS[1]
                    local window = tonumber(ARGV[1])

                    local current = redis.call('INCR', key)
                    if current == 1 then
                      redis.call('EXPIRE', key, window)
                    end

                    local ttl = redis.call('TTL', key)
                    return {current, ttl}
                    """,
                    List.class);

    public Result incrementAndGetTtl(String key, Duration window) {
        List<?> res =
                redisTemplate.execute(
                        INCR_WITH_TTL_SCRIPT,
                        List.of(key),
                        String.valueOf(Math.max(1L, window.getSeconds())));

        if (res == null || res.size() < 2) {
            return new Result(1L, Math.max(1L, window.getSeconds()));
        }

        long current = toLong(res.get(0), 1L);
        long ttl = toLong(res.get(1), Math.max(1L, window.getSeconds()));

        // TTL=-1/-2 방어
        if (ttl < 1) ttl = Math.max(1L, window.getSeconds());

        return new Result(current, ttl);
    }

    private long toLong(Object v, long fallback) {
        try {
            if (v == null) return fallback;
            if (v instanceof Number n) return n.longValue();
            return Long.parseLong(v.toString());
        } catch (Exception e) {
            return fallback;
        }
    }

    public record Result(long currentCount, long ttlSeconds) {}
}
