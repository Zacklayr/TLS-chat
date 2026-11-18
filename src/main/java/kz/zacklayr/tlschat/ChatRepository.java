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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ChatRepository implements AutoCloseable {
    private static final long SESSION_DAYS = 30;
    private static final Set<String> ALLOWED_PERMISSIONS = Set.of(
            "ceo", "department_lead", "global_write", "regional_write",
            "support_agent", "hidden_data", "manage_users", "manage_chats"
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

    public synchronized List<ChatSummary> findChatsForUser(UserAccount user) throws SQLException {
        List<ChatSummary> chats = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT c.*, (SELECT COUNT(*) FROM chat_members cm2 WHERE cm2.chat_id = c.id) AS member_count, " +
                        "(SELECT COUNT(*) FROM messages m WHERE m.chat_id = c.id) AS message_count " +
                        "FROM chats c JOIN chat_members cm ON cm.chat_id = c.id AND cm.username = ? " +
                        "ORDER BY c.type, c.name")) {
            statement.setString(1, user.username());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    chats.add(new ChatSummary(result.getString("id"), result.getString("type"),
                            result.getString("name"), result.getString("color"), result.getInt("member_count"),
                            result.getInt("online"), result.getInt("message_count")));
                }
            }
        }
        return chats;
    }

    public synchronized ChatDefinition createChat(String id, String type, String name, String color,
                                                   String professionRule, String workplaceRule,
                                                   String writePermissions) throws SQLException {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{2,64}") || name == null || name.isBlank()
                || type == null || type.isBlank() || color == null || color.isBlank()) {
            throw new IllegalArgumentException("Укажите id, тип, название и цвет чата");
        }
        String normalizedPermissions = normalizePermissions(writePermissions);
        try (PreparedStatement statement = conn().prepareStatement(
                "INSERT INTO chats(id, type, name, color, profession_rule, workplace_rule, region_rule, write_permissions) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, type.trim());
            statement.setString(3, name.trim());
            statement.setString(4, color.trim());
            statement.setString(5, professionRule == null ? "" : professionRule.trim());
            statement.setString(6, workplaceRule == null ? "" : workplaceRule.trim());
            statement.setString(7, "");
            statement.setString(8, normalizedPermissions);
            statement.executeUpdate();
        }
        assignUsersToChat(id);
        return new ChatDefinition(id, type.trim(), name.trim(), color.trim(),
                professionRule == null ? "" : professionRule.trim(),
                workplaceRule == null ? "" : workplaceRule.trim(), normalizedPermissions);
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

    public synchronized void assignChatsForUser(String username) throws SQLException {
        try (PreparedStatement user = conn().prepareStatement(
                "SELECT profession, workplace, region FROM users WHERE username = ?")) {
            user.setString(1, username);
            try (ResultSet userResult = user.executeQuery()) {
                if (!userResult.next()) {
                    return;
                }
                try (PreparedStatement chats = conn().prepareStatement(
                        "SELECT id FROM chats WHERE (profession_rule = '' OR profession_rule = ?) " +
                                "AND (workplace_rule = '' OR workplace_rule = ?) " +
                                "AND (region_rule = '' OR region_rule = ?)")) {
                    chats.setString(1, userResult.getString("profession"));
                    chats.setString(2, userResult.getString("workplace"));
                    chats.setString(3, userResult.getString("region"));
                    try (ResultSet chatResult = chats.executeQuery()) {
                        while (chatResult.next()) {
                            insertMembership(chatResult.getString("id"), username);
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
                "SELECT id, type, name, color, profession_rule, workplace_rule, write_permissions " +
                        "FROM chats ORDER BY name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                chats.add(new ChatDefinition(result.getString("id"), result.getString("type"),
                        result.getString("name"), result.getString("color"),
                        result.getString("profession_rule"), result.getString("workplace_rule"),
                        result.getString("write_permissions")));
            }
        }
        return chats;
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

    public synchronized List<Message> findMessages(String chatId, String username) throws SQLException {
        if (!chatExists(chatId) || !isMember(chatId, username)) {
            throw new IllegalArgumentException("Chat is not available");
        }
        List<Message> messages = new ArrayList<>();
        try (PreparedStatement statement = conn().prepareStatement(
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

    public synchronized Message addMessage(String chatId, String sender, String permissions, String text)
            throws SQLException {
        if (!chatExists(chatId) || !isMember(chatId, sender)) {
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
        try (PreparedStatement statement = conn().prepareStatement(
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

    private void deleteSession(String token) throws SQLException {
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

    public synchronized ChatDefinition ensureDirectChat(UserAccount owner, String targetUsername)
            throws SQLException {
        if (!hasPermission(owner, "ceo")) {
            throw new IllegalArgumentException("Только гендиректор может начинать личные чаты");
        }
        UserAccount target = findApprovedUser(targetUsername);
        if (target == null) {
            throw new IllegalArgumentException("Сотрудник не найден");
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
                              int members, int online, int messageCount) {
    }

    public record ChatDefinition(String id, String type, String name, String color,
                                 String professionRule, String workplaceRule,
                                 String writePermissions) {
    }

    public record Registration(String id, String fullName, String profession, String workplace,
                               String region, String phone, String permissions, String status,
                               String accountUsername, Instant createdAt) {
    }

    public record UserAccount(String username, String displayName, String profession,
                              String workplace, String region, String phone, String permissions) {
    }

    public record Session(String token, String role, String username, Instant expiresAt) {
    }
}
