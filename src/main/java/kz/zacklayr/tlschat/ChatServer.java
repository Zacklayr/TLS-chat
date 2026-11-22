package kz.zacklayr.tlschat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import javax.net.ssl.SSLContext;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class ChatServer implements AutoCloseable {
    private final HttpServer server;
    private final ChatRepository repository;
    public ChatServer(int port, Path database) throws IOException, SQLException {
        String databaseUrl = System.getenv("DATABASE_URL");
        repository = new ChatRepository(databaseUrl == null || databaseUrl.isBlank()
                ? "jdbc:sqlite:" + database.toAbsolutePath()
                : databaseUrl);
        server = createServer(port);
        server.createContext("/api/registration", this::handleRegistration);
        server.createContext("/api/login", this::handleLogin);
        server.createContext("/api/registrar", this::handleRegistrar);
        server.createContext("/api/admin", this::handleAdmin);
        server.createContext("/api/chats", this::handleChats);
        server.createContext("/api/users", this::handleUsers);
        server.createContext("/photos", this::handlePhotos);
        server.createContext("/", this::handlePage);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    private HttpServer createServer(int port) throws IOException {
        String keyStore = System.getenv("TLS_KEYSTORE");
        String keyStorePassword = System.getenv("TLS_PASSWORD");
        if (keyStore == null || keyStore.isBlank()) {
            return HttpServer.create(new InetSocketAddress(port), 0);
        }
        if (keyStorePassword == null || keyStorePassword.isBlank()) {
            throw new IllegalStateException("TLS_PASSWORD is required when TLS_KEYSTORE is configured");
        }
        try {
            SSLContext sslContext = SslContextFactory.serverContext(
                    Path.of(keyStore), keyStorePassword.toCharArray());
            HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters parameters) {
                    parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                    parameters.setNeedClientAuth(false);
                }
            });
            return httpsServer;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not initialize TLS server", e);
        }
    }

    private ChatRepository.UserAccount authenticatedUser(HttpExchange exchange) throws SQLException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        ChatRepository.Session session = repository.findSession(header.substring("Bearer ".length()).trim());
        return session == null || !"USER".equals(session.role())
                ? null : repository.findApprovedUser(session.username());
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
            String token = repository.createSession("USER", account.username());
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
                        configuredUsername.trim().getBytes(StandardCharsets.UTF_8),
                        jsonValue(body, "username").trim().getBytes(StandardCharsets.UTF_8));
                boolean validPassword = MessageDigest.isEqual(
                        configuredPassword.trim().getBytes(StandardCharsets.UTF_8),
                        jsonValue(body, "password").getBytes(StandardCharsets.UTF_8));
                if (!validUsername || !validPassword) {
                    writeJson(exchange, 401, "{\"error\":\"Invalid registrar credentials\"}");
                    return;
                }
                String token = repository.createSession("REGISTRAR", "");
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
                if ("APPROVED".equals(registration.status())) {
                    repository.assignChatsForUser(registration.accountUsername());
                }
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

    private void handleAdmin(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("/api/admin/login".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                String configuredUsername = System.getenv("ADMIN_USERNAME");
                String configuredPassword = System.getenv("ADMIN_PASSWORD");
                if (configuredUsername == null || configuredPassword == null
                        || configuredUsername.isBlank() || configuredPassword.isBlank()) {
                    writeJson(exchange, 503, "{\"error\":\"Admin access is not configured\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean validUsername = MessageDigest.isEqual(
                        configuredUsername.getBytes(StandardCharsets.UTF_8),
                        jsonValue(body, "username").getBytes(StandardCharsets.UTF_8));
                boolean validPassword = MessageDigest.isEqual(
                        configuredPassword.trim().getBytes(StandardCharsets.UTF_8),
                        jsonValue(body, "password").getBytes(StandardCharsets.UTF_8));
                if (!validUsername || !validPassword) {
                    writeJson(exchange, 401, "{\"error\":\"Invalid admin credentials\"}");
                    return;
                }
                    String token = repository.createSession("ADMIN", "");
                writeJson(exchange, 200, "{\"token\":\"" + token + "\"}");
                return;
            }
            if (!isAdminAuthenticated(exchange)) {
                writeJson(exchange, 401, "{\"error\":\"Admin authentication required\"}");
                return;
            }
            if ("/api/admin/chats".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 200, adminChatsJson(repository.adminChats()));
                return;
            }
            if ("/api/admin/chats".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String regionRule = "";
                try {
                    regionRule = jsonValue(body, "regionRule");
                } catch (IllegalArgumentException ignored) {
                }
                ChatRepository.ChatDefinition chat = repository.createChat(
                        jsonValue(body, "id"), jsonValue(body, "type"), jsonValue(body, "name"),
                        jsonValue(body, "color"), jsonValue(body, "professionRule"),
                        jsonValue(body, "workplaceRule"), regionRule, jsonValue(body, "writePermissions"));
                writeJson(exchange, 201, chatJson(chat));
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

    private boolean isAdminAuthenticated(HttpExchange exchange) throws SQLException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        ChatRepository.Session session = repository.findSession(header.substring("Bearer ".length()).trim());
        return session != null && "ADMIN".equals(session.role());
    }

    private boolean isRegistrarAuthenticated(HttpExchange exchange) throws SQLException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        ChatRepository.Session session = repository.findSession(header.substring("Bearer ".length()).trim());
        return session != null && "REGISTRAR".equals(session.role());
    }

    private void handleChats(HttpExchange exchange) throws IOException {
        try {
            ChatRepository.UserAccount account = authenticatedUser(exchange);
            if (account == null) {
                writeJson(exchange, 401, "{\"error\":\"Login required\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            if ("POST".equals(exchange.getRequestMethod()) && "/api/chats/direct".equals(path)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ChatRepository.ChatDefinition chat = repository.ensureDirectChat(
                        account, jsonValue(body, "username"));
                writeJson(exchange, 201, chatJson(chat));
            } else if ("GET".equals(exchange.getRequestMethod()) && "/api/chats".equals(path)) {
                writeJson(exchange, 200, chatsJson(repository.findChatsForUser(account)));
            } else if ("GET".equals(exchange.getRequestMethod()) && parts.length == 4 && !parts[3].isBlank()) {
                writeJson(exchange, 200, messagesJson(repository.findMessages(parts[3], account.username())));
            } else if ("POST".equals(exchange.getRequestMethod()) && parts.length == 5 && "messages".equals(parts[4])) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String text = jsonValue(body, "text");
                String chatId = parts[3];
                Message message = repository.addMessage(chatId, account.username(), account.permissions(), text);
                writeJson(exchange, 201, messageJson(message));
                if ("marsel-cat".equals(chatId)) {
                    Path marselDir = Path.of("photos", "marsel");
                    List<Path> photoList = List.of();
                    if (Files.exists(marselDir)) {
                        try (var stream = Files.list(marselDir)) {
                            photoList = stream.filter(p -> {
                                String s = p.getFileName().toString().toLowerCase(Locale.ROOT);
                                return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png")
                                        || s.endsWith(".webp") || s.endsWith(".gif");
                            }).toList();
                        } catch (IOException ignored) {
                        }
                    }
                    String[] meows = {
                        "Мяу! 🐾 Муррр-муррр... Марсель сладко потёрся о ваши руки и пожелал удачи!",
                        "Мурр! Сделай паузу на 5 минут и попей чай 🍵. Спокойствие сотрудников — закон Avengers Games!",
                        "Мяу-мяу! 🐱 Марсель лично проверил ваш код и одобрил ✨ Уровень стресса снижен до 0%!",
                        "Муррр... Марсель сладко спит на клавиатуре и передаёт вам тепло и уют 🐾"
                    };
                    String meow = meows[(int) (Math.random() * meows.length)];
                    if (!photoList.isEmpty()) {
                        Path chosen = photoList.get((int) (Math.random() * photoList.size()));
                        String photoUrl = "/photos/marsel/" + chosen.getFileName().toString();
                        meow += "\n[photo:" + photoUrl + "]";
                    }
                    repository.addMessage("marsel-cat", "Марсель 🐾", "", meow);
                } else if ("helpdesk".equals(chatId) && !account.permissions().contains("support_agent")) {
                    String autoReply = "🤖 Бот техподдержки: Здравствуйте, " + account.displayName() +
                            "! Ваше обращение принято: «" + text + "». Дежурный специалист техподдержки уже видит ваш запрос и ответит вам здесь!";
                    repository.addMessage("helpdesk", "AI Helpdesk Bot", "", autoReply);
                }
                return;
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

    private void handleUsers(HttpExchange exchange) throws IOException {
        try {
            ChatRepository.UserAccount account = authenticatedUser(exchange);
            if (account == null || !hasPermission(account, "ceo")) {
                writeJson(exchange, 403, "{\"error\":\"CEO permission required\"}");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            writeJson(exchange, 200, usersJson(repository.approvedUsers()));
        } catch (IllegalArgumentException e) {
            writeJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            writeJson(exchange, 500, "{\"error\":\"Database error\"}");
        } finally {
            exchange.close();
        }
    }

    private static boolean hasPermission(ChatRepository.UserAccount user, String permission) {
        return user != null && List.of(user.permissions().split(",")).contains(permission);
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

    private void handlePhotos(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String rawPath = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
            Path filePath = Path.of(rawPath.replaceFirst("^/", "")).normalize();
            if (!filePath.startsWith("photos") || !Files.exists(filePath) || Files.isDirectory(filePath)) {
                writeJson(exchange, 404, "{\"error\":\"Photo not found\"}");
                return;
            }
            String fileName = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
            String mime = fileName.endsWith(".png") ? "image/png"
                    : fileName.endsWith(".gif") ? "image/gif"
                    : fileName.endsWith(".webp") ? "image/webp" : "image/jpeg";
            byte[] bytes = Files.readAllBytes(filePath);
            exchange.getResponseHeaders().set("Content-Type", mime);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private static String chatsJson(List<ChatRepository.ChatSummary> chats) {
        return "[" + chats.stream().map(chat -> "{\"id\":\"" + escape(chat.id()) + "\",\"type\":\"" +
                escape(chat.type()) + "\",\"name\":\"" + escape(chat.name()) + "\",\"color\":\"" +
                escape(chat.color()) + "\",\"members\":" + chat.members() + ",\"online\":" +
                chat.online() + ",\"unread\":0,\"pinned\":" + ("general".equals(chat.id()) || "leaders".equals(chat.id()) || "gamedev-all".equals(chat.id())) +
                ",\"canWrite\":" + chat.canWrite() +
                "}").collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static String messagesJson(List<Message> messages) {
        return "[" + messages.stream().map(ChatServer::messageJson).collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static String messageJson(Message message) {
        String seenByArray = message.seenBy() == null ? "[]" :
                "[" + message.seenBy().stream().map(s -> "\"" + escape(s) + "\"").collect(Collectors.joining(",")) + "]";
        return "{\"id\":" + message.id() +
                ",\"from\":\"" + escape(message.sender()) +
                "\",\"name\":\"" + escape(message.senderName()) +
                "\",\"role\":\"" + escape(message.senderRole()) +
                "\",\"text\":\"" + escape(message.text()) +
                "\",\"t\":\"" + message.sentAt().toString() +
                "\",\"out\":false" +
                ",\"seenBy\":" + seenByArray + "}";
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

    private static String chatJson(ChatRepository.ChatDefinition chat) {
        return "{\"id\":\"" + escape(chat.id()) + "\",\"type\":\"" + escape(chat.type()) +
                "\",\"name\":\"" + escape(chat.name()) + "\",\"color\":\"" + escape(chat.color()) +
                "\",\"professionRule\":\"" + escape(chat.professionRule()) + "\",\"workplaceRule\":\"" +
                escape(chat.workplaceRule()) + "\",\"regionRule\":\"" + escape(chat.regionRule()) +
                "\",\"writePermissions\":\"" + escape(chat.writePermissions()) + "\"}";
    }

    private static String adminChatsJson(List<ChatRepository.ChatDefinition> chats) {
        return "[" + chats.stream().map(ChatServer::chatJson)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static String usersJson(List<ChatRepository.UserAccount> users) {
        return "[" + users.stream().map(user -> "{\"username\":\"" + escape(user.username()) +
                "\",\"displayName\":\"" + escape(user.displayName()) + "\",\"profession\":\"" +
                escape(user.profession()) + "\",\"workplace\":\"" + escape(user.workplace()) +
                "\",\"region\":\"" + escape(user.region()) + "\"}")
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
