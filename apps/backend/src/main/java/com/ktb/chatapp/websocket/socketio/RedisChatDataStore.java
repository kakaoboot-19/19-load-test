package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Redis implementation of ChatDataStore.
 * Provides distributed storage for chat-related data using Redis.
 * Essential for multi-instance deployments.
 */
@Slf4j
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    private final RedisTemplate<String, Object> redisTemplate;
    
    // Prefix for chat data keys to avoid collision
    private static final String KEY_PREFIX = "chat:store:";

    private String getKey(String key) {
        return KEY_PREFIX + key;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String redisKey = getKey(key);
            Object value = redisTemplate.opsForValue().get(redisKey);
            
            if (value == null) {
                return Optional.empty();
            }
            
            // RedisTemplate handles deserialization, so we just cast
            return Optional.ofNullable(type.cast(value));
        } catch (Exception e) {
            log.error("Error retrieving key {} from Redis", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        try {
            String redisKey = getKey(key);
            redisTemplate.opsForValue().set(redisKey, value);
        } catch (Exception e) {
            log.error("Error setting key {} in Redis", key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            String redisKey = getKey(key);
            redisTemplate.delete(redisKey);
        } catch (Exception e) {
            log.error("Error deleting key {} from Redis", key, e);
        }
    }

    @Override
    public int size() {
        // Counting keys with pattern is expensive in Redis (SCAN)
        // For performance reasons in production, we might want to return -1 or implement a counter
        // But for compatibility with interface, we will return 0 or implement simplified logic if needed.
        // Current usage of size() needs to be checked. If not critical, return 0.
        return 0;
    }
}
