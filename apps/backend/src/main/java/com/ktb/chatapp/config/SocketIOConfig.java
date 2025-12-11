package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.LocalChatDataStore;
import com.ktb.chatapp.websocket.socketio.RedisChatDataStore;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;
import org.springframework.data.redis.core.RedisTemplate;

import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {

    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private RedissonClient redissonClient;

    @Value("${socketio.server.host:localhost}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    @Value("${chat.store.type:local}")
    private String chatStoreType;

    // ✅ e2e 통과 목적: 기본값 true로 권장 (properties에서 명시해도 됨)
    @Value("${socketio.cluster.enabled:true}")
    private boolean clusterEnabled;

    public SocketIOConfig(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIOServer(AuthTokenListener authTokenListener) {
        com.corundumstudio.socketio.Configuration config =
                new com.corundumstudio.socketio.Configuration();

        config.setHostname(host);
        config.setPort(port);

        SocketConfig socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setTcpNoDelay(true); // ✅ 채팅은 지연 줄이기
        socketConfig.setAcceptBackLog(1024); // ✅ 동시 접속 여유
        socketConfig.setTcpSendBufferSize(1 << 20);
        socketConfig.setTcpReceiveBufferSize(1 << 20);
        config.setSocketConfig(socketConfig);

        config.setOrigin("*");

        config.setPingTimeout(60000);
        config.setPingInterval(25000);
        config.setUpgradeTimeout(10000);

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));

        // ✅ 여기 핵심: 멀티 인스턴스에서 room/broadcast 공유
        if (clusterEnabled) {
            if (redissonClient != null) {
                log.info("Socket.IO StoreFactory: RedissonStoreFactory (clusterEnabled=true)");
                config.setStoreFactory(new RedissonStoreFactory(redissonClient));
            } else {
                log.warn("clusterEnabled=true 이지만 RedissonClient 빈이 없음 → MemoryStoreFactory fallback (다자간 e2e 깨질 수 있음)");
                config.setStoreFactory(new MemoryStoreFactory());
            }
        } else {
            // e2e 다자간이면 사실상 여기 오면 안 됨
            log.warn("Socket.IO StoreFactory: MemoryStoreFactory (clusterEnabled=false) - 멀티 인스턴스에서 다자간 e2e 실패 가능");
            config.setStoreFactory(new MemoryStoreFactory());
        }

        log.info("Socket.IO server configured on {}:{} (clusterEnabled={})", host, port, clusterEnabled);

        SocketIOServer socketIOServer = new SocketIOServer(config);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME)
                .addAuthTokenListener(authTokenListener);

        return socketIOServer;
    }

    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer socketIOServer) {
        return new SpringAnnotationScanner(socketIOServer);
    }

    @Bean
    public ChatDataStore chatDataStore() {
        if ("redis".equalsIgnoreCase(chatStoreType)) {
            log.info("ChatDataStore: RedisChatDataStore");
            return new RedisChatDataStore(redisTemplate);
        }
        log.info("ChatDataStore: LocalChatDataStore");
        return new LocalChatDataStore();
    }
}
