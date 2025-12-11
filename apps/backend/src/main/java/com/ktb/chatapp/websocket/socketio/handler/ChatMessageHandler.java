package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.ChatMessageRequest;
import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageContent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.CHAT_MESSAGE;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGE;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ChatMessageHandler {

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final RoomService roomService;
    private final ChatUserCacheService chatUserCacheService;
    private final FileRepository fileRepository;
    private final AiService aiService;
    private final SessionService sessionService;
    private final BannedWordChecker bannedWordChecker;
    private final RateLimitService rateLimitService;
    private final MeterRegistry meterRegistry;

    @Qualifier("chatMessageExecutor")
    private final Executor messageExecutor;

    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

    @OnEvent(CHAT_MESSAGE)
    public void handleChatMessage(SocketIOClient client, ChatMessageRequest data) {
        Timer.Sample sample = Timer.start(meterRegistry);

        String metricStatus = "success";
        String metricType = "unknown";

        try {
            if (data == null) {
                metricStatus = "error";
                metricType = "null_data";
                recordError("null_data");
                client.sendEvent(ERROR, Map.of("code", "MESSAGE_ERROR", "message", "메시지 데이터가 없습니다."));
                return;
            }

            SocketUser socketUser = (SocketUser) client.get("user");
            if (socketUser == null) {
                metricStatus = "error";
                metricType = "session_null";
                recordError("session_null");
                client.sendEvent(ERROR, Map.of("code", "SESSION_EXPIRED", "message", "세션이 만료되었습니다. 다시 로그인해주세요."));
                return;
            }

            final String roomId = data.getRoom();
            if (!StringUtils.hasText(roomId)) {
                metricStatus = "error";
                metricType = "bad_room";
                recordError("bad_room");
                client.sendEvent(ERROR, Map.of("code", "MESSAGE_ERROR", "message", "roomId가 올바르지 않습니다."));
                return;
            }

            final String messageType = normalizeMessageType(data.getMessageType());
            metricType = messageType;

            try {
                SessionValidationResult validation =
                        sessionService.validateSession(socketUser.id(), socketUser.authSessionId());
                if (!validation.isValid()) {
                    recordError("session_soft_invalid");
                    log.debug("SESSION_SOFT_INVALID allow-send userId={}, roomId={}", socketUser.id(), roomId);
                }
            } catch (Exception e) {
                recordError("session_validate_failed");
                log.debug("Session validate failed but allow-send userId={}, roomId={}", socketUser.id(), roomId, e);
            }

            RateLimitCheckResult rl = rateLimitService.checkRateLimit(socketUser.id(), 10000, Duration.ofMinutes(1));
            if (!rl.allowed()) {
                metricStatus = "error";
                metricType = "rate_limit";
                recordError("rate_limit_exceeded");
                counter("socketio.messages.rate_limit", "Socket.IO rate limit exceeded count").increment();

                client.sendEvent(ERROR, Map.of(
                        "code", "RATE_LIMIT_EXCEEDED",
                        "message", "메시지 전송 횟수 제한을 초과했습니다. 잠시 후 다시 시도해주세요.",
                        "retryAfter", rl.retryAfterSeconds()
                ));
                return;
            }

            final MessageContent content = data.getParsedContent();
            if (content == null) {
                metricStatus = "error";
                metricType = "bad_content";
                recordError("bad_content");
                client.sendEvent(ERROR, Map.of("code", "MESSAGE_ERROR", "message", "메시지 내용이 올바르지 않습니다."));
                return;
            }

            final String trimmed = content.getTrimmedContent();
            if (bannedWordChecker.containsBannedWord(trimmed)) {
                metricStatus = "error";
                metricType = "banned_word";
                recordError("banned_word");
                client.sendEvent(ERROR, Map.of("code", "MESSAGE_REJECTED", "message", "금칙어가 포함된 메시지는 전송할 수 없습니다."));
                return;
            }

            final SocketUser finalSocketUser = socketUser;
            messageExecutor.execute(() -> processMessageAsync(data, finalSocketUser, roomId, messageType, content));

        } catch (Exception e) {
            metricStatus = "error";
            metricType = "exception";
            recordError("exception");
            log.error("Message handling error", e);
            client.sendEvent(ERROR, Map.of("code", "MESSAGE_ERROR", "message", "메시지 전송 중 오류가 발생했습니다."));
        } finally {
            sample.stop(timer(metricStatus, metricType));
        }
    }

    private void processMessageAsync(
            ChatMessageRequest data,
            SocketUser socketUser,
            String roomId,
            String messageType,
            MessageContent messageContent
    ) {
        try {
            // ✅ sender 조회(캐시) - null-safe
            User sender = null;
            try {
                sender = chatUserCacheService.getUserById(socketUser.id());
            } catch (Exception e) {
                recordError("sender_lookup_failed");
                log.debug("Sender lookup failed (allow-send). userId={}, roomId={}", socketUser.id(), roomId, e);
            }

            if (sender == null) {
                recordError("sender_null");
                sender = fallbackSender(socketUser.id());
                log.debug("Sender cache miss -> fallback sender. userId={}, roomId={}", socketUser.id(), roomId);
            }

            Message message = switch (messageType) {
                case "file" -> handleFileMessage(roomId, socketUser.id(), messageContent, data.getFileData());
                case "text" -> handleTextMessage(roomId, socketUser.id(), messageContent);
                default -> handleTextMessage(roomId, socketUser.id(), messageContent);
            };

            if (message == null) return;

            Message saved = messageRepository.save(message);
            roomService.incrementRecentMessageCount(roomId);

            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGE, createMessageResponse(saved, sender));

            try {
                aiService.handleAIMentions(roomId, socketUser.id(), messageContent);
            } catch (Exception e) {
                recordError("ai_failed");
                log.debug("AI mention failed roomId={}, userId={}", roomId, socketUser.id(), e);
            }

            try {
                sessionService.updateLastActivity(socketUser.id());
            } catch (Exception e) {
                recordError("last_activity_failed");
                log.debug("LastActivity update failed userId={}", socketUser.id(), e);
            }

            recordMessageSuccess(messageType);

        } catch (Exception e) {
            recordError("exception_async");
            log.error("Async message handling error - roomId={}, userId={}", roomId, socketUser.id(), e);
        }
    }

    // ✅ UserResponse.from(sender)에서 NPE 방지용 fallback
    private User fallbackSender(String userId) {
        User u = new User();
        u.setId(userId);
        u.setName("Unknown");
        u.setEmail("");
        u.setProfileImage("");
        return u;
    }

    private String normalizeMessageType(String raw) {
        if (raw == null) return "text";
        return switch (raw) {
            case "text", "file" -> raw;
            default -> "text";
        };
    }

    private Message handleFileMessage(String roomId, String userId, MessageContent messageContent, Map<String, Object> fileData) {
        if (fileData == null || fileData.get("_id") == null) {
            throw new IllegalArgumentException("파일 데이터가 올바르지 않습니다.");
        }

        String fileId = (String) fileData.get("_id");
        File file = fileRepository.findById(fileId).orElse(null);

        if (file == null || !file.getUser().equals(userId)) {
            throw new IllegalStateException("파일을 찾을 수 없거나 접근 권한이 없습니다.");
        }

        Message message = new Message();
        message.setRoomId(roomId);
        message.setSenderId(userId);
        message.setType(MessageType.file);
        message.setFileId(fileId);
        message.setContent(messageContent.getTrimmedContent());
        message.setTimestamp(LocalDateTime.now());
        message.setMentions(messageContent.aiMentions());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileType", file.getMimetype());
        metadata.put("fileSize", file.getSize());
        metadata.put("originalName", file.getOriginalname());
        message.setMetadata(metadata);

        return message;
    }

    private Message handleTextMessage(String roomId, String userId, MessageContent messageContent) {
        if (messageContent.isEmpty()) return null;

        Message message = new Message();
        message.setRoomId(roomId);
        message.setSenderId(userId);
        message.setContent(messageContent.getTrimmedContent());
        message.setType(MessageType.text);
        message.setTimestamp(LocalDateTime.now());
        message.setMentions(messageContent.aiMentions());

        return message;
    }

    private MessageResponse createMessageResponse(Message message, User sender) {
        var res = new MessageResponse();
        res.setId(message.getId());
        res.setRoomId(message.getRoomId());
        res.setContent(message.getContent());
        res.setType(message.getType());
        res.setTimestamp(message.toTimestampMillis());
        res.setReactions(message.getReactions() != null ? message.getReactions() : Collections.emptyMap());

        // ✅ 최종 방어: sender가 혹시라도 null이면 fallback으로 처리
        if (sender == null) {
            sender = fallbackSender(message.getSenderId());
        }
        res.setSender(UserResponse.from(sender));

        res.setMetadata(message.getMetadata());

        if (message.getFileId() != null) {
            fileRepository.findById(message.getFileId())
                    .ifPresent(file -> res.setFile(FileResponse.from(file)));
        }

        return res;
    }

    private Timer timer(String status, String messageType) {
        String key = status + "|" + messageType;
        return timerCache.computeIfAbsent(key, k ->
                Timer.builder("socketio.messages.processing.time")
                        .description("Socket.IO message processing time")
                        .tag("status", status)
                        .tag("message_type", messageType)
                        .register(meterRegistry)
        );
    }

    private Counter counter(String name, String desc) {
        return counterCache.computeIfAbsent(name, k ->
                Counter.builder(name)
                        .description(desc)
                        .register(meterRegistry)
        );
    }

    private void recordMessageSuccess(String messageType) {
        String key = "socketio.messages.total|success|" + messageType;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("socketio.messages.total")
                        .description("Total Socket.IO messages processed")
                        .tag("status", "success")
                        .tag("message_type", messageType)
                        .register(meterRegistry)
        ).increment();
    }

    private void recordError(String errorType) {
        String key = "socketio.messages.errors|" + errorType;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("socketio.messages.errors")
                        .description("Socket.IO message processing errors")
                        .tag("error_type", errorType)
                        .register(meterRegistry)
        ).increment();
    }
}
