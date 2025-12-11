package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Slf4j
@Component
public class ChatRoomJoinHandler {

    @OnEvent("joinRoom")
    public void handleJoinRoom(SocketIOClient client, String roomId) {
        try {
            if (!StringUtils.hasText(roomId)) {
                client.sendEvent("joinRoomError", Map.of("message", "roomId가 올바르지 않습니다."));
                return;
            }

            // (선택) 인증이 꼭 필요하다면:
            SocketUser socketUser = (SocketUser) client.get("user");
            if (socketUser == null) {
                client.sendEvent("joinRoomError", Map.of("message", "세션이 만료되었습니다."));
                return;
            }

            client.joinRoom(roomId);

            log.info("joinRoomSuccess socket={}, userId={}, roomId={}",
                    client.getSessionId(), socketUser.id(), roomId);

            client.sendEvent("joinRoomSuccess", Map.of("roomId", roomId));
        } catch (Exception e) {
            log.error("joinRoomError socket={}, roomId={}", client.getSessionId(), roomId, e);
            client.sendEvent("joinRoomError", Map.of("message", "채팅방 입장에 실패했습니다."));
        }
    }

    @OnEvent("leaveRoom")
    public void handleLeaveRoom(SocketIOClient client, String roomId) {
        try {
            if (!StringUtils.hasText(roomId)) return;
            client.leaveRoom(roomId);
        } catch (Exception e) {
            log.debug("leaveRoom failed socket={}, roomId={}", client.getSessionId(), roomId, e);
        }
    }
}
