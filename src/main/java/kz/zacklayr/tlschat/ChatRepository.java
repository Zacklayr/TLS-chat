package kz.zacklayr.tlschat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ChatRepository implements AutoCloseable {
    private final Connection connection;
    private final boolean postgres;

    public ChatRepository(String jdbcUrl) throws SQLException {
        postgres = jdbcUrl.startsWith("postgres://") || jdbcUrl.startsWith("postgresql://")
                || jdbcUrl.startsWith("jdbc:postgresql:");
        connection = java.sql.DriverManager.getConnection(normalizeJdbcUrl(jdbcUrl));
        initialize();
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS chats (
                        id TEXT PRIMARY KEY,
                        type TEXT NOT NULL,
                        name TEXT NOT NULL,
                        color TEXT NOT NULL,
                        members INTEGER NOT NULL DEFAULT 0,
                        online INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.executeUpdate(postgres ? """
                    CREATE TABLE IF NOT EXISTS messages (
                        id BIGSERIAL PRIMARY KEY,
                        chat_id TEXT NOT NULL REFERENCES chats(id),
                        sender TEXT NOT NULL,
                        text TEXT NOT NULL,
                        sent_at TEXT NOT NULL
                    )
                    """ : """
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        chat_id TEXT NOT NULL REFERENCES chats(id),
                        sender TEXT NOT NULL,
                        text TEXT NOT NULL,
                        sent_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate(postgres ? """
                    CREATE TABLE IF NOT EXISTS users (
                        id BIGSERIAL PRIMARY KEY,
                        username TEXT NOT NULL UNIQUE,
                        display_name TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        created_at TEXT NOT NULL,
                        profession TEXT NOT NULL DEFAULT '',
                        workplace TEXT NOT NULL DEFAULT '',
                        region TEXT NOT NULL DEFAULT '',
                        phone TEXT NOT NULL DEFAULT '',
                        permissions TEXT NOT NULL DEFAULT '',
                        password_salt TEXT NOT NULL DEFAULT '',
                        password_hash TEXT NOT NULL DEFAULT ''
                    )
                    """ : """
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL UNIQUE,
                        display_name TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        created_at TEXT NOT NULL,
                        profession TEXT NOT NULL DEFAULT '',
                        workplace TEXT NOT NULL DEFAULT '',
                        region TEXT NOT NULL DEFAULT '',
                        phone TEXT NOT NULL DEFAULT '',
                        permissions TEXT NOT NULL DEFAULT '',
                        password_salt TEXT NOT NULL DEFAULT '',
                        password_hash TEXT NOT NULL DEFAULT ''
                    )
                    """);
            ensureUserColumn(statement, "profession", "TEXT NOT NULL DEFAULT ''");
            ensureUserColumn(statement, "workplace", "TEXT NOT NULL DEFAULT ''");
            ensureUserColumn(statement, "region", "TEXT NOT NULL DEFAULT ''");
            ensureUserColumn(statement, "phone", "TEXT NOT NULL DEFAULT ''");
            ensureUserColumn(statement, "permissions", "TEXT NOT NULL DEFAULT ''");
            ensureUserColumn(statement, "password_salt", "TEXT NOT NULL DEFAULT ''");
            ensureUserColumn(statement, "password_hash", "TEXT NOT NULL DEFAULT ''");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS registration_requests (
                        id TEXT PRIMARY KEY,
                        full_name TEXT NOT NULL,
                        profession TEXT NOT NULL,
                        workplace TEXT NOT NULL,
                        region TEXT NOT NULL,
                        phone TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        account_username TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private void ensureUserColumn(Statement statement, String name, String definition) throws SQLException {
        try (ResultSet columns = statement.executeQuery("SELECT * FROM users WHERE 1 = 0")) {
            if (columns.getMetaData().getColumnCount() > 0) {
                for (int i = 1; i <= columns.getMetaData().getColumnCount(); i++) {
                    if (name.equalsIgnoreCase(columns.getMetaData().getColumnName(i))) {
                        return;
                    }
                }
            }
        }
        statement.executeUpdate("ALTER TABLE users ADD COLUMN " + name + " " + definition);
    }

    static String normalizeJdbcUrl(String jdbcUrl) {
        if (jdbcUrl.startsWith("jdbc:")) {
            return jdbcUrl;
        }
        if (jdbcUrl.startsWith("postgres://") || jdbcUrl.startsWith("postgresql://")) {
            URI database = URI.create(jdbcUrl);
            if (database.getHost() == null || database.getPath() == null) {
                throw new IllegalArgumentException("Invalid PostgreSQL database URL");
            }
            StringBuilder normalized = new StringBuilder("jdbc:postgresql://")
                    .append(database.getHost());
            if (database.getPort() >= 0) {
                normalized.append(':').append(database.getPort());
            }
            normalized.append(database.getRawPath());

            String rawQuery = database.getRawQuery();
            String userInfo = database.getUserInfo();
            if (userInfo != null) {
                int separator = userInfo.indexOf(':');
                if (separator <= 0 || separator == userInfo.length() - 1) {
                    throw new IllegalArgumentException("Invalid PostgreSQL database credentials");
                }
                String user = userInfo.substring(0, separator);
                String password = userInfo.substring(separator + 1);
                normalized.append('?')
                        .append("user=").append(URLEncoder.encode(user, StandardCharsets.UTF_8))
                        .append("&password=").append(URLEncoder.encode(password, StandardCharsets.UTF_8));
                if (rawQuery != null && !rawQuery.isBlank()) {
                    normalized.append('&').append(rawQuery);
                }
            } else if (rawQuery != null && !rawQuery.isBlank()) {
                normalized.append('?').append(rawQuery);
            }
            return normalized.toString();
        }
        return jdbcUrl;
    }

    public synchronized List<ChatSummary> findChats() throws SQLException {
        List<ChatSummary> chats = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT c.*, (SELECT COUNT(*) FROM messages m WHERE m.chat_id = c.id) AS message_count " +
                        "FROM chats c ORDER BY c.type, c.name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                chats.add(new ChatSummary(result.getString("id"), result.getString("type"),
                        result.getString("name"), result.getString("color"), result.getInt("members"),
                        result.getInt("online"), result.getInt("message_count")));
            }
        }
        return chats;
    }

    public synchronized List<Message> findMessages(String chatId) throws SQLException {
        if (!chatExists(chatId)) {
            throw new IllegalArgumentException("Unknown chat: " + chatId);
        }
        List<Message> messages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sender, text, sent_at FROM messages WHERE chat_id = ? ORDER BY id")) {
            statement.setString(1, chatId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    messages.add(new Message(result.getString("sender"), result.getString("text"),
                            Instant.parse(result.getString("sent_at"))));
                }
            }
        }
        return messages;
    }

    public synchronized Message addMessage(String chatId, String sender, String text) throws SQLException {
        if (!chatExists(chatId)) {
            throw new IllegalArgumentException("Unknown chat: " + chatId);
        }
        if (sender == null || sender.isBlank() || text == null || text.isBlank() || text.length() > 4000) {
            throw new IllegalArgumentException("Sender and message text are required; text must be 1..4000 characters");
        }
        Instant sentAt = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO messages(chat_id, sender, text, sent_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, chatId);
            statement.setString(2, sender);
            statement.setString(3, text);
            statement.setString(4, sentAt.toString());
            statement.executeUpdate();
        }
        return new Message(sender, text, sentAt);
    }

    public synchronized Registration registerApplication(String fullName, String profession,
                                                         String workplace, String region, String phone)
            throws SQLException {
        if (fullName == null || fullName.isBlank() || fullName.length() > 120
                || profession == null || profession.isBlank() || profession.length() > 80
                || workplace == null || workplace.isBlank() || workplace.length() > 80
                || region == null || region.isBlank() || region.length() > 80
                || phone == null || phone.isBlank() || phone.length() > 32) {
            throw new IllegalArgumentException("Заполните ФИО, профессию, место работы, регион и номер телефона");
        }
        String requestId = java.util.UUID.randomUUID().toString();
        String normalizedFullName = fullName.trim();
        String normalizedProfession = profession.trim();
        String normalizedWorkplace = workplace.trim();
        String normalizedRegion = region.trim();
        String normalizedPhone = phone.trim();
        Instant createdAt = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO registration_requests(id, full_name, profession, workplace, region, phone, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, requestId);
            statement.setString(2, normalizedFullName);
            statement.setString(3, normalizedProfession);
            statement.setString(4, normalizedWorkplace);
            statement.setString(5, normalizedRegion);
            statement.setString(6, normalizedPhone);
            statement.setString(7, createdAt.toString());
            statement.executeUpdate();
        }
        return new Registration(requestId, normalizedFullName, normalizedProfession,
                normalizedWorkplace, normalizedRegion, normalizedPhone, "", "PENDING", "", createdAt);
    }

    public synchronized List<Registration> pendingRegistrations() throws SQLException {
        List<Registration> registrations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, full_name, profession, workplace, region, phone, status, account_username, created_at " +
                        "FROM registration_requests WHERE status = 'PENDING' ORDER BY created_at");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                registrations.add(registrationFrom(result));
            }
        }
        return registrations;
    }

    public synchronized Registration decideRegistration(String requestId, String status, String fullName,
                                                         String profession, String workplace, String region,
                                                         String phone, String permissions, String username,
                                                         String password) throws SQLException {
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw new IllegalArgumentException("Status must be APPROVED or REJECTED");
        }
        String normalizedPermissions = normalizePermissions(permissions);
        Registration request = findRequest(requestId);
        if ("APPROVED".equals(status)) {
            if (username == null || !username.matches("[a-zA-Z0-9_]{3,32}")
                    || password == null || password.length() < 8 || password.length() > 128) {
                throw new IllegalArgumentException("Регистратор должен указать логин и пароль (8-128 символов)");
            }
            if (fullName == null || fullName.isBlank() || profession == null || profession.isBlank()
                    || workplace == null || workplace.isBlank() || region == null || region.isBlank()
                    || phone == null || phone.isBlank()) {
                throw new IllegalArgumentException("Анкета сотрудника заполнена не полностью");
            }
            PasswordHasher.PasswordHash passwordHash = PasswordHasher.hash(password.toCharArray());
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO users(username, display_name, profession, workplace, region, phone, permissions, " +
                            "password_salt, password_hash, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, username.toLowerCase(Locale.ROOT));
                statement.setString(2, fullName.trim());
                statement.setString(3, profession.trim());
                statement.setString(4, workplace.trim());
                statement.setString(5, region.trim());
                statement.setString(6, phone.trim());
                statement.setString(7, normalizedPermissions);
                statement.setString(8, passwordHash.salt());
                statement.setString(9, passwordHash.hash());
                statement.setString(10, "APPROVED");
                statement.setString(11, Instant.now().toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())
                        || (e.getMessage() != null && e.getMessage().contains("UNIQUE"))) {
                    throw new IllegalArgumentException("Пользователь с таким логином уже существует");
                }
                throw e;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE registration_requests SET status = ?, account_username = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setString(2, "APPROVED".equals(status) ? username.toLowerCase(Locale.ROOT) : "");
            statement.setString(3, requestId);
            statement.executeUpdate();
        }
        return new Registration(request.id(), fullName == null ? request.fullName() : fullName.trim(),
                profession == null ? request.profession() : profession.trim(),
                workplace == null ? request.workplace() : workplace.trim(),
                region == null ? request.region() : region.trim(),
                phone == null ? request.phone() : phone.trim(), normalizedPermissions, status,
                "APPROVED".equals(status) ? username.toLowerCase(Locale.ROOT) : "", request.createdAt());
    }

    private Registration registrationFrom(ResultSet result) throws SQLException {
        return new Registration(result.getString("id"), result.getString("full_name"),
                result.getString("profession"), result.getString("workplace"), result.getString("region"),
                result.getString("phone"), "", result.getString("status"),
                result.getString("account_username"), Instant.parse(result.getString("created_at")));
    }

    private Registration findRequest(String requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, full_name, profession, workplace, region, phone, status, account_username, created_at " +
                        "FROM registration_requests WHERE id = ?")) {
            statement.setString(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Заявка не найдена");
                }
                return registrationFrom(result);
            }
        }
    }

    static String normalizePermissions(String permissions) {
        if (permissions == null || permissions.isBlank()) {
            return "";
        }
        Set<String> allowed = Set.of("global_write", "regional_write", "hidden_data",
                "manage_users", "manage_chats");
        Set<String> selected = new HashSet<>();
        for (String permission : permissions.split(",")) {
            String value = permission.trim();
            if (!value.isEmpty() && !allowed.contains(value)) {
                throw new IllegalArgumentException("Unknown permission: " + value);
            }
            if (!value.isEmpty()) {
                selected.add(value);
            }
        }
        return String.join(",", selected.stream().sorted().toList());
    }

    private boolean chatExists(String chatId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM chats WHERE id = ?")) {
            statement.setString(1, chatId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    public record ChatSummary(String id, String type, String name, String color,
                              int members, int online, int messageCount) {
    }

    public record Registration(String id, String fullName, String profession, String workplace,
                               String region, String phone, String permissions, String status,
                               String accountUsername, Instant createdAt) {
    }
}
