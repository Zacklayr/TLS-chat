package kz.zacklayr.tlschat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ChatRepository implements AutoCloseable {
    private final Connection connection;

    public ChatRepository(String jdbcUrl) throws SQLException {
        connection = java.sql.DriverManager.getConnection(jdbcUrl);
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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        chat_id TEXT NOT NULL REFERENCES chats(id),
                        sender TEXT NOT NULL,
                        text TEXT NOT NULL,
                        sent_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL UNIQUE,
                        display_name TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        created_at TEXT NOT NULL
                    )
                    """);
        }
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

    public synchronized Registration register(String username, String displayName) throws SQLException {
        if (username == null || !username.matches("[a-zA-Z0-9_]{3,32}")
                || displayName == null || displayName.isBlank() || displayName.length() > 80) {
            throw new IllegalArgumentException("Username: 3-32 латинских букв, цифр или _; имя: 1-80 символов");
        }
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        String normalizedDisplayName = displayName.trim();
        Instant createdAt = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users(username, display_name, created_at) VALUES (?, ?, ?)")) {
            statement.setString(1, normalizedUsername);
            statement.setString(2, normalizedDisplayName);
            statement.setString(3, createdAt.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                throw new IllegalArgumentException("Пользователь с таким именем уже зарегистрирован");
            }
            throw e;
        }
        return new Registration(normalizedUsername, normalizedDisplayName, "PENDING", createdAt);
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

    public record Registration(String username, String displayName, String status, Instant createdAt) {
    }
}
