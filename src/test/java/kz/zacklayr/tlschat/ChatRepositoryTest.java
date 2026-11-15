package kz.zacklayr.tlschat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatRepositoryTest {
    @Test
    void convertsHerokuDatabaseUrlToJdbcFormat() {
        assertEquals(
                "jdbc:postgresql://db.example.com:5432/chat?user=chat_user&password=p%40ss%2Bword&sslmode=require",
                ChatRepository.normalizeJdbcUrl(
                        "postgres://chat_user:p%40ss%2Bword@db.example.com:5432/chat?sslmode=require"));
    }
}
