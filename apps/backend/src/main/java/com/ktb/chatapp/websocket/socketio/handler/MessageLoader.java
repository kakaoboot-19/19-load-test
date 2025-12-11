package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.service.ChatUserCacheService;
import com.ktb.chatapp.service.MessageReadStatusService;
import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import static java.util.Collections.emptyList;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private final MessageRepository messageRepository;
    private final ChatUserCacheService chatUserCacheService; // ✅ UserRepository 대신
    private final MessageResponseMapper messageResponseMapper;
    private final MessageReadStatusService messageReadStatusService;

    private static final int BATCH_SIZE = 30;

    public FetchMessagesResponse loadMessages(FetchMessagesRequest data, String userId) {
        try {
            return loadMessagesInternal(
                    data.roomId(),
                    data.limit(BATCH_SIZE),
                    data.before(LocalDateTime.now()),
                    userId
            );
        } catch (Exception e) {
            log.error("Error loading initial messages for room {}", data.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before,
            String userId) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());

        Page<Message> messagePage = messageRepository
                .findByRoomIdAndIsDeletedAndTimestampBefore(roomId, false, before, pageable);

        List<Message> messages = messagePage.getContent();

        // DESC 조회 → ASC로 뒤집기
        List<Message> sortedMessages = messages.reversed();

        var messageIds = sortedMessages.stream().map(Message::getId).toList();
        messageReadStatusService.updateReadStatus(messageIds, userId);

        // ✅ 유저 조회를 캐시 서비스로 우회
        List<MessageResponse> messageResponses = sortedMessages.stream()
                .map(message -> {
                    var user = findUserById(message.getSenderId());
                    return messageResponseMapper.mapToMessageResponse(message, user);
                })
                .collect(Collectors.toList());

        boolean hasMore = messagePage.hasNext();

        log.debug("Messages loaded - roomId: {}, limit: {}, count: {}, hasMore: {}",
                roomId, limit, messageResponses.size(), hasMore);

        return FetchMessagesResponse.builder()
                .messages(messageResponses)
                .hasMore(hasMore)
                .build();
    }

    @Nullable
    private User findUserById(String id) {
        if (id == null) {
            return null;
        }
        try {
            return chatUserCacheService.getUserById(id); // ✅ Mongo → Redis 캐시
        } catch (Exception e) {
            log.warn("Failed to load user for message sender - userId={}", id);
            return null;
        }
    }
}