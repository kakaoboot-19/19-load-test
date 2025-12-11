package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis implementation of SessionStore.
 * Optimized for high-performance session management.
 * Replaces SessionMongoStore for load testing and production.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class SessionRedisStore implements SessionStore {

    private final RedisTemplate<String, Object> redisTemplate;
    
    private static final String KEY_PREFIX = "session:user:";

    private String getKey(String userId) {
        return KEY_PREFIX + userId;
    }

    @Override
    public Optional<Session> findByUserId(String userId) {
        try {
            String key = getKey(userId);
            Session session = (Session) redisTemplate.opsForValue().get(key);
            return Optional.ofNullable(session);
        } catch (Exception e) {
            log.error("Error retrieving session from Redis for userId: {}", userId, e);
            return Optional.empty();
        }
    }

    @Override
    public Session save(Session session) {
        try {
            String key = getKey(session.getUserId());
            
            // Calculate TTL based on expiresAt
            long ttlSeconds = 1800; // Default 30m
            if (session.getExpiresAt() != null) {
                long diff = session.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
                if (diff > 0) {
                    ttlSeconds = diff;
                }
            }
            
            redisTemplate.opsForValue().set(key, session, ttlSeconds, TimeUnit.SECONDS);
            return session;
        } catch (Exception e) {
            log.error("Error saving session to Redis for userId: {}", session.getUserId(), e);
            throw new RuntimeException("Redis 세션 저장 실패", e);
        }
    }

    @Override
    public void deleteAll(String userId) {
        try {
            String key = getKey(userId);
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Error deleting session from Redis for userId: {}", userId, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        try {
            // Redis에서는 key가 userId 기준이므로 먼저 조회해서 sessionId가 일치하는지 확인해야 안전함
            // (다른 기기에서 로그인해서 세션이 바뀌었을 수도 있으므로)
            Optional<Session> currentSessionOpt = findByUserId(userId);
            
            if (currentSessionOpt.isPresent()) {
                Session currentSession = currentSessionOpt.get();
                if (sessionId.equals(currentSession.getSessionId())) {
                    String key = getKey(userId);
                    redisTemplate.delete(key);
                } else {
                    log.debug("Session ID mismatch during delete. Ignored. userId={}, reqSessionId={}, storedSessionId={}",
                            userId, sessionId, currentSession.getSessionId());
                }
            }
        } catch (Exception e) {
            log.error("Error deleting specific session from Redis for userId: {}", userId, e);
        }
    }
}
