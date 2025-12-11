package com.ktb.chatapp.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SocketIoExecutorConfig {

    @Bean
    public Executor messageExecutor() {
        return Executors.newFixedThreadPool(16);
    }
}
