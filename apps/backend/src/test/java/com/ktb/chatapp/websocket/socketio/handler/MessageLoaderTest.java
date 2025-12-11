package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.service.ChatUserCacheService;
import com.ktb.chatapp.service.MessageReadStatusService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class MessageLoaderTest {

    @Test
    void loadMessages_doesNotThrow() {
        MessageRepository messageRepository = Mockito.mock(MessageRepository.class);
        ChatUserCacheService chatUserCacheService = Mockito.mock(ChatUserCacheService.class);
        MessageResponseMapper mapper = Mockito.mock(MessageResponseMapper.class);
        MessageReadStatusService readStatusService = Mockito.mock(MessageReadStatusService.class);

        MessageLoader loader =
                new MessageLoader(messageRepository, chatUserCacheService, mapper, readStatusService);

        // ✅ 세 번째 인자는 Long (timestamp) 이므로 null 또는 millis 사용
        FetchMessagesRequest req = new FetchMessagesRequest("room-1", 20, null);

        var result = loader.loadMessages(req, "user-1");

        assertThat(result).isNotNull();
    }
}
