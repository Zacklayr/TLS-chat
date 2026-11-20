package kz.zacklayr.tlschat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatRepositoryTest {
    private Path database;

    @Test
    void convertsHerokuDatabaseUrlToJdbcFormat() {
        assertEquals(
                "jdbc:postgresql://db.example.com:5432/chat?user=chat_user&password=p%40ss%2Bword&sslmode=require",
                ChatRepository.normalizeJdbcUrl(
                        "postgres://chat_user:p%40ss%2Bword@db.example.com:5432/chat?sslmode=require"));
    }

    @Test
    void storesAndApprovesRegistrationWithRoles() throws Exception {
        database = Files.createTempFile("tls-chat-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            ChatRepository.Registration application = repository.registerApplication(
                    "Alice Example", "Продавец", "Магазин Avengers", "Талгар", "+77001234567");

            ChatRepository.Registration pending = repository.pendingRegistrations().get(0);
            assertEquals("PENDING", pending.status());
            assertEquals("Продавец", pending.profession());
            assertEquals(application.id(), pending.id());

            ChatRepository.Registration approved = repository.decideRegistration(
                    application.id(), "APPROVED", "Alice Example", "Продавец",
                    "Магазин Avengers", "Талгар", "+77001234567",
                    "regional_write,global_write", "alice", "secure-pass");
            assertEquals("APPROVED", approved.status());
            assertEquals("global_write,regional_write", approved.permissions());
            assertEquals("alice", approved.accountUsername());
        }
    }

    @Test
    void assignsMatchingUsersAndEnforcesChatWritePermission() throws Exception {
        database = Files.createTempFile("tls-chat-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            repository.createChat("developers", "department", "Developers", "#123456",
                    "Developer", "", "global_write");
            ChatRepository.Registration application = repository.registerApplication(
                    "Alice Example", "Developer", "Avengers", "Almaty", "+77001234567");
            repository.decideRegistration(application.id(), "APPROVED", "Alice Example", "Developer",
                    "Avengers", "Almaty", "+77001234567", "global_write", "alice", "1111");
            repository.assignChatsForUser("alice");

            assertEquals(1, repository.findChatsForUser(repository.authenticate("alice", "1111")).size());
            repository.addMessage("developers", "alice", "global_write", "Hello");
            assertThrows(IllegalArgumentException.class,
                    () -> repository.addMessage("developers", "alice", "", "Denied"));
        }
    }

    @Test
    void registrarCannotAssignCeoPermission() throws Exception {
        database = Files.createTempFile("tls-chat-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            ChatRepository.Registration application = repository.registerApplication(
                    "CEO Example", "Director", "Avengers", "Almaty", "+77001234567");
            assertThrows(IllegalArgumentException.class,
                    () -> repository.decideRegistration(application.id(), "APPROVED", "CEO Example",
                            "Director", "Avengers", "Almaty", "+77001234567",
                            "ceo", "ceo_user", "1111"));
        }
    }

    @Test
    void assignsChatByDepartmentRule() throws Exception {
        database = Files.createTempFile("tls-chat-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            repository.createChat("support", "department", "Техподдержка", "#ff8800",
                    "", "Техподдержка", "");
            repository.createChat("general", "global", "Общий чат", "#00ff88",
                    "", "", "global_write");

            ChatRepository.Registration app = repository.registerApplication(
                    "Канат Техов", "Специалист поддержки", "Техподдержка", "Алматы", "+77005554433");
            repository.decideRegistration(app.id(), "APPROVED", "Канат Техов", "Специалист поддержки",
                    "Техподдержка", "Алматы", "+77005554433", "", "kanat", "pass123");
            repository.assignChatsForUser("kanat");

            var chats = repository.findChatsForUser(repository.authenticate("kanat", "pass123"));
            assertEquals(2, chats.size());
        }
    }

    @Test
    void persistsSessionsAndCreatesCeoDirectChat() throws Exception {
        database = Files.createTempFile("tls-chat-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            ChatRepository.Registration ceoApplication = repository.registerApplication(
                    "Chief Executive", "Гендиректор", "Высшее руководство", "Алматы", "+77000000001");
            repository.decideRegistration(ceoApplication.id(), "APPROVED", "Chief Executive",
                    "Гендиректор", "Высшее руководство", "Алматы", "+77000000001",
                    "", "ceo", "secure-pass");
            repository.setUserPermissions("ceo", "ceo");
            ChatRepository.Registration employeeApplication = repository.registerApplication(
                    "Employee", "Разработчик", "Разработка движка", "Алматы", "+77000000002");
            repository.decideRegistration(employeeApplication.id(), "APPROVED", "Employee",
                    "Разработчик", "Разработка движка", "Алматы", "+77000000002",
                    "", "employee", "secure-pass");

            ChatRepository.UserAccount ceo = repository.authenticate("ceo", "secure-pass");
            String token = repository.createSession("USER", ceo.username());
            assertEquals(ceo.username(), repository.findSession(token).username());

            ChatRepository.ChatDefinition direct = repository.ensureDirectChat(ceo, "employee");
            repository.addMessage(direct.id(), "ceo", ceo.permissions(), "Important message");
            assertEquals(1, repository.findMessages(direct.id(), "employee").size());
        }
    }

    @AfterEach
    void removeDatabase() throws Exception {
        if (database != null) {
            Files.deleteIfExists(database);
        }
    }
}
