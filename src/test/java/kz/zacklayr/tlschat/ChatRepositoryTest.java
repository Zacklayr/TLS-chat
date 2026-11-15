package kz.zacklayr.tlschat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            repository.register("alice", "Alice", "secure-pass", "Продавец", "Талгар");

            ChatRepository.Registration pending = repository.pendingRegistrations().get(0);
            assertEquals("PENDING", pending.status());
            assertEquals("Продавец", pending.profession());

            ChatRepository.Registration approved = repository.decideRegistration(
                    "alice", "APPROVED", "Продавец", "Талгар",
                    "regional_write,global_write");
            assertEquals("APPROVED", approved.status());
            assertEquals("global_write,regional_write", approved.permissions());
        }
    }

    @AfterEach
    void removeDatabase() throws Exception {
        if (database != null) {
            Files.deleteIfExists(database);
        }
    }
}
