package kz.zacklayr.tlschat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ChatRepository implements AutoCloseable {
    private static final long SESSION_DAYS = 30;
    private static final Set<String> ALLOWED_PERMISSIONS = Set.of(
            "ceo", "department_lead", "global_write", "regional_write",
            "support_agent", "hidden_data", "manage_users", "manage_chats",
            "engine_lead", "integration_lead", "porting_lead", "support_lead",
            "cybersecurity_lead", "language_lead", "equipment_lead", "wellbeing_lead",
            "design_lead", "game_design_lead", "scenario_lead", "sound_lead"
    );

    private final String normalizedJdbcUrl;
    private final boolean postgres;
    private Connection connection;

    public ChatRepository(String jdbcUrl) throws SQLException {
        postgres = jdbcUrl.startsWith("postgres://") || jdbcUrl.startsWith("postgresql://")
                || jdbcUrl.startsWith("jdbc:postgresql:");
        normalizedJdbcUrl = normalizeJdbcUrl(jdbcUrl);
        connection = java.sql.DriverManager.getConnection(normalizedJdbcUrl);
        initialize();
    }

    private synchronized Connection conn() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
            connection = java.sql.DriverManager.getConnection(normalizedJdbcUrl);
        }
        return connection;
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
                        online INTEGER NOT NULL DEFAULT 0,
                        profession_rule TEXT NOT NULL DEFAULT '',
                        workplace_rule TEXT NOT NULL DEFAULT '',
                        region_rule TEXT NOT NULL DEFAULT '',
                        write_permissions TEXT NOT NULL DEFAULT ''
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
            ensureUserColumn(statement, "about", "TEXT NOT NULL DEFAULT ''");
            ensureUserColumn(statement, "presence", "TEXT NOT NULL DEFAULT 'offline'");
            ensureUserColumn(statement, "last_seen", "TEXT NOT NULL DEFAULT ''");
            ensureChatColumn(statement, "profession_rule", "TEXT NOT NULL DEFAULT ''");
            ensureChatColumn(statement, "workplace_rule", "TEXT NOT NULL DEFAULT ''");
            ensureChatColumn(statement, "region_rule", "TEXT NOT NULL DEFAULT ''");
            ensureChatColumn(statement, "write_permissions", "TEXT NOT NULL DEFAULT ''");
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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS chat_members (
                        chat_id TEXT NOT NULL,
                        username TEXT NOT NULL,
                        PRIMARY KEY (chat_id, username)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        token TEXT PRIMARY KEY,
                        role TEXT NOT NULL,
                        username TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS message_reads (
                        message_id BIGINT NOT NULL,
                        username TEXT NOT NULL,
                        read_at TEXT NOT NULL,
                        PRIMARY KEY (message_id, username)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS attachments (
                        id TEXT PRIMARY KEY,
                        chat_id TEXT NOT NULL REFERENCES chats(id),
                        uploader TEXT NOT NULL,
                        original_name TEXT NOT NULL,
                        stored_name TEXT NOT NULL,
                        content_type TEXT NOT NULL,
                        size BIGINT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate(postgres ? """
                    CREATE TABLE IF NOT EXISTS calls (
                        id TEXT PRIMARY KEY,
                        chat_id TEXT NOT NULL,
                        caller TEXT NOT NULL,
                        callee TEXT NOT NULL,
                        media_type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        ended_at TEXT NOT NULL DEFAULT ''
                    )
                    """ : """
                    CREATE TABLE IF NOT EXISTS calls (
                        id TEXT PRIMARY KEY,
                        chat_id TEXT NOT NULL,
                        caller TEXT NOT NULL,
                        callee TEXT NOT NULL,
                        media_type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        ended_at TEXT NOT NULL DEFAULT ''
                    )
                    """);
            statement.executeUpdate(postgres ? """
                    CREATE TABLE IF NOT EXISTS call_events (
                        id BIGSERIAL PRIMARY KEY,
                        call_id TEXT NOT NULL REFERENCES calls(id),
                        sender TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """ : """
                    CREATE TABLE IF NOT EXISTS call_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        call_id TEXT NOT NULL REFERENCES calls(id),
                        sender TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS notifications (
                        id TEXT PRIMARY KEY,
                        recipient_username TEXT NOT NULL DEFAULT '',
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        created_by TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL DEFAULT ''
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS notification_reads (
                        notification_id TEXT NOT NULL REFERENCES notifications(id),
                        username TEXT NOT NULL,
                        read_at TEXT NOT NULL,
                        PRIMARY KEY (notification_id, username)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS scheduled_calls (
                        id TEXT PRIMARY KEY,
                        chat_id TEXT NOT NULL,
                        creator TEXT NOT NULL,
                        callee TEXT NOT NULL,
                        media_type TEXT NOT NULL,
                        scheduled_at TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
        }
        ensureDefaultChats();
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

    private void ensureChatColumn(Statement statement, String name, String definition) throws SQLException {
        try (ResultSet columns = statement.executeQuery("SELECT * FROM chats WHERE 1 = 0")) {
            for (int i = 1; i <= columns.getMetaData().getColumnCount(); i++) {
                if (name.equalsIgnoreCase(columns.getMetaData().getColumnName(i))) {
                    return;
                }
            }
        }
        statement.executeUpdate("ALTER TABLE chats ADD COLUMN " + name + " " + definition);
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
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT c.*, (SELECT COUNT(*) FROM messages m WHERE m.chat_id = c.id) AS message_count, " +
                        "(SELECT COUNT(*) FROM chat_members cm_online JOIN users u ON u.username = cm_online.username " +
                        "WHERE cm_online.chat_id = c.id AND u.status = 'APPROVED' AND u.presence = 'online') AS online_count " +
                        "FROM chats c ORDER BY c.type, c.name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                chats.add(new ChatSummary(result.getString("id"), result.getString("type"),
                        result.getString("name"), result.getString("color"), result.getInt("members"),
                        result.getInt("online_count"), result.getInt("message_count")));
            }
        }
        return chats;
    }

    public synchronized List<ChatSummary> findChatsForUser(UserAccount user) throws SQLException {
        List<ChatSummary> chats = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT c.*, (SELECT COUNT(*) FROM chat_members cm2 WHERE cm2.chat_id = c.id) AS member_count, " +
                        "(SELECT COUNT(*) FROM chat_members cm_online JOIN users u ON u.username = cm_online.username " +
                        "WHERE cm_online.chat_id = c.id AND u.status = 'APPROVED' AND u.presence = 'online') AS online_count, " +
                        "(SELECT COUNT(*) FROM messages m WHERE m.chat_id = c.id) AS message_count " +
                        "FROM chats c JOIN chat_members cm ON cm.chat_id = c.id AND cm.username = ? " +
                        "ORDER BY c.type, c.name")) {
            statement.setString(1, user.username());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    boolean writable = canWrite(result.getString("write_permissions"), user.permissions());
                    chats.add(new ChatSummary(result.getString("id"), result.getString("type"),
                            result.getString("name"), result.getString("color"), result.getInt("member_count"),
                            result.getInt("online_count"), result.getInt("message_count"), writable));
                }
            }
        }
        return chats;
    }

    public synchronized ChatDefinition createChat(String id, String type, String name, String color,
                                                   String professionRule, String workplaceRule,
                                                   String writePermissions) throws SQLException {
        return createChat(id, type, name, color, professionRule, workplaceRule, "", writePermissions);
    }

    public synchronized ChatDefinition createChat(String id, String type, String name, String color,
                                                   String professionRule, String workplaceRule,
                                                   String regionRule, String writePermissions) throws SQLException {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{2,64}") || name == null || name.isBlank()
                || type == null || type.isBlank() || color == null || color.isBlank()) {
            throw new IllegalArgumentException("Укажите id, тип, название и цвет чата");
        }
        String normalizedPermissions = normalizePermissions(writePermissions);
        String sql = postgres
                ? "INSERT INTO chats(id, type, name, color, profession_rule, workplace_rule, region_rule, write_permissions) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name, " +
                "color = EXCLUDED.color, profession_rule = EXCLUDED.profession_rule, workplace_rule = EXCLUDED.workplace_rule, " +
                "region_rule = EXCLUDED.region_rule, write_permissions = EXCLUDED.write_permissions"
                : "INSERT OR REPLACE INTO chats(id, type, name, color, profession_rule, workplace_rule, region_rule, write_permissions) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = conn().prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, type.trim());
            statement.setString(3, name.trim());
            statement.setString(4, color.trim());
            statement.setString(5, professionRule == null ? "" : professionRule.trim());
            statement.setString(6, workplaceRule == null ? "" : workplaceRule.trim());
            statement.setString(7, regionRule == null ? "" : regionRule.trim());
            statement.setString(8, normalizedPermissions);
            statement.executeUpdate();
        }
        assignUsersToChat(id);
        return new ChatDefinition(id, type.trim(), name.trim(), color.trim(),
                professionRule == null ? "" : professionRule.trim(),
                workplaceRule == null ? "" : workplaceRule.trim(),
                regionRule == null ? "" : regionRule.trim(), normalizedPermissions);
    }

    private void ensureDefaultChats() throws SQLException {
        insertDefaultChat("general", "group", "📢 Главный штаб (Avengers Games)", "c-blue", "", "", "", "global_write");
        insertDefaultChat("gamedev-all", "group", "🎮 Разработка игр (All Devs)", "c-green", "",
                "Разработка движка,Разработка интеграций и портирования,Геймдизайн,Арт и анимация,Нарратив и сценарий,Звук,Локализация и адаптация,QA,Управление проектами", "", "");
        insertDefaultChat("leaders", "group", "⭐ Совет руководителей", "c-orange", "", "Высшее руководство", "", "");
        insertDefaultChat("dept-engine", "group", "⚙️ Разработка движка", "c-purple", "", "Разработка движка", "", "");
        insertDefaultChat("dept-integrations", "group", "🔌 Интеграции и портирование", "c-blue", "", "Разработка интеграций и портирования", "", "");
        insertDefaultChat("dept-gamedesign", "group", "🎲 Геймдизайн", "c-green", "", "Геймдизайн", "", "");
        insertDefaultChat("dept-art", "group", "🎨 Арт и анимация", "c-pink", "", "Арт и анимация", "", "");
        insertDefaultChat("dept-narrative", "group", "📜 Нарратив и сценарий", "c-orange", "", "Нарратив и сценарий", "", "");
        insertDefaultChat("dept-audio", "group", "🎧 Звук", "c-purple", "", "Звук", "", "");
        insertDefaultChat("dept-localization", "group", "🌐 Локализация и адаптация", "c-blue", "", "Локализация и адаптация", "", "");
        insertDefaultChat("dept-support", "group", "🛠️ Отдел техподдержки", "c-orange", "", "Техподдержка", "", "");
        insertDefaultChat("dept-security", "group", "🛡️ Кибербезопасность", "c-green", "", "Кибербезопасность", "", "");
        insertDefaultChat("dept-hardware", "group", "🖥️ Оборудование и закупки", "c-blue", "", "Оборудование и закупки", "", "");
        insertDefaultChat("dept-qa", "group", "🎯 Отдел тестирования (QA)", "c-pink", "", "QA", "", "");
        insertDefaultChat("dept-hr", "group", "👥 Отдел кадров (HR)", "c-green", "", "HR", "", "");
        insertDefaultChat("dept-projects", "group", "📊 Управление проектами", "c-orange", "", "Управление проектами", "", "");
        insertDefaultChat("helpdesk", "support", "🆘 Служба техподдержки", "c-orange", "", "", "", "");
        insertDefaultChat("anonymous", "group", "🎭 Анонимный чат", "c-purple", "", "", "", "");
        insertDefaultChat("marsel-cat", "direct", "🐱 Кот Марсель", "c-orange", "", "", "", "");
        insertDefaultChat("region-almaty", "group", "🏙️ Филиал Алматы", "c-blue", "", "", "Алматы", "");
        insertDefaultChat("region-astana", "group", "🏛️ Филиал Астана", "c-blue", "", "", "Астана", "");
        insertDefaultChat("region-talgar", "group", "🏔️ Филиал Талгар", "c-green", "", "", "Талгар", "");
    }

    private void insertDefaultChat(String id, String type, String name, String color,
                                   String professionRule, String workplaceRule,
                                   String regionRule, String writePermissions) throws SQLException {
        if (!chatExists(id)) {
            try (PreparedStatement stmt = conn().prepareStatement(
                    "INSERT INTO chats(id, type, name, color, profession_rule, workplace_rule, region_rule, write_permissions) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                stmt.setString(1, id);
                stmt.setString(2, type);
                stmt.setString(3, name);
                stmt.setString(4, color);
                stmt.setString(5, professionRule);
                stmt.setString(6, workplaceRule);
                stmt.setString(7, regionRule);
                stmt.setString(8, writePermissions);
                stmt.executeUpdate();
            }
        }
        assignUsersToChat(id);
    }

    private void assignUsersToChat(String chatId) throws SQLException {
        String sql = "SELECT username FROM users WHERE status = 'APPROVED' AND username NOT IN " +
                "(SELECT username FROM chat_members WHERE chat_id = ?)";
        try (PreparedStatement users = conn().prepareStatement(sql)) {
            users.setString(1, chatId);
            try (ResultSet result = users.executeQuery()) {
                while (result.next()) {
                    assignChatsForUser(result.getString("username"));
                }
            }
        }
    }

    static boolean matchesRule(String rule, String userValue) {
        if (rule == null || rule.isBlank()) {
            return true;
        }
        if (userValue == null || userValue.isBlank()) {
            return false;
        }
        String normalizedUser = userValue.trim().toLowerCase(Locale.ROOT);
        for (String part : rule.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && trimmed.equals(normalizedUser)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void assignChatsForUser(String username) throws SQLException {
        try (PreparedStatement user = conn().prepareStatement(
                "SELECT profession, workplace, region, permissions, status FROM users WHERE username = ?")) {
            user.setString(1, username);
            try (ResultSet userResult = user.executeQuery()) {
                if (!userResult.next() || !"APPROVED".equals(userResult.getString("status"))) {
                    return;
                }
                String userProf = userResult.getString("profession");
                String userWork = userResult.getString("workplace");
                String userReg = userResult.getString("region");
                String userPerms = userResult.getString("permissions");
                Set<String> permsSet = (userPerms == null || userPerms.isBlank())
                        ? Set.of()
                        : new HashSet<>(Arrays.asList(userPerms.split(",")));

                try (Statement stmt = conn().createStatement();
                     ResultSet chatResult = stmt.executeQuery(
                             "SELECT id, profession_rule, workplace_rule, region_rule FROM chats")) {
                    while (chatResult.next()) {
                        String chatId = chatResult.getString("id");
                        String profRule = chatResult.getString("profession_rule");
                        String workRule = chatResult.getString("workplace_rule");
                        String regRule = chatResult.getString("region_rule");

                        boolean isLeaderChat = "leaders".equals(chatId);
                        boolean matchesLeader = isLeaderChat && (permsSet.contains("ceo") || permsSet.contains("department_lead"));

                        if (matchesLeader || (matchesRule(profRule, userProf) && matchesRule(workRule, userWork) && matchesRule(regRule, userReg))) {
                            insertMembership(chatId, username);
                        }
                    }
                }
            }
        }
    }

    private void insertMembership(String chatId, String username) throws SQLException {
        try (PreparedStatement statement = conn().prepareStatement(
                postgres ? "INSERT INTO chat_members(chat_id, username) VALUES (?, ?) ON CONFLICT DO NOTHING"
                        : "INSERT OR IGNORE INTO chat_members(chat_id, username) VALUES (?, ?)")) {
            statement.setString(1, chatId);
            statement.setString(2, username);
            statement.executeUpdate();
        }
    }

    public synchronized List<ChatDefinition> adminChats() throws SQLException {
        List<ChatDefinition> chats = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT id, type, name, color, profession_rule, workplace_rule, region_rule, write_permissions " +
                        "FROM chats ORDER BY name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                chats.add(new ChatDefinition(result.getString("id"), result.getString("type"),
                        result.getString("name"), result.getString("color"),
                        result.getString("profession_rule"), result.getString("workplace_rule"),
                        result.getString("region_rule"), result.getString("write_permissions")));
            }
        }
        return chats;
    }

    public synchronized List<AdminUser> adminUsers() throws SQLException {
        List<AdminUser> users = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT username, display_name, profession, workplace, region, permissions, status, about, presence, last_seen " +
                        "FROM users ORDER BY display_name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String lastSeen = result.getString("last_seen");
                users.add(new AdminUser(result.getString("username"), result.getString("display_name"),
                        result.getString("profession"), result.getString("workplace"), result.getString("region"),
                        result.getString("permissions"), result.getString("status"), result.getString("about"),
                        result.getString("presence"), lastSeen == null || lastSeen.isBlank() ? null : Instant.parse(lastSeen)));
            }
        }
        return users;
    }

    public synchronized AdminUser updateUserAccess(String username, String status, String permissions)
            throws SQLException {
        return updateUserAccess(username, status, permissions, null);
    }

    public synchronized AdminUser updateUserAccess(String username, String status, String permissions,
                                                    String temporaryPassword) throws SQLException {
        if (!Set.of("APPROVED", "SUSPENDED").contains(status)) {
            throw new IllegalArgumentException("User status must be APPROVED or SUSPENDED");
        }
        String normalizedPermissions = normalizePermissions(permissions);
        String normalizedUsername = username == null ? "" : username.toLowerCase(Locale.ROOT);
        if (temporaryPassword != null && !temporaryPassword.isBlank()
                && (temporaryPassword.length() < 8 || temporaryPassword.length() > 128)) {
            throw new IllegalArgumentException("Temporary password must contain 8..128 characters");
        }
        String sql = temporaryPassword == null || temporaryPassword.isBlank()
                ? "UPDATE users SET status = ?, permissions = ? WHERE username = ?"
                : "UPDATE users SET status = ?, permissions = ?, password_salt = ?, password_hash = ? WHERE username = ?";
        try (PreparedStatement statement = conn().prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, normalizedPermissions);
            int usernameIndex = 3;
            if (temporaryPassword != null && !temporaryPassword.isBlank()) {
                PasswordHasher.PasswordHash passwordHash = PasswordHasher.hash(temporaryPassword.toCharArray());
                statement.setString(3, passwordHash.salt());
                statement.setString(4, passwordHash.hash());
                usernameIndex = 5;
            }
            statement.setString(usernameIndex, normalizedUsername);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("User not found");
            }
        }
        reconcileMembershipsForUser(normalizedUsername, status);
        for (AdminUser user : adminUsers()) {
            if (user.username().equals(normalizedUsername)) {
                return user;
            }
        }
        throw new IllegalArgumentException("User not found");
    }

    private boolean isMember(String chatId, String username) throws SQLException {
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT 1 FROM chat_members WHERE chat_id = ? AND username = ?")) {
            statement.setString(1, chatId);
            statement.setString(2, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public synchronized boolean isChatMember(String chatId, String username) throws SQLException {
        return chatExists(chatId) && username != null && !username.isBlank()
                && isMember(chatId, username);
    }

    public synchronized String findChatPeer(String chatId, String username) throws SQLException {
        if (!isChatMember(chatId, username)) {
            throw new IllegalArgumentException("Chat is not available");
        }
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT username FROM chat_members WHERE chat_id = ? AND username <> ? ORDER BY username LIMIT 1")) {
            statement.setString(1, chatId);
            statement.setString(2, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("username") : "";
            }
        }
    }

    public synchronized Attachment createAttachment(String chatId, String uploader, String originalName,
                                                     String storedName, String contentType, long size)
            throws SQLException {
        if (!isMember(chatId, uploader)) {
            throw new IllegalArgumentException("Chat is not available");
        }
        if (originalName == null || originalName.isBlank() || storedName == null || storedName.isBlank()
                || contentType == null || contentType.isBlank() || size < 0) {
            throw new IllegalArgumentException("Invalid attachment metadata");
        }
        String id = java.util.UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        try (PreparedStatement statement = conn().prepareStatement(
                "INSERT INTO attachments(id, chat_id, uploader, original_name, stored_name, content_type, size, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, chatId);
            statement.setString(3, uploader);
            statement.setString(4, originalName);
            statement.setString(5, storedName);
            statement.setString(6, contentType);
            statement.setLong(7, size);
            statement.setString(8, createdAt.toString());
            statement.executeUpdate();
        }
        return new Attachment(id, chatId, uploader, originalName, storedName, contentType, size, createdAt);
    }

    public synchronized Attachment findAttachment(String id) throws SQLException {
        if (id == null || id.isBlank()) {
            return null;
        }
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT id, chat_id, uploader, original_name, stored_name, content_type, size, created_at " +
                        "FROM attachments WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new Attachment(result.getString("id"), result.getString("chat_id"),
                        result.getString("uploader"), result.getString("original_name"),
                        result.getString("stored_name"), result.getString("content_type"),
                        result.getLong("size"), Instant.parse(result.getString("created_at")));
            }
        }
    }

    public synchronized Call createCall(String chatId, String caller, String callee, String mediaType)
            throws SQLException {
        if (!isMember(chatId, caller) || !isMember(chatId, callee)) {
            throw new IllegalArgumentException("Both call participants must belong to the chat");
        }
        if (!"audio".equals(mediaType) && !"video".equals(mediaType)) {
            throw new IllegalArgumentException("Media type must be audio or video");
        }
        if (caller.equals(callee)) {
            throw new IllegalArgumentException("You cannot call yourself");
        }
        String id = java.util.UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        try (PreparedStatement statement = conn().prepareStatement(
                "INSERT INTO calls(id, chat_id, caller, callee, media_type, status, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'RINGING', ?)")) {
            statement.setString(1, id);
            statement.setString(2, chatId);
            statement.setString(3, caller);
            statement.setString(4, callee);
            statement.setString(5, mediaType);
            statement.setString(6, createdAt.toString());
            statement.executeUpdate();
        }
        return new Call(id, chatId, caller, callee, mediaType, "RINGING", createdAt, null);
    }

    public synchronized Call findCall(String id) throws SQLException {
        if (id == null || id.isBlank()) {
            return null;
        }
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT id, chat_id, caller, callee, media_type, status, created_at, ended_at FROM calls WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                String endedAt = result.getString("ended_at");
                return new Call(result.getString("id"), result.getString("chat_id"),
                        result.getString("caller"), result.getString("callee"),
                        result.getString("media_type"), result.getString("status"),
                        Instant.parse(result.getString("created_at")),
                        endedAt == null || endedAt.isBlank() ? null : Instant.parse(endedAt));
            }
        }
    }

    public synchronized List<Call> findIncomingCalls(String username) throws SQLException {
        expireStaleCalls();
        List<Call> calls = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT id, chat_id, caller, callee, media_type, status, created_at, ended_at " +
                        "FROM calls WHERE callee = ? AND status = 'RINGING' ORDER BY created_at")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    calls.add(callFrom(result));
                }
            }
        }
        return calls;
    }

    public synchronized List<Call> findCallHistory(String username, int limit) throws SQLException {
        expireStaleCalls();
        List<Call> calls = new ArrayList<>();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT id, chat_id, caller, callee, media_type, status, created_at, ended_at " +
                        "FROM calls WHERE caller = ? OR callee = ? ORDER BY created_at DESC LIMIT ?")) {
            statement.setString(1, username);
            statement.setString(2, username);
            statement.setInt(3, safeLimit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    calls.add(callFrom(result));
                }
            }
        }
        return calls;
    }

    public synchronized ScheduledCall scheduleCall(String chatId, String creator, String callee,
                                                    String mediaType, Instant scheduledAt) throws SQLException {
        if (!isMember(chatId, creator) || !isMember(chatId, callee)) {
            throw new IllegalArgumentException("Both call participants must belong to the chat");
        }
        if (creator.equals(callee)) {
            throw new IllegalArgumentException("You cannot schedule a call with yourself");
        }
        if (!"audio".equals(mediaType) && !"video".equals(mediaType)) {
            throw new IllegalArgumentException("Media type must be audio or video");
        }
        if (scheduledAt == null || scheduledAt.isBefore(Instant.now())) {
            throw new IllegalArgumentException("Scheduled time must be in the future");
        }
        String id = java.util.UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        try (PreparedStatement statement = conn().prepareStatement(
                "INSERT INTO scheduled_calls(id, chat_id, creator, callee, media_type, scheduled_at, status, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'SCHEDULED', ?)")) {
            statement.setString(1, id);
            statement.setString(2, chatId);
            statement.setString(3, creator);
            statement.setString(4, callee);
            statement.setString(5, mediaType);
            statement.setString(6, scheduledAt.toString());
            statement.setString(7, createdAt.toString());
            statement.executeUpdate();
        }
        return new ScheduledCall(id, chatId, creator, callee, mediaType, scheduledAt, "SCHEDULED", createdAt);
    }

    public synchronized List<ScheduledCall> findScheduledCalls(String username) throws SQLException {
        List<ScheduledCall> calls = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT id, chat_id, creator, callee, media_type, scheduled_at, status, created_at " +
                        "FROM scheduled_calls WHERE (creator = ? OR callee = ?) AND status = 'SCHEDULED' " +
                        "ORDER BY scheduled_at")) {
            statement.setString(1, username);
            statement.setString(2, username);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    calls.add(scheduledCallFrom(result));
                }
            }
        }
        return calls;
    }

    public synchronized List<ScheduledCall> claimDueScheduledCalls(Instant now) throws SQLException {
        List<ScheduledCall> due = new ArrayList<>();
        List<ScheduledCall> candidates = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT id, chat_id, creator, callee, media_type, scheduled_at, status, created_at "
                        + "FROM scheduled_calls WHERE status = 'SCHEDULED' AND scheduled_at <= ? ORDER BY scheduled_at")) {
            statement.setString(1, now.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    candidates.add(scheduledCallFrom(result));
                }
            }
        }
        try (PreparedStatement claim = conn().prepareStatement(
                "UPDATE scheduled_calls SET status = 'READY' WHERE id = ? AND status = 'SCHEDULED'")) {
            for (ScheduledCall call : candidates) {
                claim.setString(1, call.id());
                if (claim.executeUpdate() == 1) {
                    due.add(new ScheduledCall(call.id(), call.chatId(), call.creator(), call.callee(),
                            call.mediaType(), call.scheduledAt(), "READY", call.createdAt()));
                }
            }
        }
        return due;
    }

    public synchronized ScheduledCall cancelScheduledCall(String id, String username) throws SQLException {
        ScheduledCall call = findScheduledCall(id);
        if (call == null || (!username.equals(call.creator()) && !username.equals(call.callee()))) {
            throw new IllegalArgumentException("Scheduled call is not available");
        }
        try (PreparedStatement statement = conn().prepareStatement(
                "UPDATE scheduled_calls SET status = 'CANCELLED' WHERE id = ? AND status = 'SCHEDULED'")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
        return new ScheduledCall(call.id(), call.chatId(), call.creator(), call.callee(), call.mediaType(),
                call.scheduledAt(), "CANCELLED", call.createdAt());
    }

    private ScheduledCall findScheduledCall(String id) throws SQLException {
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT id, chat_id, creator, callee, media_type, scheduled_at, status, created_at " +
                        "FROM scheduled_calls WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? scheduledCallFrom(result) : null;
            }
        }
    }

    private static ScheduledCall scheduledCallFrom(ResultSet result) throws SQLException {
        return new ScheduledCall(result.getString("id"), result.getString("chat_id"),
                result.getString("creator"), result.getString("callee"), result.getString("media_type"),
                Instant.parse(result.getString("scheduled_at")), result.getString("status"),
                Instant.parse(result.getString("created_at")));
    }

    public synchronized UserProfile findProfile(String username) throws SQLException {
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT username, display_name, profession, workplace, region, phone, permissions, about, presence, last_seen " +
                        "FROM users WHERE username = ? AND status = 'APPROVED'")) {
            statement.setString(1, username == null ? "" : username.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                String lastSeen = result.getString("last_seen");
                return new UserProfile(result.getString("username"), result.getString("display_name"),
                        result.getString("profession"), result.getString("workplace"), result.getString("region"),
                        result.getString("phone"), result.getString("permissions"), result.getString("about"),
                        result.getString("presence"), lastSeen == null || lastSeen.isBlank() ? null : Instant.parse(lastSeen));
            }
        }
    }

    public synchronized UserProfile updateProfile(String username, String about, String presence) throws SQLException {
        if (about == null || about.length() > 2000) {
            throw new IllegalArgumentException("About text must be at most 2000 characters");
        }
        validatePresence(presence);
        try (PreparedStatement statement = conn().prepareStatement(
                "UPDATE users SET about = ?, presence = ?, last_seen = ? WHERE username = ? AND status = 'APPROVED'")) {
            String now = Instant.now().toString();
            statement.setString(1, about.trim());
            statement.setString(2, presence);
            statement.setString(3, now);
            statement.setString(4, username);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Approved user not found");
            }
        }
        return findProfile(username);
    }

    public synchronized void updatePresence(String username, String presence) throws SQLException {
        validatePresence(presence);
        try (PreparedStatement statement = conn().prepareStatement(
                "UPDATE users SET presence = ?, last_seen = ? WHERE username = ? AND status = 'APPROVED'")) {
            statement.setString(1, presence);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, username);
            statement.executeUpdate();
        }
    }

    private static void validatePresence(String presence) {
        if (!Set.of("online", "offline", "away", "busy", "dnd").contains(presence)) {
            throw new IllegalArgumentException("Unsupported presence status");
        }
    }

    public synchronized Notification createNotification(String recipient, String title, String body,
                                                         String severity, String createdBy, Instant expiresAt)
            throws SQLException {
        if (title == null || title.isBlank() || title.length() > 160
                || body == null || body.isBlank() || body.length() > 4000) {
            throw new IllegalArgumentException("Notification title/body is invalid");
        }
        if (!Set.of("info", "warning", "emergency").contains(severity)) {
            throw new IllegalArgumentException("Unsupported notification severity");
        }
        String id = java.util.UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        try (PreparedStatement statement = conn().prepareStatement(
                "INSERT INTO notifications(id, recipient_username, title, body, severity, created_by, created_at, expires_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, recipient == null ? "" : recipient.trim().toLowerCase(Locale.ROOT));
            statement.setString(3, title.trim());
            statement.setString(4, body.trim());
            statement.setString(5, severity);
            statement.setString(6, createdBy);
            statement.setString(7, createdAt.toString());
            statement.setString(8, expiresAt == null ? "" : expiresAt.toString());
            statement.executeUpdate();
        }
        return new Notification(id, recipient == null ? "" : recipient.trim().toLowerCase(Locale.ROOT),
                title.trim(), body.trim(), severity, createdBy, createdAt, expiresAt, false);
    }

    public synchronized List<Notification> findNotifications(String username) throws SQLException {
        List<Notification> notifications = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT n.id, n.recipient_username, n.title, n.body, n.severity, n.created_by, n.created_at, n.expires_at, " +
                        "CASE WHEN r.username IS NULL THEN 0 ELSE 1 END AS is_read " +
                        "FROM notifications n LEFT JOIN notification_reads r ON r.notification_id = n.id AND r.username = ? " +
                        "WHERE (n.recipient_username = '' OR n.recipient_username = ?) " +
                        "AND (n.expires_at = '' OR n.expires_at > ?) ORDER BY n.created_at DESC")) {
            String now = Instant.now().toString();
            statement.setString(1, username);
            statement.setString(2, username);
            statement.setString(3, now);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String expiresAt = result.getString("expires_at");
                    notifications.add(new Notification(result.getString("id"), result.getString("recipient_username"),
                            result.getString("title"), result.getString("body"), result.getString("severity"),
                            result.getString("created_by"), Instant.parse(result.getString("created_at")),
                            expiresAt == null || expiresAt.isBlank() ? null : Instant.parse(expiresAt),
                            result.getBoolean("is_read")));
                }
            }
        }
        return notifications;
    }

    public synchronized void markNotificationRead(String id, String username) throws SQLException {
        try (PreparedStatement statement = conn().prepareStatement(
                postgres ? "INSERT INTO notification_reads(notification_id, username, read_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
                        : "INSERT OR IGNORE INTO notification_reads(notification_id, username, read_at) VALUES (?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, username);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private static Call callFrom(ResultSet result) throws SQLException {
        String endedAt = result.getString("ended_at");
        return new Call(result.getString("id"), result.getString("chat_id"),
                result.getString("caller"), result.getString("callee"),
                result.getString("media_type"), result.getString("status"),
                Instant.parse(result.getString("created_at")),
                endedAt == null || endedAt.isBlank() ? null : Instant.parse(endedAt));
    }

    public synchronized CallEvent addCallEvent(String callId, String sender, String eventType, String payload)
            throws SQLException {
        expireStaleCalls();
        Call call = findCall(callId);
        if (call == null || (!sender.equals(call.caller()) && !sender.equals(call.callee()))) {
            throw new IllegalArgumentException("Call is not available");
        }
        if (Set.of("MISSED", "REJECTED", "ENDED").contains(call.status())) {
            throw new IllegalArgumentException("Call is no longer active");
        }
        if (!Set.of("offer", "answer", "candidate", "accept", "reject", "end").contains(eventType)) {
            throw new IllegalArgumentException("Unsupported call event");
        }
        if (payload == null || payload.length() > 100_000) {
            throw new IllegalArgumentException("Call event payload is too large");
        }
        Instant createdAt = Instant.now();
        long eventId = 0;
        try (PreparedStatement statement = conn().prepareStatement(
                "INSERT INTO call_events(call_id, sender, event_type, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, callId);
            statement.setString(2, sender);
            statement.setString(3, eventType);
            statement.setString(4, payload == null ? "" : payload);
            statement.setString(5, createdAt.toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    eventId = keys.getLong(1);
                }
            }
        }
        String status = switch (eventType) {
            case "accept", "offer", "answer", "candidate" -> "ACTIVE";
            case "reject" -> "REJECTED";
            case "end" -> "ENDED";
            default -> call.status();
        };
        if (!status.equals(call.status())) {
            try (PreparedStatement statement = conn().prepareStatement(
                    "UPDATE calls SET status = ?, ended_at = ? WHERE id = ?")) {
                statement.setString(1, status);
                statement.setString(2, "ENDED".equals(status) || "REJECTED".equals(status)
                        ? createdAt.toString() : "");
                statement.setString(3, callId);
                statement.executeUpdate();
            }
        }
        return new CallEvent(eventId, callId, sender, eventType, payload == null ? "" : payload, createdAt);
    }

    public synchronized void expireStaleCalls() throws SQLException {
        expireStaleCallsAt(Instant.now());
    }

    void expireStaleCallsAt(Instant now) throws SQLException {
        Instant cutoff = now.minus(2, ChronoUnit.MINUTES);
        try (PreparedStatement statement = conn().prepareStatement(
                "UPDATE calls SET status = 'MISSED', ended_at = ? WHERE status = 'RINGING' AND created_at < ?")) {
            statement.setString(1, now.toString());
            statement.setString(2, cutoff.toString());
            statement.executeUpdate();
        }
    }

    public synchronized List<CallEvent> findCallEvents(String callId, String username, long afterId)
            throws SQLException {
        Call call = findCall(callId);
        if (call == null || (!username.equals(call.caller()) && !username.equals(call.callee()))) {
            throw new IllegalArgumentException("Call is not available");
        }
        List<CallEvent> events = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT id, call_id, sender, event_type, payload, created_at FROM call_events " +
                        "WHERE call_id = ? AND id > ? ORDER BY id")) {
            statement.setString(1, callId);
            statement.setLong(2, Math.max(0, afterId));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new CallEvent(result.getLong("id"), result.getString("call_id"),
                            result.getString("sender"), result.getString("event_type"),
                            result.getString("payload"), Instant.parse(result.getString("created_at"))));
                }
            }
        }
        return events;
    }

    public synchronized List<Message> findMessages(String chatId, String username) throws SQLException {
        if (!chatExists(chatId) || !isMember(chatId, username)) {
            throw new IllegalArgumentException("Chat is not available");
        }
        boolean isAnonymous = "anonymous".equals(chatId);
        // Mark existing messages as read by this user
        if (username != null && !username.isBlank() && !isAnonymous) {
            String insertReadsSql = postgres
                    ? "INSERT INTO message_reads(message_id, username, read_at) " +
                      "SELECT id, ?, ? FROM messages WHERE chat_id = ? " +
                      "ON CONFLICT DO NOTHING"
                    : "INSERT OR IGNORE INTO message_reads(message_id, username, read_at) " +
                      "SELECT id, ?, ? FROM messages WHERE chat_id = ?";
            try (PreparedStatement readStmt = conn().prepareStatement(insertReadsSql)) {
                readStmt.setString(1, username);
                readStmt.setString(2, Instant.now().toString());
                readStmt.setString(3, chatId);
                readStmt.executeUpdate();
            }
        }

        // Build mapping of message_id -> seenBy readers
        Map<Long, List<String>> readsMap = new HashMap<>();
        if (!isAnonymous) {
            try (PreparedStatement stmt = conn().prepareStatement(
                    "SELECT mr.message_id, mr.username, COALESCE(u.display_name, mr.username) AS reader_name, " +
                            "COALESCE(u.profession, '') AS reader_role " +
                            "FROM message_reads mr " +
                            "JOIN messages m ON m.id = mr.message_id " +
                            "LEFT JOIN users u ON u.username = mr.username " +
                            "WHERE m.chat_id = ? " +
                            "ORDER BY mr.read_at")) {
                stmt.setString(1, chatId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        long msgId = rs.getLong("message_id");
                        String rName = rs.getString("reader_name");
                        String rRole = rs.getString("reader_role");
                        String label = rRole.isBlank() ? rName : rName + " (" + rRole + ")";
                        readsMap.computeIfAbsent(msgId, k -> new ArrayList<>()).add(label);
                    }
                }
            }
        }

        List<Message> messages = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT m.id, m.sender, m.text, m.sent_at, " +
                        "COALESCE(u.display_name, m.sender) AS display_name, " +
                        "COALESCE(u.profession, '') AS profession " +
                        "FROM messages m " +
                        "LEFT JOIN users u ON u.username = m.sender " +
                        "WHERE m.chat_id = ? ORDER BY m.id")) {
            statement.setString(1, chatId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long id = result.getLong("id");
                    String sender = result.getString("sender");
                    String displayName = result.getString("display_name");
                    String role = result.getString("profession");
                    if (isAnonymous) {
                        sender = "Аноним";
                        displayName = "Аноним";
                        role = "Сотрудник";
                    } else if ("Марсель 🐾".equals(sender)) {
                        displayName = "Марсель 🐾";
                        role = "Главный по спокойствию";
                    } else if ("AI Helpdesk Bot".equals(sender)) {
                        displayName = "Бот техподдержки 🤖";
                        role = "Автоматическая служба помощи";
                    }
                    List<String> seenBy = readsMap.getOrDefault(id, List.of());
                    messages.add(new Message(id, sender, displayName, role, result.getString("text"),
                            Instant.parse(result.getString("sent_at")), seenBy));
                }
            }
        }
        return messages;
    }

    public synchronized Message addMessage(String chatId, String sender, String permissions, String text)
            throws SQLException {
        boolean anonymousChat = "anonymous".equals(chatId);
        boolean isSystemSender = "Марсель 🐾".equals(sender) || "AI Helpdesk Bot".equals(sender)
                || "Бот техподдержки".equals(sender) || "Система".equals(sender);
        if (!chatExists(chatId) || (!isSystemSender && !isMember(chatId, sender))) {
            throw new IllegalArgumentException("Chat is not available");
        }
        if (sender == null || sender.isBlank() || text == null || text.isBlank() || text.length() > 4000) {
            throw new IllegalArgumentException("Sender and message text are required; text must be 1..4000 characters");
        }
        try (PreparedStatement access = conn().prepareStatement(
                "SELECT write_permissions FROM chats WHERE id = ?")) {
            access.setString(1, chatId);
            try (ResultSet result = access.executeQuery()) {
                if (result.next() && !canWrite(result.getString("write_permissions"), permissions)) {
                    throw new IllegalArgumentException("У вас нет права писать в этот чат");
                }
            }
        }
        Instant sentAt = Instant.now();
        long generatedId = 0;
        try (PreparedStatement statement = conn().prepareStatement(
                "INSERT INTO messages(chat_id, sender, text, sent_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, chatId);
            statement.setString(2, anonymousChat ? "anonymous" : sender);
            statement.setString(3, text);
            statement.setString(4, sentAt.toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    generatedId = keys.getLong(1);
                }
            }
        }
        String visibleSender = anonymousChat ? "anonymous" : sender;
        String displayName = anonymousChat ? "Аноним" : sender;
        String role = anonymousChat ? "Сотрудник" : "";
        if (anonymousChat) {
            return new Message(generatedId, visibleSender, displayName, role, text, sentAt, List.of());
        }
        if ("Марсель 🐾".equals(sender)) {
            role = "Главный по спокойствию";
        } else if ("AI Helpdesk Bot".equals(sender)) {
            displayName = "Бот техподдержки 🤖";
            role = "Автоматическая служба помощи";
        } else {
            UserAccount account = findApprovedUser(sender);
            if (account != null) {
                displayName = account.displayName();
                role = account.profession();
            }
        }
        return new Message(generatedId, sender, displayName, role, text, sentAt, List.of());
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
        try (PreparedStatement statement = conn().prepareStatement(
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
        try (PreparedStatement statement = conn().prepareStatement(
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
        if ("APPROVED".equals(status) && normalizedPermissions.split(",").length > 0
                && List.of(normalizedPermissions.split(",")).contains("ceo")) {
            throw new IllegalArgumentException("Право CEO назначает только администратор");
        }
        Registration request = findRequest(requestId);
        if ("APPROVED".equals(status)) {
            if (username == null || !username.matches("[a-zA-Z0-9_]{3,32}")
                    || password == null || password.isBlank() || password.length() > 128) {
                throw new IllegalArgumentException("Регистратор должен указать логин и временный пароль");
            }
            if (fullName == null || fullName.isBlank() || profession == null || profession.isBlank()
                    || workplace == null || workplace.isBlank() || region == null || region.isBlank()
                    || phone == null || phone.isBlank()) {
                throw new IllegalArgumentException("Анкета сотрудника заполнена не полностью");
            }
            PasswordHasher.PasswordHash passwordHash = PasswordHasher.hash(password.toCharArray());
            try (PreparedStatement statement = conn().prepareStatement(
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
        try (PreparedStatement statement = conn().prepareStatement(
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
        try (PreparedStatement statement = conn().prepareStatement(
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

    public synchronized UserAccount authenticate(String username, String password) throws SQLException {
        if (username == null || password == null || username.isBlank() || password.isEmpty()) {
            throw new IllegalArgumentException("Неверный логин или пароль");
        }
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT username, display_name, profession, workplace, region, phone, permissions, " +
                        "password_salt, password_hash FROM users WHERE username = ? AND status = 'APPROVED'")) {
            statement.setString(1, username.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getString("password_hash").isBlank()
                        || !PasswordHasher.verify(password.toCharArray(), result.getString("password_salt"),
                        result.getString("password_hash"))) {
                    throw new IllegalArgumentException("Неверный логин или пароль");
                }
                return new UserAccount(result.getString("username"), result.getString("display_name"),
                        result.getString("profession"), result.getString("workplace"),
                        result.getString("region"), result.getString("phone"),
                        result.getString("permissions"));
            }
        }
    }

    static String normalizePermissions(String permissions) {
        if (permissions == null || permissions.isBlank()) {
            return "";
        }
        Set<String> selected = new HashSet<>();
        for (String permission : permissions.split(",")) {
            String value = permission.trim();
            if (!value.isEmpty() && !ALLOWED_PERMISSIONS.contains(value)) {
                throw new IllegalArgumentException("Unknown permission: " + value);
            }
            if (!value.isEmpty()) {
                selected.add(value);
            }
        }
        return String.join(",", selected.stream().sorted().toList());
    }

    private boolean chatExists(String chatId) throws SQLException {
        try (PreparedStatement statement = conn().prepareStatement("SELECT 1 FROM chats WHERE id = ?")) {
            statement.setString(1, chatId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean canWrite(String required, String actual) {
        if (required == null || required.isBlank()) {
            return true;
        }
        Set<String> actualPermissions = new HashSet<>(
                Arrays.asList((actual == null ? "" : actual).split(",")));
        return actualPermissions.contains("ceo")
                || Arrays.stream(required.split(",")).anyMatch(actualPermissions::contains);
    }

    public synchronized String createSession(String role, String username) throws SQLException {
        String token = java.util.UUID.randomUUID().toString();
        Instant now = Instant.now();
        try (PreparedStatement statement = conn().prepareStatement(
                "INSERT INTO sessions(token, role, username, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, token);
            statement.setString(2, role);
            statement.setString(3, username == null ? "" : username);
            statement.setString(4, now.toString());
            statement.setString(5, now.plus(SESSION_DAYS, ChronoUnit.DAYS).toString());
            statement.executeUpdate();
        }
        return token;
    }

    public synchronized Session findSession(String token) throws SQLException {
        if (token == null || token.isBlank()) {
            return null;
        }
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT token, role, username, expires_at FROM sessions WHERE token = ?")) {
            statement.setString(1, token);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Instant expiresAt = Instant.parse(result.getString("expires_at"));
                if (expiresAt.isBefore(Instant.now())) {
                    deleteSession(token);
                    return null;
                }
                return new Session(result.getString("token"), result.getString("role"),
                        result.getString("username"), expiresAt);
            }
        }
    }

    public synchronized void deleteSession(String token) throws SQLException {
        if (token == null || token.isBlank()) {
            return;
        }
        try (PreparedStatement statement = conn().prepareStatement("DELETE FROM sessions WHERE token = ?")) {
            statement.setString(1, token);
            statement.executeUpdate();
        }
    }

    public synchronized List<UserAccount> approvedUsers() throws SQLException {
        List<UserAccount> users = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT username, display_name, profession, workplace, region, phone, permissions " +
                        "FROM users WHERE status = 'APPROVED' ORDER BY display_name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                users.add(new UserAccount(result.getString("username"), result.getString("display_name"),
                        result.getString("profession"), result.getString("workplace"),
                        result.getString("region"), result.getString("phone"),
                        result.getString("permissions")));
            }
        }
        return users;
    }

    public synchronized void setUserPermissions(String username, String permissions) throws SQLException {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Укажите логин пользователя");
        }
        String normalizedPermissions = normalizePermissions(permissions);
        try (PreparedStatement statement = conn().prepareStatement(
                "UPDATE users SET permissions = ? WHERE username = ? AND status = 'APPROVED'")) {
            statement.setString(1, normalizedPermissions);
            statement.setString(2, username.toLowerCase(Locale.ROOT));
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Одобренный пользователь не найден");
            }
        }
        reconcileMembershipsForUser(username.toLowerCase(Locale.ROOT), "APPROVED");
    }

    private void reconcileMembershipsForUser(String username, String status) throws SQLException {
        String normalizedUsername = username == null ? "" : username.toLowerCase(Locale.ROOT);
        try (PreparedStatement statement = conn().prepareStatement(
                "DELETE FROM chat_members WHERE username = ? AND chat_id IN "
                        + "(SELECT id FROM chats WHERE type <> 'direct')")) {
            statement.setString(1, normalizedUsername);
            statement.executeUpdate();
        }
        if ("APPROVED".equals(status)) {
            assignChatsForUser(normalizedUsername);
            return;
        }
        try (PreparedStatement statement = conn().prepareStatement("DELETE FROM sessions WHERE username = ?")) {
            statement.setString(1, normalizedUsername);
            statement.executeUpdate();
        }
    }

    public synchronized ChatDefinition ensureDirectChat(UserAccount owner, String targetUsername)
            throws SQLException {
        if (owner == null) {
            throw new IllegalArgumentException("Войдите в систему");
        }
        UserAccount target = findApprovedUser(targetUsername);
        if (target == null) {
            throw new IllegalArgumentException("Сотрудник не найден");
        }
        if (owner.username().equals(target.username())) {
            throw new IllegalArgumentException("Нельзя открыть чат с собой");
        }
        if (!hasPermission(owner, "ceo") && hasPermission(target, "ceo")) {
            throw new IllegalArgumentException("Личный чат с CEO доступен только по приглашению CEO");
        }
        String id = "dm-" + stableId(owner.username() + "-" + target.username());
        try (PreparedStatement statement = conn().prepareStatement(
                "INSERT INTO chats(id, type, name, color, profession_rule, workplace_rule, region_rule, write_permissions) " +
                        "VALUES (?, 'direct', ?, '#3a7bd5', '', '', '', '') " +
                        (postgres ? "ON CONFLICT DO NOTHING" : ""))) {
            statement.setString(1, id);
            statement.setString(2, target.displayName());
            statement.executeUpdate();
        }
        insertMembership(id, owner.username());
        insertMembership(id, target.username());
        return new ChatDefinition(id, "direct", target.displayName(), "#3a7bd5", "", "", "");
    }

    public synchronized UserAccount findApprovedUser(String username) throws SQLException {
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT username, display_name, profession, workplace, region, phone, permissions " +
                        "FROM users WHERE username = ? AND status = 'APPROVED'")) {
            statement.setString(1, username == null ? "" : username.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new UserAccount(result.getString("username"), result.getString("display_name"),
                        result.getString("profession"), result.getString("workplace"),
                        result.getString("region"), result.getString("phone"),
                        result.getString("permissions"));
            }
        }
    }

    private static String stableId(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean hasPermission(UserAccount user, String permission) {
        return user != null && Arrays.asList(user.permissions().split(",")).contains(permission);
    }

    @Override
    public void close() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    public record ChatSummary(String id, String type, String name, String color,
                              int members, int online, int messageCount, boolean canWrite) {
        public ChatSummary(String id, String type, String name, String color,
                           int members, int online, int messageCount) {
            this(id, type, name, color, members, online, messageCount, true);
        }
    }

    public record ChatDefinition(String id, String type, String name, String color,
                                 String professionRule, String workplaceRule,
                                 String regionRule, String writePermissions) {
        public ChatDefinition(String id, String type, String name, String color,
                              String professionRule, String workplaceRule,
                              String writePermissions) {
            this(id, type, name, color, professionRule, workplaceRule, "", writePermissions);
        }
    }

    public record Registration(String id, String fullName, String profession, String workplace,
                               String region, String phone, String permissions, String status,
                               String accountUsername, Instant createdAt) {
    }

    public record UserAccount(String username, String displayName, String profession,
                              String workplace, String region, String phone, String permissions) {
    }

    public record AdminUser(String username, String displayName, String profession,
                            String workplace, String region, String permissions, String status,
                            String about, String presence, Instant lastSeen) {
    }

    public record Session(String token, String role, String username, Instant expiresAt) {
    }

    public record Attachment(String id, String chatId, String uploader, String originalName,
                             String storedName, String contentType, long size, Instant createdAt) {
    }

    public record Call(String id, String chatId, String caller, String callee, String mediaType,
                       String status, Instant createdAt, Instant endedAt) {
    }

    public record CallEvent(long id, String callId, String sender, String eventType,
                            String payload, Instant createdAt) {
    }

    public record ScheduledCall(String id, String chatId, String creator, String callee,
                                String mediaType, Instant scheduledAt, String status, Instant createdAt) {
    }

    public record UserProfile(String username, String displayName, String profession,
                              String workplace, String region, String phone, String permissions,
                              String about, String presence, Instant lastSeen) {
    }

    public record Notification(String id, String recipient, String title, String body,
                               String severity, String createdBy, Instant createdAt,
                               Instant expiresAt, boolean read) {
    }
}
