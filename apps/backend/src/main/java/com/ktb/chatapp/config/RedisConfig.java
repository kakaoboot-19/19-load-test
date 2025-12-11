package com.ktb.chatapp.config;

import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.ReadFrom;
import lombok.extern.slf4j.Slf4j;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import org.springframework.data.redis.core.RedisTemplate;

import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Slf4j
@Configuration
@EnableCaching
public class RedisConfig {

    // Sentinel 모드 설정
    @Value("${REDIS_SENTINEL_MASTER:}")
    private String sentinelMaster;

    @Value("${REDIS_SENTINEL_NODES:}")
    private String sentinelNodes;

    // Standalone 모드 설정 (로컬 개발용)
    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    @Value("${REDIS_PASSWORD:}")
    private String redisPassword;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // Sentinel 모드 체크
        if (sentinelMaster != null && !sentinelMaster.isEmpty()
                && sentinelNodes != null && !sentinelNodes.isEmpty()) {

            log.info("Redis Sentinel 모드로 연결합니다");
            log.info("Master: {}, Nodes: {}", sentinelMaster, sentinelNodes);

            return createSentinelConnectionFactory();
        } else {
            log.info("Redis Standalone 모드로 연결합니다");
            log.info("Host: {}, Port: {}", redisHost, redisPort);

            return createStandaloneConnectionFactory();
        }
    }

    /**
     * Sentinel 모드 연결
     */
    private RedisConnectionFactory createSentinelConnectionFactory() {
        RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
                .master(sentinelMaster);

        // Sentinel 노드 추가
        Arrays.stream(sentinelNodes.split(","))
                .map(node -> node.split(":"))
                .forEach(parts -> sentinelConfig.sentinel(parts[0].trim(), Integer.parseInt(parts[1].trim())));

        if (redisPassword != null && !redisPassword.isEmpty()) {
            sentinelConfig.setPassword(redisPassword);
        }

        // Replica 우선 읽기 (읽기 부하 분산)
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .readFrom(ReadFrom.REPLICA_PREFERRED)
                .build();

        return new LettuceConnectionFactory(sentinelConfig, clientConfig);
    }

    /**
     * Standalone 모드 연결 (로컬 개발용)
     */
    private RedisConnectionFactory createStandaloneConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisHost);
        config.setPort(redisPort);

        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }

        return new LettuceConnectionFactory(config);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        var keySerializer = new StringRedisSerializer();
        var valueSerializer = new GenericJackson2JsonRedisSerializer(objectMapper());

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer(objectMapper())
                        )
                )
                .disableCachingNullValues()
                .entryTtl(Duration.ofMinutes(5));

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("userById", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("roomById", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("recentMessageCount", defaultConfig.entryTtl(Duration.ofSeconds(90)));

        cacheConfigs.put("fileByName", defaultConfig.entryTtl(Duration.ofHours(1)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }

    /**
     * Socket.IO 클러스터링용 RedissonClient
     * - Sentinel / Standalone 모두 지원
     * - socketio.cluster.enabled=true 일 때만 활성화
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "socketio.cluster.enabled", havingValue = "true")
    public RedissonClient redissonClient() {
        Config config = new Config();

        boolean isSentinel =
                sentinelMaster != null && !sentinelMaster.isBlank()
                        && sentinelNodes != null && !sentinelNodes.isBlank();

        // Sentinel 모드
        if (isSentinel) {

            log.info("Redisson Sentinel 모드로 초기화합니다. master={}, nodes={}",
                    sentinelMaster, sentinelNodes);

            var sentinelConfig = config.useSentinelServers()
                    .setMasterName(sentinelMaster);

            Arrays.stream(sentinelNodes.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(node -> sentinelConfig.addSentinelAddress("redis://" + node));

            // 비밀번호가 있을 때만 AUTH
            if (redisPassword != null && !redisPassword.trim().isEmpty()) {
                sentinelConfig.setPassword(redisPassword.trim());
            }

        } else {
            // Standalone 모드
            String address = "redis://" + redisHost + ":" + redisPort;
            log.info("Redisson Standalone 모드로 초기화합니다. address={}", address);

            var singleConfig = config.useSingleServer()
                    .setAddress(address);

            // 비밀번호가 있을 때만 AUTH
            if (redisPassword != null && !redisPassword.trim().isEmpty()) {
                singleConfig.setPassword(redisPassword.trim());
            }
        }

        return Redisson.create(config);
    }

    private ObjectMapper objectMapper() {
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        return om;
    }
}
