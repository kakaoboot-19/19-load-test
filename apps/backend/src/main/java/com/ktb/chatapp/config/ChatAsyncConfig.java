package com.ktb.chatapp.config;

import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class ChatAsyncConfig {

    @Bean(name = "chatMessageExecutor")
    public Executor chatMessageExecutor() {
        int poolSize = 16;
        int queueSize = 10000;

        ThreadFactory tf = r -> {
            Thread t = new Thread(r);
            t.setName("chat-msg-" + t.threadId());
            t.setDaemon(true);
            return t;
        };

        // ✅ 큐가 꽉 차면 CallerRunsPolicy로 “밀어넣는 쪽(소켓 이벤트 스레드)”이 실행하게 되어
        //   최악의 경우 이벤트루프가 다시 막힐 수 있음.
        //   그래서 E2E 통과 목적이면 DiscardOldestPolicy / AbortPolicy가 더 낫다.
        //   여기서는 "빠르게 실패 + 유실 최소화" 타협으로 DiscardOldestPolicy 추천.
        RejectedExecutionHandler reject = new ThreadPoolExecutor.DiscardOldestPolicy();

        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                tf,
                reject
        );
    }
}
