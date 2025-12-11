package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;

import org.springframework.data.redis.core.RedisTemplate;

/**
 * Redis 기반 ChatDataStore 구현체
 * - 다중 서버 환경에서 Socket 상태 공유용
 */
public class RedisChatDataStore implements ChatDataStore {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisChatDataStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of((T) value);
        } catch (ClassCastException e) {
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public int size() {
        // 접속자 수는 prefix 기준으로 대략 계산
        var keys = redisTemplate.keys("conn_users:userid:*");
        return keys != null ? keys.size() : 0;
    }
}
