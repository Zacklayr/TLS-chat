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
                        permissions TEXT NOT NULL DEFAULT '',
                        password_salt TEXT NOT NULL DEFAULT '',
                        password_hash TEXT NOT NULL DEFAULT ''
                    )
                    """);
            ensureUserColumn(statement, "profession", "TEXT NOT NULL DEFAULT ''");
            ensureUserColumn(statement, "workplace", "TEXT NOT NULL DEFAULT ''");
            ensureUserColumn(statement, "permissions", "TEXT NOT NULL DEFAULT ''");
            ensureUserColumn(statement, "password_salt", "TEXT NOT NULL DEFAULT ''");
            ensureUserColumn(statement, "password_hash", "TEXT NOT NULL DEFAULT ''");
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

    public synchronized Registration register(String username, String displayName, String password,
                                              String profession, String workplace) throws SQLException {
        if (username == null || !username.matches("[a-zA-Z0-9_]{3,32}")
                || displayName == null || displayName.isBlank() || displayName.length() > 80
                || password == null || password.length() < 8 || password.length() > 128
                || profession == null || profession.isBlank() || profession.length() > 80
                || workplace == null || workplace.isBlank() || workplace.length() > 80) {
            throw new IllegalArgumentException("Заполните логин, имя, пароль (8-128 символов), профессию и место работы");
        }
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        String normalizedDisplayName = displayName.trim();
        String normalizedProfession = profession.trim();
        String normalizedWorkplace = workplace.trim();
        PasswordHasher.PasswordHash passwordHash = PasswordHasher.hash(password.toCharArray());
        Instant createdAt = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users(username, display_name, profession, workplace, password_salt, password_hash, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, normalizedUsername);
            statement.setString(2, normalizedDisplayName);
            statement.setString(3, normalizedProfession);
            statement.setString(4, normalizedWorkplace);
            statement.setString(5, passwordHash.salt());
            statement.setString(6, passwordHash.hash());
            statement.setString(7, createdAt.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())
                    || (e.getMessage() != null && e.getMessage().contains("UNIQUE"))) {
                throw new IllegalArgumentException("Пользователь с таким именем уже зарегистрирован");
            }
            throw e;
        }
        return new Registration(normalizedUsername, normalizedDisplayName, normalizedProfession,
                normalizedWorkplace, "", "PENDING", createdAt);
    }

    public synchronized List<Registration> pendingRegistrations() throws SQLException {
        List<Registration> registrations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT username, display_name, profession, workplace, permissions, status, created_at " +
                        "FROM users WHERE status = 'PENDING' ORDER BY created_at");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                registrations.add(registrationFrom(result));
            }
        }
        return registrations;
    }

    public synchronized Registration decideRegistration(String username, String status, String profession,
                                                         String workplace, String permissions) throws SQLException {
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw new IllegalArgumentException("Status must be APPROVED or REJECTED");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if ("APPROVED".equals(status)
                && (profession == null || profession.isBlank() || workplace == null || workplace.isBlank())) {
            throw new IllegalArgumentException("Профессия и место работы обязательны для одобрения");
        }
        String normalizedPermissions = normalizePermissions(permissions);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE users SET status = ?, profession = ?, workplace = ?, permissions = ? WHERE username = ?")) {
            statement.setString(1, status);
            statement.setString(2, profession == null ? "" : profession.trim());
            statement.setString(3, workplace == null ? "" : workplace.trim());
            statement.setString(4, normalizedPermissions);
            statement.setString(5, username.toLowerCase(Locale.ROOT));
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Заявка не найдена");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT username, display_name, profession, workplace, permissions, status, created_at " +
                        "FROM users WHERE username = ?")) {
            statement.setString(1, username.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Заявка не найдена");
                }
                return registrationFrom(result);
            }
        }
    }

    private Registration registrationFrom(ResultSet result) throws SQLException {
        return new Registration(result.getString("username"), result.getString("display_name"),
                result.getString("profession"), result.getString("workplace"),
                result.getString("permissions"), result.getString("status"),
                Instant.parse(result.getString("created_at")));
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

    public record Registration(String username, String displayName, String profession, String workplace,
                               String permissions, String status, Instant createdAt) {
    }
}
