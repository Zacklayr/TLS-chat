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

    @AfterEach
    void removeDatabase() throws Exception {
        if (database != null) {
            Files.deleteIfExists(database);
        }
    }
}
