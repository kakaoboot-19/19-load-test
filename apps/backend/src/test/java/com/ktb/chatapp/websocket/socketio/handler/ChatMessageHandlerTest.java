package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.ChatMessageRequest;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.service.ChatUserCacheService;
import com.ktb.chatapp.service.RateLimitCheckResult;
import com.ktb.chatapp.service.RateLimitService;
import com.ktb.chatapp.service.RoomService;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.util.BannedWordChecker;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.ai.AiService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatMessageHandlerTest {

    private final Executor directExecutor = Runnable::run;

    @Mock private SocketIOServer socketIOServer;
    @Mock private MessageRepository messageRepository;
    @Mock private RoomService roomService;
    @Mock private ChatUserCacheService chatUserCacheService;
    @Mock private FileRepository fileRepository;
    @Mock private AiService aiService;
    @Mock private SessionService sessionService;
    @Mock private BannedWordChecker bannedWordChecker;
    @Mock private RateLimitService rateLimitService;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ChatMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ChatMessageHandler(
                socketIOServer,
                messageRepository,
                roomService,
                chatUserCacheService,
                fileRepository,
                aiService,
                sessionService,
                bannedWordChecker,
                rateLimitService,
                meterRegistry,
                directExecutor
        );
    }

    @Test
    void handleChatMessage_blocksMessagesContainingBannedWords() {
        // given
        SocketIOClient client = mock(SocketIOClient.class);
        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
        when(client.get("user")).thenReturn(socketUser);

        when(sessionService.validateSession(socketUser.id(), socketUser.authSessionId()))
                .thenReturn(SessionValidationResult.valid(null));

        when(rateLimitService.checkRateLimit(eq(socketUser.id()), anyInt(), any()))
                .thenReturn(RateLimitCheckResult.allowed(
                        10000, 9999, 60, System.currentTimeMillis() / 1000 + 60, 60
                ));

        User user = new User();
        user.setId("user-1");
        when(chatUserCacheService.getUserById("user-1")).thenReturn(user);

        ChatMessageRequest request = ChatMessageRequest.builder()
                .room("room-1")
                .type("text")
                .content("bad word")
                .build();

        when(bannedWordChecker.containsBannedWord("bad word")).thenReturn(true);

        // when
        handler.handleChatMessage(client, request);

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> payloadCaptor = ArgumentCaptor.forClass(Map.class);

        verify(client).sendEvent(eq(ERROR), payloadCaptor.capture());
        Map<String, String> payload = payloadCaptor.getValue();
        assertEquals("MESSAGE_REJECTED", payload.get("code"));

        verifyNoInteractions(messageRepository);
        verify(socketIOServer, never()).getRoomOperations(any());
    }
}
