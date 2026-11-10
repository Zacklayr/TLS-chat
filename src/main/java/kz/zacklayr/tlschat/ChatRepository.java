package kz.zacklayr.tlschat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
        }
        seed();
    }

    private void seed() throws SQLException {
        try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM chats");
             ResultSet result = count.executeQuery()) {
            if (result.next() && result.getInt(1) > 0) {
                return;
            }
        }

        try (PreparedStatement chat = connection.prepareStatement(
                "INSERT INTO chats(id, type, name, color, members, online) VALUES (?, ?, ?, ?, ?, ?)");
             PreparedStatement message = connection.prepareStatement(
                     "INSERT INTO messages(chat_id, sender, text, sent_at) VALUES (?, ?, ?, ?)")) {
            addChat(chat, "general", "group", "Общий", "c-blue", 12, 3);
            addChat(chat, "dev", "group", "Разработка", "c-purple", 5, 2);
            addChat(chat, "random", "group", "Флудилка", "c-blue", 20, 5);
            addChat(chat, "bob", "direct", "Bob", "c-green", 0, 1);
            addChat(chat, "carol", "direct", "Carol", "c-pink", 0, 1);
            addChat(chat, "admin", "direct", "Admin", "c-orange", 0, 0);

            addMessage(message, "general", "bob", "Всем доброе утро", "2026-11-10T09:00:00Z");
            addMessage(message, "general", "carol", "Сервер сегодня поднимали?", "2026-11-10T09:02:00Z");
            addMessage(message, "general", "bob", "да, сертификат проверен, всё ок", "2026-11-10T09:03:00Z");
            addMessage(message, "dev", "admin", "релиз v0.9 в ветке main", "2026-11-10T10:15:00Z");
            addMessage(message, "dev", "carol", "тесты на SslContextFactory зелёные", "2026-11-10T10:16:00Z");
            addMessage(message, "bob", "bob", "Глянешь PR с PBKDF2?", "2026-11-09T18:40:00Z");
            addMessage(message, "carol", "carol", "Скинь, пожалуйста, новый сертификат для телефона", "2026-11-10T08:30:00Z");
        }
    }

    private static void addChat(PreparedStatement statement, String id, String type, String name,
                                String color, int members, int online) throws SQLException {
        statement.setString(1, id);
        statement.setString(2, type);
        statement.setString(3, name);
        statement.setString(4, color);
        statement.setInt(5, members);
        statement.setInt(6, online);
        statement.addBatch();
        statement.executeBatch();
    }

    private static void addMessage(PreparedStatement statement, String chatId, String sender,
                                   String text, String sentAt) throws SQLException {
        statement.setString(1, chatId);
        statement.setString(2, sender);
        statement.setString(3, text);
        statement.setString(4, sentAt);
        statement.addBatch();
        statement.executeBatch();
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
}
