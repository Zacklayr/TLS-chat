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
    private final WebSocketTicketStore ticketStore;
    private final Map<WebSocket, String> users = new ConcurrentHashMap<>();
    private final Map<WebSocket, Set<String>> subscriptions = new ConcurrentHashMap<>();

    public ChatWebSocketServer(int port, ChatRepository repository) {
        this(port, repository, new WebSocketTicketStore());
    }

    public ChatWebSocketServer(int port, ChatRepository repository, WebSocketTicketStore ticketStore) {
        super(new InetSocketAddress(port));
        this.repository = repository;
        this.ticketStore = ticketStore;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        try {
            String ticketUsername = ticketStore.consume(queryValue(handshake.getResourceDescriptor(), "ticket"));
            ChatRepository.UserAccount account = ticketUsername == null
                    ? userFromSession(queryValue(handshake.getResourceDescriptor(), "token"))
                    : repository.findApprovedUser(ticketUsername);
            if (account == null) {
                connection.close(1008, "Authentication required");
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

    private ChatRepository.UserAccount userFromSession(String token) throws SQLException {
        ChatRepository.Session session = repository.findSession(token);
        return session == null || !"USER".equals(session.role())
                ? null : repository.findApprovedUser(session.username());
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
                if ("helpdesk".equals(chatId) && !account.permissions().contains("support_agent")) {
                    HelpdeskBot.Reply reply = HelpdeskBot.answer(account.displayName(), text);
                    broadcast(chatId, repository.addMessage(chatId, "AI Helpdesk Bot", "",
                            reply.text() + " Категория: " + reply.category() + (reply.urgent() ? " [СРОЧНО]" : "")));
                } else if ("marsel-cat".equals(chatId)) {
                    String[] replies = {
                            "Мяу! 🐾 Марсель желает удачи и спокойного рабочего дня!",
                            "Мурр! Сделай паузу на пять минут и попей чай 🍵.",
                            "Мяу-мяу! 🐱 Уровень стресса снижен, можно продолжать работу!"
                    };
                    String reply = replies[(int) (Math.random() * replies.length)];
                    broadcast(chatId, repository.addMessage(chatId, "Марсель 🐾", "", reply));
                }
                return;
            }
            if ("presence".equals(type)) {
                String presence = field(rawMessage, "presence");
                repository.updatePresence(username, presence);
                broadcastPresence(username, presence);
                return;
            }
            if ("typing".equals(type)) {
                String chatId = field(rawMessage, "chatId");
                if (!repository.isChatMember(chatId, username)) {
                    sendError(connection, "Chat is not available");
                    return;
                }
                boolean active = "true".equalsIgnoreCase(field(rawMessage, "active"));
                ChatRepository.UserAccount account = repository.findApprovedUser(username);
                String displayName = "anonymous".equals(chatId)
                        ? "Участник"
                        : (account == null ? username : account.displayName());
                broadcastTyping(chatId, displayName, active);
                return;
            }
            if ("read".equals(type)) {
                String chatId = field(rawMessage, "chatId");
                repository.markMessagesRead(chatId, username);
                if (!"anonymous".equals(chatId)) broadcastRead(chatId, username);
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
            if (chats.contains(chatId) && canReceive(connection, chatId)) {
                connection.send(payload);
                connection.send(deliveredJson(chatId, message.id()));
            }
        });
    }

    private boolean canReceive(WebSocket connection, String chatId) {
        if (!connection.isOpen()) return false;
        String username = users.get(connection);
        if (username == null) return false;
        try {
            return repository.isChatMember(chatId, username);
        } catch (SQLException e) {
            return false;
        }
    }

    private static String deliveredJson(String chatId, long messageId) {
        return "{\"type\":\"delivered\",\"chatId\":\"" + escape(chatId)
                + "\",\"messageId\":" + messageId + "}";
    }

    public void broadcastNotification(ChatRepository.Notification notification) {
        String recipient = notification.recipient() == null ? "" : notification.recipient();
        String payload = "{\"type\":\"notification\",\"notification\":{\"id\":\""
                + escape(notification.id()) + "\",\"recipient\":\"" + escape(recipient)
                + "\",\"title\":\"" + escape(notification.title()) + "\",\"body\":\""
                + escape(notification.body()) + "\",\"severity\":\"" + escape(notification.severity())
                + "\",\"createdAt\":\"" + notification.createdAt() + "\"}}";
        users.forEach((connection, username) -> {
            if ((recipient.isBlank() || recipient.equals(username)) && connection.isOpen()) {
                connection.send(payload);
            }
        });
    }

    public boolean hasActiveUser(String username) {
        return username != null && users.containsValue(username);
    }

    private void broadcastPresence(String username, String presence) {
        String payload = "{\"type\":\"presence\",\"username\":\"" + escape(username)
                + "\",\"presence\":\"" + escape(presence) + "\"}";
        users.keySet().forEach(connection -> {
            if (connection.isOpen()) connection.send(payload);
        });
    }

    private void broadcastTyping(String chatId, String displayName, boolean active) {
        String payload = "{\"type\":\"typing\",\"chatId\":\"" + escape(chatId)
                + "\",\"name\":\"" + escape(displayName) + "\",\"active\":" + active + "}";
        subscriptions.forEach((connection, chats) -> {
            if (chats.contains(chatId) && canReceive(connection, chatId)) connection.send(payload);
        });
    }

    private void broadcastRead(String chatId, String username) {
        String payload = "{\"type\":\"read\",\"chatId\":\"" + escape(chatId)
                + "\",\"username\":\"" + escape(username) + "\"}";
        subscriptions.forEach((connection, chats) -> {
            if (chats.contains(chatId) && canReceive(connection, chatId)) connection.send(payload);
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
        if (username != null && !users.containsValue(username)) {
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
