package kz.zacklayr.tlschat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;

public final class ChatServer implements AutoCloseable {
    private final HttpServer server;
    private final ChatRepository repository;

    public ChatServer(int port, Path database) throws IOException, SQLException {
        repository = new ChatRepository("jdbc:sqlite:" + database.toAbsolutePath());
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/chats", this::handleChats);
        server.createContext("/", this::handlePage);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleChats(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            if ("GET".equals(exchange.getRequestMethod()) && "/api/chats".equals(path)) {
                writeJson(exchange, 200, chatsJson(repository.findChats()));
            } else if ("GET".equals(exchange.getRequestMethod()) && parts.length == 4 && !parts[3].isBlank()) {
                writeJson(exchange, 200, messagesJson(repository.findMessages(parts[3])));
            } else if ("POST".equals(exchange.getRequestMethod()) && parts.length == 5 && "messages".equals(parts[4])) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String sender = jsonValue(body, "sender");
                String text = jsonValue(body, "text");
                Message message = repository.addMessage(parts[3], sender, text);
                writeJson(exchange, 201, messageJson(message));
            } else {
                writeJson(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (IllegalArgumentException e) {
            writeJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            writeJson(exchange, 500, "{\"error\":\"Database error\"}");
        } finally {
            exchange.close();
        }
    }

    private void handlePage(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
            writeJson(exchange, 404, "{\"error\":\"Not found\"}");
            exchange.close();
            return;
        }
        byte[] content;
        try (InputStream page = ChatServer.class.getResourceAsStream("/web/index.html")) {
            if (page == null) {
                writeJson(exchange, 500, "{\"error\":\"Web interface is unavailable\"}");
                exchange.close();
                return;
            }
            content = page.readAllBytes();
        }
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static String chatsJson(List<ChatRepository.ChatSummary> chats) {
        return "[" + chats.stream().map(chat -> "{\"id\":\"" + escape(chat.id()) + "\",\"type\":\"" +
                escape(chat.type()) + "\",\"name\":\"" + escape(chat.name()) + "\",\"color\":\"" +
                escape(chat.color()) + "\",\"members\":" + chat.members() + ",\"online\":" +
                chat.online() + ",\"unread\":0,\"pinned\":" + ("general".equals(chat.id()) || "dev".equals(chat.id())) +
                "}").collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static String messagesJson(List<Message> messages) {
        return "[" + messages.stream().map(ChatServer::messageJson).collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static String messageJson(Message message) {
        return "{\"from\":\"" + escape(message.sender()) + "\",\"text\":\"" + escape(message.text()) +
                "\",\"t\":\"" + message.sentAt().toString() + "\",\"out\":" + "alice".equals(message.sender()) + "}";
    }

    private static String jsonValue(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        int start = json.indexOf('"', json.indexOf(':', keyIndex) + 1) + 1;
        int end = start;
        while (end < json.length() && json.charAt(end) != '"') {
            if (json.charAt(end) == '\\') {
                end++;
            }
            end++;
        }
        if (start == 0 || end >= json.length()) {
            throw new IllegalArgumentException("Invalid JSON field: " + key);
        }
        return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        try {
            repository.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not close database", e);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path database = Path.of(args.length > 1 ? args[1] : "tls-chat.db");
        ChatServer chatServer = new ChatServer(port, database);
        Runtime.getRuntime().addShutdownHook(new Thread(chatServer::close));
        chatServer.start();
        System.out.println("TLS Chat server started: http://localhost:" + chatServer.port());
    }
}
