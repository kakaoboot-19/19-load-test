package com.ktb.chatapp.service;

import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * WebSocket 경로에서 사용하는 User 조회 전용 캐시 서비스.
 * - MongoDB → Redis 캐시 우회
 * - 동일 userId에 대해서는 TTL 동안 Mongo를 다시 조회하지 않도록 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatUserCacheService {

    private final UserRepository userRepository;

    /**
     * userId 기준으로 User 엔티티 조회 + Redis 캐싱
     *
     * cacheNames: userEntityById
     *  - RedisConfig에서 따로 등록 안 해도 기본 config(5분 TTL 등)를 사용
     */
    @Cacheable(cacheNames = "userEntityById", key = "#userId", unless = "#result == null")
    public User getUserById(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("User not found in ChatUserCacheService - userId={}", userId);
                    return new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + userId);
                });
    }
}
