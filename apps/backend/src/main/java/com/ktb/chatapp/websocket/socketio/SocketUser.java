package com.ktb.chatapp.websocket.socketio;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Socket User Data Object
 * Converted from record to POJO for better Redis serialization support.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocketUser {
    private String id;
    private String name;
    private String authSessionId;
    private String socketId;
}
