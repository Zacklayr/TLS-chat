package kz.zacklayr.tlschat;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Realtime transport for messages and presence. REST remains the fallback transport. */
public final class ChatWebSocketServer extends WebSocketServer implements AutoCloseable {
    private final ChatRepository repository;
    private final Map<WebSocket, String> users = new ConcurrentHashMap<>();
    private final Map<WebSocket, Set<String>> subscriptions = new ConcurrentHashMap<>();

    public ChatWebSocketServer(int port, ChatRepository repository) {
        super(new InetSocketAddress(port));
        this.repository = repository;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        try {
            String token = queryValue(handshake.getResourceDescriptor(), "token");
            ChatRepository.Session session = repository.findSession(token);
            if (session == null || !"USER".equals(session.role())) {
                connection.close(1008, "Authentication required");
                return;
            }
            ChatRepository.UserAccount account = repository.findApprovedUser(session.username());
            if (account == null) {
                connection.close(1008, "User is not approved");
                return;
            }
            users.put(connection, account.username());
            subscriptions.put(connection, ConcurrentHashMap.newKeySet());
            repository.updatePresence(account.username(), "online");
            connection.send("{\"type\":\"ready\",\"username\":\"" + escape(account.username()) + "\"}");
            broadcastPresence(account.username(), "online");
        } catch (SQLException e) {
            connection.close(1011, "Database error");
        }
    }

    @Override
    public void onMessage(WebSocket connection, String rawMessage) {
        String username = users.get(connection);
        if (username == null) {
            connection.close(1008, "Authentication required");
            return;
        }
        try {
            String type = field(rawMessage, "type");
            if ("subscribe".equals(type)) {
                String chatId = field(rawMessage, "chatId");
                if (!repository.isChatMember(chatId, username)) {
                    sendError(connection, "Chat is not available");
                    return;
                }
                subscriptions.get(connection).add(chatId);
                connection.send("{\"type\":\"subscribed\",\"chatId\":\"" + escape(chatId) + "\"}");
                return;
            }
            if ("unsubscribe".equals(type)) {
                subscriptions.get(connection).remove(field(rawMessage, "chatId"));
                return;
            }
            if ("message".equals(type)) {
                String chatId = field(rawMessage, "chatId");
                String text = field(rawMessage, "text");
                ChatRepository.UserAccount account = repository.findApprovedUser(username);
                Message message = repository.addMessage(chatId, username, account.permissions(), text);
                broadcast(chatId, message);
                return;
            }
            if ("presence".equals(type)) {
                String presence = field(rawMessage, "presence");
                repository.updatePresence(username, presence);
                broadcastPresence(username, presence);
                return;
            }
            if ("ping".equals(type)) {
                connection.send("{\"type\":\"pong\"}");
                return;
            }
            sendError(connection, "Unsupported realtime message");
        } catch (IllegalArgumentException e) {
            sendError(connection, e.getMessage());
        } catch (SQLException e) {
            sendError(connection, "Database error");
        }
    }

    public void broadcast(String chatId, Message message) {
        String payload = "{\"type\":\"message\",\"chatId\":\"" + escape(chatId)
                + "\",\"message\":" + messageJson(message) + "}";
        subscriptions.forEach((connection, chats) -> {
            if (chats.contains(chatId) && connection.isOpen()) {
                connection.send(payload);
            }
        });
    }

    private void broadcastPresence(String username, String presence) {
        String payload = "{\"type\":\"presence\",\"username\":\"" + escape(username)
                + "\",\"presence\":\"" + escape(presence) + "\"}";
        users.keySet().forEach(connection -> {
            if (connection.isOpen()) connection.send(payload);
        });
    }

    private static void sendError(WebSocket connection, String message) {
        if (connection.isOpen()) {
            connection.send("{\"type\":\"error\",\"error\":\"" + escape(message) + "\"}");
        }
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        String username = users.remove(connection);
        subscriptions.remove(connection);
        if (username != null) {
            try {
                repository.updatePresence(username, "offline");
                broadcastPresence(username, "offline");
            } catch (SQLException ignored) {
            }
        }
    }

    @Override
    public void onError(WebSocket connection, Exception exception) {
        if (connection != null) connection.close(1011, "Realtime transport error");
    }

    @Override
    public void onStart() {
    }

    @Override
    public void close() {
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String messageJson(Message message) {
        return "{\"id\":" + message.id() + ",\"from\":\"" + escape(message.sender())
                + "\",\"name\":\"" + escape(message.senderName()) + "\",\"role\":\""
                + escape(message.senderRole()) + "\",\"text\":\"" + escape(message.text())
                + "\",\"t\":\"" + message.sentAt() + "\"}";
    }

    private static String field(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) throw new IllegalArgumentException("Missing field: " + key);
        int colon = json.indexOf(':', keyIndex + marker.length());
        int start = json.indexOf('"', colon + 1) + 1;
        if (colon < 0 || start == 0) throw new IllegalArgumentException("Invalid field: " + key);
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char current = json.charAt(i);
            if (escaped) {
                value.append(switch (current) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> current;
                });
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return value.toString();
            } else {
                value.append(current);
            }
        }
        throw new IllegalArgumentException("Invalid field: " + key);
    }

    private static String queryValue(String resource, String key) {
        int question = resource.indexOf('?');
        if (question < 0) return "";
        for (String part : resource.substring(question + 1).split("&")) {
            int equals = part.indexOf('=');
            String rawKey = equals < 0 ? part : part.substring(0, equals);
            if (key.equals(URLDecoder.decode(rawKey, StandardCharsets.UTF_8))) {
                return equals < 0 ? "" : URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
