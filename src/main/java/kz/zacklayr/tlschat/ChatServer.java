package kz.zacklayr.tlschat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsExchange;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class ChatServer implements AutoCloseable {
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;
    private final HttpServer server;
    private final ChatRepository repository;
    private final AttachmentStorage attachmentStorage;
    private final GitHubService githubService = new GitHubService();
    private final ScheduledExecutorService scheduledCallDispatcher = Executors.newSingleThreadScheduledExecutor();
    private ChatWebSocketServer webSocketServer;
    public ChatServer(int port, Path database) throws IOException, SQLException {
        String databaseUrl = System.getenv("DATABASE_URL");
        repository = new ChatRepository(databaseUrl == null || databaseUrl.isBlank()
                ? "jdbc:sqlite:" + database.toAbsolutePath()
                : databaseUrl);
        attachmentStorage = AttachmentStorage.fromEnvironment();
        server = createServer(port);
        server.createContext("/api/registration", this::handleRegistration);
        server.createContext("/api/login", this::handleLogin);
        server.createContext("/api/logout", this::handleLogout);
        server.createContext("/health", this::handleHealth);
        server.createContext("/api/files", this::handleFiles);
        server.createContext("/api/calls", this::handleCalls);
        server.createContext("/api/profile", this::handleProfile);
        server.createContext("/api/notifications", this::handleNotifications);
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
            repository.updatePresence(account.username(), "online");
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

    private void handleLogout(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring("Bearer ".length()).trim();
                ChatRepository.Session session = repository.findSession(token);
                if (session != null && session.username() != null && !session.username().isBlank()) {
                    repository.updatePresence(session.username(), "offline");
                }
                repository.deleteSession(token);
            }
            writeJson(exchange, 204, "");
        } catch (SQLException e) {
            writeJson(exchange, 500, "{\"error\":\"Database error\"}");
        } finally {
            exchange.close();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            writeJson(exchange, 200, "{\"status\":\"ok\"}");
        } finally {
            exchange.close();
        }
    }

    private void handleProfile(HttpExchange exchange) throws IOException {
        try {
            ChatRepository.UserAccount account = authenticatedUser(exchange);
            if (account == null) {
                writeJson(exchange, 401, "{\"error\":\"Login required\"}");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 200, profileJson(repository.findProfile(account.username())));
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod()) || "POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String about = optionalJsonValue(body, "about", "");
                String presence = optionalJsonValue(body, "presence", "online");
                writeJson(exchange, 200, profileJson(repository.updateProfile(account.username(), about, presence)));
                return;
            }
            writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
        } catch (IllegalArgumentException e) {
            writeJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            writeJson(exchange, 500, "{\"error\":\"Database error\"}");
        } finally {
            exchange.close();
        }
    }

    private void handleNotifications(HttpExchange exchange) throws IOException {
        try {
            ChatRepository.UserAccount account = authenticatedUser(exchange);
            if (account == null) {
                writeJson(exchange, 401, "{\"error\":\"Login required\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/api/notifications".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 200, notificationsJson(repository.findNotifications(account.username())));
                return;
            }
            String prefix = "/api/notifications/";
            if (path.startsWith(prefix) && path.endsWith("/read") && "POST".equals(exchange.getRequestMethod())) {
                String id = URLDecoder.decode(path.substring(prefix.length(), path.length() - "/read".length()), StandardCharsets.UTF_8);
                repository.markNotificationRead(id, account.username());
                writeJson(exchange, 204, "");
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

    private void handleFiles(HttpExchange exchange) throws IOException {
        ChatRepository.Attachment attachment = null;
        try {
            ChatRepository.UserAccount account = authenticatedUser(exchange);
            if (account == null) {
                writeJson(exchange, 401, "{\"error\":\"Login required\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/api/files".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                String chatId = queryValue(exchange.getRequestURI().getRawQuery(), "chatId");
                if (!repository.isChatMember(chatId, account.username())) {
                    writeJson(exchange, 403, "{\"error\":\"Chat is not available\"}");
                    return;
                }
                String originalName = exchange.getRequestHeaders().getFirst("X-Filename");
                if (originalName == null || originalName.isBlank()) {
                    throw new IllegalArgumentException("X-Filename is required");
                }
                originalName = Path.of(originalName).getFileName().toString().trim();
                if (originalName.isBlank() || originalName.length() > 180) {
                    throw new IllegalArgumentException("Invalid file name");
                }
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || contentType.isBlank()) {
                    contentType = "application/octet-stream";
                }
                byte[] data = exchange.getRequestBody().readNBytes((int) MAX_ATTACHMENT_BYTES + 1);
                if (data.length > MAX_ATTACHMENT_BYTES) {
                    writeJson(exchange, 413, "{\"error\":\"File is too large (maximum 25 MB)\"}");
                    return;
                }
                String storedName = java.util.UUID.randomUUID() + "-" + safeExtension(originalName);
                attachmentStorage.put(storedName, data, contentType);
                try {
                    attachment = repository.createAttachment(chatId, account.username(), originalName,
                            storedName, contentType, data.length);
                } catch (RuntimeException | SQLException e) {
                    attachmentStorage.delete(storedName);
                    throw e;
                }
                writeJson(exchange, 201, attachmentJson(attachment));
                return;
            }
            String prefix = "/api/files/";
            if (path.startsWith(prefix) && "GET".equals(exchange.getRequestMethod())) {
                String id = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
                attachment = repository.findAttachment(id);
                if (attachment == null || !repository.isChatMember(attachment.chatId(), account.username())) {
                    writeJson(exchange, 404, "{\"error\":\"File not found\"}");
                    return;
                }
                byte[] data;
                try {
                    data = attachmentStorage.get(attachment.storedName());
                } catch (IOException e) {
                    writeJson(exchange, 404, "{\"error\":\"File not found\"}");
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", attachment.contentType());
                String downloadName = attachment.originalName().replaceAll("[\\\"\\r\\n]", "_");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\""
                        + downloadName + "\"");
                securityHeaders(exchange);
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(data);
                }
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

    private void handleCalls(HttpExchange exchange) throws IOException {
        try {
            ChatRepository.UserAccount account = authenticatedUser(exchange);
            if (account == null) {
                writeJson(exchange, 401, "{\"error\":\"Login required\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/api/calls/ice-servers".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 200, iceServersJson());
                return;
            }
            if ("/api/calls".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ChatRepository.Call call = repository.createCall(
                        jsonValue(body, "chatId"), account.username(), jsonValue(body, "username"),
                        jsonValue(body, "mediaType"));
                writeJson(exchange, 201, callJson(call));
                return;
            }
            if ("/api/calls/incoming".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 200, callsJson(repository.findIncomingCalls(account.username())));
                return;
            }
            if ("/api/calls/history".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                String limitValue = queryValue(exchange.getRequestURI().getRawQuery(), "limit");
                int limit = limitValue.isBlank() ? 50 : Integer.parseInt(limitValue);
                writeJson(exchange, 200, callsJson(repository.findCallHistory(account.username(), limit)));
                return;
            }
            if ("/api/calls/scheduled".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 200, scheduledCallsJson(repository.findScheduledCalls(account.username())));
                return;
            }
            if ("/api/calls/scheduled".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ChatRepository.ScheduledCall scheduled = repository.scheduleCall(
                        jsonValue(body, "chatId"), account.username(), jsonValue(body, "username"),
                        jsonValue(body, "mediaType"), Instant.parse(jsonValue(body, "scheduledAt")));
                writeJson(exchange, 201, scheduledCallJson(scheduled));
                return;
            }
            String scheduledPrefix = "/api/calls/scheduled/";
            if (path.startsWith(scheduledPrefix) && "POST".equals(exchange.getRequestMethod())) {
                ChatRepository.ScheduledCall scheduled = repository.cancelScheduledCall(
                        URLDecoder.decode(path.substring(scheduledPrefix.length()), StandardCharsets.UTF_8), account.username());
                writeJson(exchange, 200, scheduledCallJson(scheduled));
                return;
            }
            String prefix = "/api/calls/";
            if (!path.startsWith(prefix)) {
                writeJson(exchange, 404, "{\"error\":\"Not found\"}");
                return;
            }
            String remainder = path.substring(prefix.length());
            int slash = remainder.indexOf('/');
            String callId = slash < 0 ? remainder : remainder.substring(0, slash);
            if (callId.isBlank()) {
                writeJson(exchange, 404, "{\"error\":\"Call not found\"}");
                return;
            }
            if (slash < 0 && "GET".equals(exchange.getRequestMethod())) {
                ChatRepository.Call call = repository.findCall(callId);
                if (call == null || (!account.username().equals(call.caller()) && !account.username().equals(call.callee()))) {
                    writeJson(exchange, 404, "{\"error\":\"Call not found\"}");
                    return;
                }
                writeJson(exchange, 200, callJson(call));
                return;
            }
            if (slash > 0 && "/events".equals(remainder.substring(slash))
                    && "GET".equals(exchange.getRequestMethod())) {
                long after = 0;
                String afterValue = queryValue(exchange.getRequestURI().getRawQuery(), "after");
                if (!afterValue.isBlank()) {
                    after = Long.parseLong(afterValue);
                }
                writeJson(exchange, 200, callEventsJson(repository.findCallEvents(callId, account.username(), after)));
                return;
            }
            if (slash > 0 && "/events".equals(remainder.substring(slash))
                    && "POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ChatRepository.CallEvent event = repository.addCallEvent(callId, account.username(),
                        jsonValue(body, "type"), jsonValue(body, "payload"));
                writeJson(exchange, 201, callEventJson(event));
                return;
            }
            writeJson(exchange, 404, "{\"error\":\"Not found\"}");
        } catch (NumberFormatException e) {
            writeJson(exchange, 400, "{\"error\":\"Invalid numeric call parameter\"}");
        } catch (IllegalArgumentException e) {
            writeJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            writeJson(exchange, 500, "{\"error\":\"Database error\"}");
        } finally {
            exchange.close();
        }
    }

    public void start() {
        server.start();
        scheduledCallDispatcher.scheduleWithFixedDelay(this::dispatchDueScheduledCalls, 5, 15, TimeUnit.SECONDS);
        String websocketPort = System.getenv("WS_PORT");
        if (websocketPort != null && !websocketPort.isBlank()) {
            try {
                webSocketServer = new ChatWebSocketServer(Integer.parseInt(websocketPort), repository);
                webSocketServer.start();
            } catch (NumberFormatException e) {
                throw new IllegalStateException("WS_PORT must be a number", e);
            }
        }
    }

    private void dispatchDueScheduledCalls() {
        try {
            for (ChatRepository.ScheduledCall call : repository.claimDueScheduledCalls(Instant.now())) {
                String title = "Запланированный звонок готов";
                String body = ("video".equals(call.mediaType()) ? "Видео" : "Аудио")
                        + " звонок по расписанию. Откройте личный чат и начните звонок.";
                ChatRepository.Notification creator = repository.createNotification(
                        call.creator(), title, body, "info", "system", null);
                ChatRepository.Notification callee = repository.createNotification(
                        call.callee(), title, body, "info", "system", null);
                if (webSocketServer != null) {
                    webSocketServer.broadcastNotification(creator);
                    webSocketServer.broadcastNotification(callee);
                }
            }
        } catch (SQLException ignored) {
            // The next scheduled pass retries other pending calls; HTTP/API traffic remains available.
        }
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
            if ("/api/admin/users".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 200, adminUsersJson(repository.adminUsers()));
                return;
            }
            if ("/api/admin/registrations".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 200, registrationsJson(repository.pendingRegistrations()));
                return;
            }
            String registrationsPrefix = "/api/admin/registrations/";
            if (path.startsWith(registrationsPrefix) && "POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ChatRepository.Registration registration = repository.decideRegistration(
                        URLDecoder.decode(path.substring(registrationsPrefix.length()), StandardCharsets.UTF_8),
                        jsonValue(body, "status"), optionalJsonValue(body, "fullName", null),
                        optionalJsonValue(body, "profession", null), optionalJsonValue(body, "workplace", null),
                        optionalJsonValue(body, "region", null), optionalJsonValue(body, "phone", null),
                        optionalJsonValue(body, "permissions", ""), optionalJsonValue(body, "username", null),
                        optionalJsonValue(body, "password", null));
                if ("APPROVED".equals(registration.status())) {
                    repository.assignChatsForUser(registration.accountUsername());
                }
                writeJson(exchange, 200, registrationJson(registration));
                return;
            }
            String usersPrefix = "/api/admin/users/";
            if (path.startsWith(usersPrefix) && "POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String username = URLDecoder.decode(path.substring(usersPrefix.length()), StandardCharsets.UTF_8);
                ChatRepository.AdminUser user = repository.updateUserAccess(username,
                        jsonValue(body, "status"), jsonValue(body, "permissions"),
                        optionalJsonValue(body, "temporaryPassword", ""));
                writeJson(exchange, 200, adminUserJson(user));
                return;
            }
            if ("/api/admin/notifications".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String recipient = optionalJsonValue(body, "recipient", "");
                String expiresAtValue = optionalJsonValue(body, "expiresAt", "");
                Instant expiresAt = expiresAtValue.isBlank() ? null : Instant.parse(expiresAtValue);
                ChatRepository.Notification notification = repository.createNotification(
                        recipient, jsonValue(body, "title"), jsonValue(body, "body"),
                        jsonValue(body, "severity"), "admin", expiresAt);
                writeJson(exchange, 201, notificationJson(notification));
                if (webSocketServer != null) {
                    webSocketServer.broadcastNotification(notification);
                }
                return;
            }
            if ("/api/admin/github/commits".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                if (!githubService.configured()) {
                    writeJson(exchange, 503, "{\"error\":\"GitHub integration is not configured\"}");
                    return;
                }
                writeJson(exchange, 200, githubService.commits());
                return;
            }
            String githubCommentPrefix = "/api/admin/github/commits/";
            if (path.startsWith(githubCommentPrefix) && path.endsWith("/comments")
                    && "POST".equals(exchange.getRequestMethod())) {
                if (!githubService.configured()) {
                    writeJson(exchange, 503, "{\"error\":\"GitHub integration is not configured\"}");
                    return;
                }
                String sha = URLDecoder.decode(path.substring(githubCommentPrefix.length(), path.length() - "/comments".length()), StandardCharsets.UTF_8);
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                writeJson(exchange, 201, githubService.comment(sha, jsonValue(body, "body")));
                return;
            }
            writeJson(exchange, 404, "{\"error\":\"Not found\"}");
        } catch (IllegalArgumentException e) {
            writeJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (SQLException e) {
            writeJson(exchange, 500, "{\"error\":\"Database error\"}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeJson(exchange, 502, "{\"error\":\"GitHub request was interrupted\"}");
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
            } else if ("GET".equals(exchange.getRequestMethod()) && parts.length == 5 && "peer".equals(parts[4])) {
                String peer = repository.findChatPeer(parts[3], account.username());
                writeJson(exchange, 200, "{\"username\":\"" + escape(peer) + "\"}");
            } else if ("GET".equals(exchange.getRequestMethod()) && parts.length == 4 && !parts[3].isBlank()) {
                writeJson(exchange, 200, messagesJson(repository.findMessages(parts[3], account.username())));
            } else if ("POST".equals(exchange.getRequestMethod()) && parts.length == 5 && "messages".equals(parts[4])) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String text = jsonValue(body, "text");
                String chatId = parts[3];
                Message message = repository.addMessage(chatId, account.username(), account.permissions(), text);
                writeJson(exchange, 201, messageJson(message));
                broadcastMessage(chatId, message);
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
                    broadcastMessage("marsel-cat", repository.addMessage("marsel-cat", "Марсель 🐾", "", meow));
                } else if ("helpdesk".equals(chatId) && !account.permissions().contains("support_agent")) {
                    String autoReply = "🤖 Бот техподдержки: Здравствуйте, " + account.displayName() +
                            "! Ваше обращение принято: «" + text + "». Дежурный специалист техподдержки уже видит ваш запрос и ответит вам здесь!";
                    broadcastMessage("helpdesk", repository.addMessage("helpdesk", "AI Helpdesk Bot", "", autoReply));
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

    private void broadcastMessage(String chatId, Message message) {
        if (webSocketServer != null) {
            webSocketServer.broadcast(chatId, message);
        }
    }

    private void handleUsers(HttpExchange exchange) throws IOException {
        try {
            ChatRepository.UserAccount account = authenticatedUser(exchange);
            if (account == null) {
                writeJson(exchange, 401, "{\"error\":\"Login required\"}");
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
        String requestPath = exchange.getRequestURI().getPath();
        boolean knownPage = "/".equals(requestPath) || "/admin".equals(requestPath)
                || "/manifest.webmanifest".equals(requestPath) || "/sw.js".equals(requestPath)
                || "/icons/tls-chat.svg".equals(requestPath);
        if (!"GET".equals(exchange.getRequestMethod()) || !knownPage) {
            writeJson(exchange, 404, "{\"error\":\"Not found\"}");
            exchange.close();
            return;
        }
        byte[] content;
        String resourceName = "/admin".equals(requestPath) ? "admin.html"
                : "/manifest.webmanifest".equals(requestPath) ? "manifest.webmanifest"
                : "/sw.js".equals(requestPath) ? "sw.js"
                : "/icons/tls-chat.svg".equals(requestPath) ? "icons/tls-chat.svg" : "index.html";
        String resource = "/web/" + resourceName;
        try (InputStream page = ChatServer.class.getResourceAsStream(resource)) {
            if (page == null) {
                writeJson(exchange, 500, "{\"error\":\"Web interface is unavailable\"}");
                exchange.close();
                return;
            }
            content = page.readAllBytes();
        }
        String contentType = resourceName.endsWith(".webmanifest") ? "application/manifest+json"
                : resourceName.endsWith(".js") ? "application/javascript; charset=UTF-8"
                : resourceName.endsWith(".svg") ? "image/svg+xml"
                : "text/html; charset=UTF-8";
        exchange.getResponseHeaders().set("Content-Type", contentType);
        securityHeaders(exchange);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private void handlePhotos(HttpExchange exchange) throws IOException {
        try {
            ChatRepository.UserAccount account;
            try {
                account = authenticatedUser(exchange);
            } catch (SQLException e) {
                writeJson(exchange, 500, "{\"error\":\"Database error\"}");
                return;
            }
            if (account == null) {
                writeJson(exchange, 401, "{\"error\":\"Login required\"}");
                return;
            }
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
            securityHeaders(exchange);
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

    private static String attachmentJson(ChatRepository.Attachment attachment) {
        return "{\"id\":\"" + escape(attachment.id()) + "\",\"name\":\""
                + escape(attachment.originalName()) + "\",\"contentType\":\""
                + escape(attachment.contentType()) + "\",\"size\":" + attachment.size()
                + ",\"url\":\"/api/files/" + escape(attachment.id()) + "\"}";
    }

    private static String iceServersJson() {
        String turnUrls = System.getenv().getOrDefault("TURN_URL", "").trim();
        String turnUsername = System.getenv().getOrDefault("TURN_USERNAME", "").trim();
        String turnCredential = System.getenv().getOrDefault("TURN_CREDENTIAL", "").trim();
        StringBuilder json = new StringBuilder("{\"iceServers\":[{\"urls\":\"stun:stun.l.google.com:19302\"}");
        if (!turnUrls.isBlank() && !turnUsername.isBlank() && !turnCredential.isBlank()) {
            json.append(",{\"urls\":[");
            String[] urls = turnUrls.split(",");
            for (int i = 0; i < urls.length; i++) {
                if (i > 0) json.append(',');
                json.append('\"').append(escape(urls[i].trim())).append('\"');
            }
            json.append("],\"username\":\"").append(escape(turnUsername))
                    .append("\",\"credential\":\"").append(escape(turnCredential)).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static String callJson(ChatRepository.Call call) {
        return "{\"id\":\"" + escape(call.id()) + "\",\"chatId\":\"" + escape(call.chatId())
                + "\",\"caller\":\"" + escape(call.caller()) + "\",\"callee\":\""
                + escape(call.callee()) + "\",\"mediaType\":\"" + escape(call.mediaType())
                + "\",\"status\":\"" + escape(call.status()) + "\",\"createdAt\":\""
                + call.createdAt() + "\"}";
    }

    private static String callEventJson(ChatRepository.CallEvent event) {
        return "{\"id\":" + event.id() + ",\"callId\":\"" + escape(event.callId())
                + "\",\"sender\":\"" + escape(event.sender()) + "\",\"type\":\""
                + escape(event.eventType()) + "\",\"payload\":\"" + escape(event.payload())
                + "\",\"createdAt\":\"" + event.createdAt() + "\"}";
    }

    private static String callEventsJson(List<ChatRepository.CallEvent> events) {
        return "[" + events.stream().map(ChatServer::callEventJson)
                .collect(Collectors.joining(",")) + "]";
    }

    private static String callsJson(List<ChatRepository.Call> calls) {
        return "[" + calls.stream().map(ChatServer::callJson)
                .collect(Collectors.joining(",")) + "]";
    }

    private static String scheduledCallJson(ChatRepository.ScheduledCall call) {
        return "{\"id\":\"" + escape(call.id()) + "\",\"chatId\":\"" + escape(call.chatId())
                + "\",\"creator\":\"" + escape(call.creator()) + "\",\"callee\":\""
                + escape(call.callee()) + "\",\"mediaType\":\"" + escape(call.mediaType())
                + "\",\"scheduledAt\":\"" + call.scheduledAt() + "\",\"status\":\""
                + escape(call.status()) + "\",\"createdAt\":\"" + call.createdAt() + "\"}";
    }

    private static String scheduledCallsJson(List<ChatRepository.ScheduledCall> calls) {
        return "[" + calls.stream().map(ChatServer::scheduledCallJson)
                .collect(Collectors.joining(",")) + "]";
    }

    private static String profileJson(ChatRepository.UserProfile profile) {
        if (profile == null) return "null";
        return "{\"username\":\"" + escape(profile.username()) + "\",\"displayName\":\""
                + escape(profile.displayName()) + "\",\"profession\":\"" + escape(profile.profession())
                + "\",\"workplace\":\"" + escape(profile.workplace()) + "\",\"region\":\""
                + escape(profile.region()) + "\",\"phone\":\"" + escape(profile.phone())
                + "\",\"permissions\":\"" + escape(profile.permissions()) + "\",\"about\":\""
                + escape(profile.about()) + "\",\"presence\":\"" + escape(profile.presence())
                + "\",\"lastSeen\":" + (profile.lastSeen() == null ? "null" : "\"" + profile.lastSeen() + "\"") + "}";
    }

    private static String notificationJson(ChatRepository.Notification notification) {
        return "{\"id\":\"" + escape(notification.id()) + "\",\"recipient\":\""
                + escape(notification.recipient()) + "\",\"title\":\"" + escape(notification.title())
                + "\",\"body\":\"" + escape(notification.body()) + "\",\"severity\":\""
                + escape(notification.severity()) + "\",\"createdBy\":\"" + escape(notification.createdBy())
                + "\",\"createdAt\":\"" + notification.createdAt() + "\",\"read\":"
                + notification.read() + "}";
    }

    private static String notificationsJson(List<ChatRepository.Notification> notifications) {
        return "[" + notifications.stream().map(ChatServer::notificationJson)
                .collect(Collectors.joining(",")) + "]";
    }

    private static String adminUserJson(ChatRepository.AdminUser user) {
        return "{\"username\":\"" + escape(user.username()) + "\",\"displayName\":\""
                + escape(user.displayName()) + "\",\"profession\":\"" + escape(user.profession())
                + "\",\"workplace\":\"" + escape(user.workplace()) + "\",\"region\":\""
                + escape(user.region()) + "\",\"permissions\":\"" + escape(user.permissions())
                + "\",\"status\":\"" + escape(user.status()) + "\",\"about\":\""
                + escape(user.about()) + "\",\"presence\":\"" + escape(user.presence()) + "\"}";
    }

    private static String adminUsersJson(List<ChatRepository.AdminUser> users) {
        return "[" + users.stream().map(ChatServer::adminUserJson).collect(Collectors.joining(",")) + "]";
    }

    private static String queryValue(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            String rawKey = separator < 0 ? part : part.substring(0, separator);
            if (key.equals(URLDecoder.decode(rawKey, StandardCharsets.UTF_8))) {
                return separator < 0 ? "" : URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String safeExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1 || fileName.length() - dot > 12) {
            return "";
        }
        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]+") ? extension : "";
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

    private static String optionalJsonValue(String json, String key, String fallback) {
        try {
            return jsonValue(json, key);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
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
        securityHeaders(exchange);
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(self), microphone=(self)");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' ws: wss:; "
                        + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        String forwardedProto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        if (exchange instanceof HttpsExchange || "https".equalsIgnoreCase(forwardedProto)) {
            exchange.getResponseHeaders().set("Strict-Transport-Security",
                    "max-age=31536000; includeSubDomains");
        }
    }

    @Override
    public void close() {
        scheduledCallDispatcher.shutdownNow();
        if (webSocketServer != null) {
            webSocketServer.close();
        }
        server.stop(0);
        attachmentStorage.close();
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
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                chatServer.close();
            } finally {
                shutdown.countDown();
            }
        }));
        chatServer.start();
        System.out.println("TLS Chat server started: http://localhost:" + chatServer.port());
        shutdown.await();
    }
}
