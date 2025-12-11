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

    // 🔹 선택 의존성(옵셔널) – 클러스터 모드에서만 필요
    @Autowired(required = false)
    private RedissonClient redissonClient;

    @Value("${socketio.server.host:localhost}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    // local | redis  (ChatDataStore 타입 선택용)
    @Value("${chat.store.type:local}")
    private String chatStoreType;

    // Socket.IO 클러스터 on/off
    @Value("${socketio.cluster.enabled:false}")
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
        socketConfig.setTcpNoDelay(false);
        socketConfig.setAcceptBackLog(10);
        socketConfig.setTcpSendBufferSize(4096);
        socketConfig.setTcpReceiveBufferSize(4096);
        config.setSocketConfig(socketConfig);

        config.setOrigin("*");

        // Socket.IO settings
        config.setPingTimeout(60000);
        config.setPingInterval(25000);
        config.setUpgradeTimeout(10000);

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));

        // 🔹 클러스터 설정에 따라 StoreFactory 결정
        if (clusterEnabled) {
            if (redissonClient == null) {
                log.warn("socketio.cluster.enabled=true 이지만 RedissonClient 빈이 없음 → MemoryStoreFactory로 fallback");
                config.setStoreFactory(new MemoryStoreFactory());
            } else {
                log.info("Socket.IO StoreFactory: RedissonStoreFactory 사용 (클러스터 / 다중 인스턴스 모드)");
                config.setStoreFactory(new RedissonStoreFactory(redissonClient));
            }
        } else {
            log.info("Socket.IO StoreFactory: MemoryStoreFactory 사용 (단일 인스턴스 모드)");
            config.setStoreFactory(new MemoryStoreFactory());
        }

        log.info(
                "Socket.IO server configured on {}:{} with {} boss threads and {} worker threads (clusterEnabled={})",
                host, port, config.getBossThreads(), config.getWorkerThreads(), clusterEnabled
        );

        SocketIOServer socketIOServer = new SocketIOServer(config);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME)
                .addAuthTokenListener(authTokenListener);

        return socketIOServer;
    }

    /**
     * SpringAnnotationScanner는 BeanPostProcessor로서
     * ApplicationContext 초기화 초기에 등록되고,
     * 내부에서 사용하는 SocketIOServer는 Lazy로 지연되어
     * 다른 Bean들의 초기화 과정에 간섭하지 않게 한다.
     */
    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer socketIOServer) {
        return new SpringAnnotationScanner(socketIOServer);
    }

    /**
     * ChatDataStore 구현 선택
     * - chat.store.type=redis  → RedisChatDataStore (다중 인스턴스 간 상태 공유)
     * - 그 외 / 기본값         → LocalChatDataStore (단일 인스턴스 인메모리)
     */
    @Bean
    public ChatDataStore chatDataStore() {
        if ("redis".equalsIgnoreCase(chatStoreType)) {
            log.info("ChatDataStore: RedisChatDataStore 사용 (다중 인스턴스 간 상태 공유)");
            return new RedisChatDataStore(redisTemplate);
        } else {
            log.info("ChatDataStore: LocalChatDataStore 사용 (단일 인스턴스 인메모리)");
            return new LocalChatDataStore();
        }
    }
}
