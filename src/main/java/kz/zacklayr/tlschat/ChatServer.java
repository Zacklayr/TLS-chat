package kz.zacklayr.tlschat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class ChatServer implements AutoCloseable {
    private final HttpServer server;
    private final ChatRepository repository;
    private final Set<String> registrarTokens = ConcurrentHashMap.newKeySet();
    private final Map<String, ChatRepository.UserAccount> userTokens = new ConcurrentHashMap<>();

    public ChatServer(int port, Path database) throws IOException, SQLException {
        String databaseUrl = System.getenv("DATABASE_URL");
        repository = new ChatRepository(databaseUrl == null || databaseUrl.isBlank()
                ? "jdbc:sqlite:" + database.toAbsolutePath()
                : databaseUrl);
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/registration", this::handleRegistration);
        server.createContext("/api/login", this::handleLogin);
        server.createContext("/api/registrar", this::handleRegistrar);
        server.createContext("/api/chats", this::handleChats);
        server.createContext("/", this::handlePage);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    private boolean isUserAuthenticated(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ")
                && userTokens.containsKey(header.substring("Bearer ".length()).trim());
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ChatRepository.UserAccount account = repository.authenticate(
                    jsonValue(body, "username"), jsonValue(body, "password"));
            String token = UUID.randomUUID().toString();
            userTokens.put(token, account);
            writeJson(exchange, 200, "{\"token\":\"" + token + "\",\"username\":\"" +
                    escape(account.username()) + "\",\"displayName\":\"" +
                    escape(account.displayName()) + "\",\"profession\":\"" +
                    escape(account.profession()) + "\",\"workplace\":\"" +
                    escape(account.workplace()) + "\",\"region\":\"" +
                    escape(account.region()) + "\",\"permissions\":\"" +
                    escape(account.permissions()) + "\"}");
        } catch (IllegalArgumentException e) {
            writeJson(exchange, 401, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            writeJson(exchange, 500, "{\"error\":\"Database error\"}");
        } finally {
            exchange.close();
        }
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleRegistration(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ChatRepository.Registration registration = repository.registerApplication(
                    jsonValue(body, "fullName"), jsonValue(body, "profession"),
                    jsonValue(body, "workplace"), jsonValue(body, "region"),
                    jsonValue(body, "phone"));
            writeJson(exchange, 201, registrationJson(registration));
        } catch (IllegalArgumentException e) {
            writeJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            writeJson(exchange, 500, "{\"error\":\"Database error\"}");
        } finally {
            exchange.close();
        }
    }

    private void handleRegistrar(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("/api/registrar/login".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                String configuredUsername = System.getenv("REGISTRAR_USERNAME");
                String configuredPassword = System.getenv("REGISTRAR_PASSWORD");
                if (configuredUsername == null || configuredPassword == null
                        || configuredUsername.isBlank() || configuredPassword.isBlank()) {
                    writeJson(exchange, 503, "{\"error\":\"Registrar access is not configured\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean validUsername = MessageDigest.isEqual(
                        configuredUsername.getBytes(StandardCharsets.UTF_8),
                        jsonValue(body, "username").getBytes(StandardCharsets.UTF_8));
                boolean validPassword = MessageDigest.isEqual(
                        configuredPassword.getBytes(StandardCharsets.UTF_8),
                        jsonValue(body, "password").getBytes(StandardCharsets.UTF_8));
                if (!validUsername || !validPassword) {
                    writeJson(exchange, 401, "{\"error\":\"Invalid registrar credentials\"}");
                    return;
                }
                String token = UUID.randomUUID().toString();
                registrarTokens.add(token);
                writeJson(exchange, 200, "{\"token\":\"" + token + "\"}");
                return;
            }
            if (!isRegistrarAuthenticated(exchange)) {
                writeJson(exchange, 401, "{\"error\":\"Registrar authentication required\"}");
                return;
            }
            if ("/api/registrar/requests".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 200, registrationsJson(repository.pendingRegistrations()));
                return;
            }
            String prefix = "/api/registrar/requests/";
            if (path.startsWith(prefix) && "POST".equals(exchange.getRequestMethod())) {
                String requestId = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ChatRepository.Registration registration = repository.decideRegistration(
                        requestId, jsonValue(body, "status"), jsonValue(body, "fullName"),
                        jsonValue(body, "profession"), jsonValue(body, "workplace"),
                        jsonValue(body, "region"), jsonValue(body, "phone"),
                        jsonValue(body, "permissions"), jsonValue(body, "username"),
                        jsonValue(body, "password"));
                writeJson(exchange, 200, registrationJson(registration));
                return;
            }
            writeJson(exchange, 404, "{\"error\":\"Not found\"}");
        } catch (IllegalArgumentException e) {
            writeJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            writeJson(exchange, 500, "{\"error\":\"Database error\"}");
        } finally {
            exchange.close();
        }
    }

    private boolean isRegistrarAuthenticated(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ")
                && registrarTokens.contains(header.substring("Bearer ".length()).trim());
    }

    private void handleChats(HttpExchange exchange) throws IOException {
        if (!isUserAuthenticated(exchange)) {
            writeJson(exchange, 401, "{\"error\":\"Login required\"}");
            exchange.close();
            return;
        }
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
                "\",\"t\":\"" + message.sentAt().toString() + "\",\"out\":false}";
    }

    private static String registrationJson(ChatRepository.Registration registration) {
        return "{\"id\":\"" + escape(registration.id()) + "\",\"fullName\":\"" +
                escape(registration.fullName()) + "\",\"profession\":\"" +
                escape(registration.profession()) + "\",\"workplace\":\"" +
                escape(registration.workplace()) + "\",\"region\":\"" +
                escape(registration.region()) + "\",\"phone\":\"" + escape(registration.phone()) +
                "\",\"permissions\":\"" + escape(registration.permissions()) + "\",\"status\":\"" +
                registration.status() + "\",\"accountUsername\":\"" +
                escape(registration.accountUsername()) +
                "\",\"createdAt\":\"" + registration.createdAt() + "\"}";
    }

    private static String registrationsJson(List<ChatRepository.Registration> registrations) {
        return "[" + registrations.stream().map(ChatServer::registrationJson)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
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
        int port = args.length > 0
                ? Integer.parseInt(args[0])
                : Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path database = Path.of(args.length > 1 ? args[1] : "tls-chat.db");
        ChatServer chatServer = new ChatServer(port, database);
        Runtime.getRuntime().addShutdownHook(new Thread(chatServer::close));
        chatServer.start();
        System.out.println("TLS Chat server started: http://localhost:" + chatServer.port());
    }
}
