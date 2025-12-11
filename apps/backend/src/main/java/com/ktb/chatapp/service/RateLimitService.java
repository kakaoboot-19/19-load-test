package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.RedisRateLimitStore;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RedisRateLimitStore redisRateLimitStore;

    public RateLimitCheckResult checkRateLimit(String clientId, int maxRequests, Duration window) {
        long windowSeconds = Math.max(1L, window.getSeconds());
        long nowEpochSeconds = Instant.now().getEpochSecond();

        try {
            // ✅ 멀티 인스턴스 공유 카운터를 위해 hostName prefix 제거
            // windowSeconds까지 키에 포함하면 설정 변경/혼용에도 안전
            String key = "rl:" + windowSeconds + ":" + clientId;

            RedisRateLimitStore.Result r = redisRateLimitStore.incrementAndGetTtl(key, window);

            long current = r.currentCount();
            long ttlSeconds = Math.max(1L, r.ttlSeconds());
            long resetEpochSeconds = nowEpochSeconds + ttlSeconds;

            if (current > maxRequests) {
                long retryAfterSeconds = ttlSeconds;
                return RateLimitCheckResult.rejected(
                        maxRequests,
                        windowSeconds,
                        resetEpochSeconds,
                        retryAfterSeconds
                );
            }

            int remaining = (int) Math.max(0, maxRequests - current);

            return RateLimitCheckResult.allowed(
                    maxRequests,
                    remaining,
                    windowSeconds,
                    resetEpochSeconds,
                    ttlSeconds
            );

        } catch (Exception e) {
            // Redis 장애 시 “허용”으로 풀어서 서비스 연속성을 확보(원하면 반대로 막아도 됨)
            log.error("Rate limit check failed for client: {}", clientId, e);
            long resetEpochSeconds = nowEpochSeconds + windowSeconds;
            return RateLimitCheckResult.allowed(
                    maxRequests,
                    maxRequests,
                    windowSeconds,
                    resetEpochSeconds,
                    windowSeconds
            );
        }
    }
}
