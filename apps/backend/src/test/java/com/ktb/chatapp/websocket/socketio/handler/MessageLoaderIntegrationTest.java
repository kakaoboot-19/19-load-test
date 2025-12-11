package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.service.ChatUserCacheService;
import com.ktb.chatapp.service.MessageReadStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MessageLoaderIntegrationTest {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ChatUserCacheService chatUserCacheService;

    @Autowired
    private MessageResponseMapper messageResponseMapper;

    @Autowired
    private MessageReadStatusService messageReadStatusService;

    @Test
    void contextLoads_andBeansAreWired() {
        assertThat(messageRepository).isNotNull();
        assertThat(chatUserCacheService).isNotNull();
        assertThat(messageResponseMapper).isNotNull();
        assertThat(messageReadStatusService).isNotNull();

        // 단순 생성 스모크 테스트
        MessageLoader loader =
                new MessageLoader(messageRepository, chatUserCacheService,
                        messageResponseMapper, messageReadStatusService);
        assertThat(loader).isNotNull();
    }
}
