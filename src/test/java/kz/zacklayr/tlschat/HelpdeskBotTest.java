package kz.zacklayr.tlschat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpdeskBotTest {
    @Test
    void prioritizesSecurityIncidents() {
        HelpdeskBot.Reply reply = HelpdeskBot.answer("Алиса", "Похоже на фишинг и утечку токена");

        assertEquals("security", reply.category());
        assertTrue(reply.urgent());
        assertTrue(reply.text().contains("кибербезопасности"));
    }

    @Test
    void classifiesCommonSupportCategories() {
        assertEquals("access", HelpdeskBot.answer("Алиса", "Не могу войти, забыл пароль").category());
        assertEquals("calls", HelpdeskBot.answer("Алиса", "Не работает микрофон в видеозвонке").category());
        assertEquals("files", HelpdeskBot.answer("Алиса", "Не загружается файл").category());
        assertEquals("messaging", HelpdeskBot.answer("Алиса", "Не приходит сообщение в чат").category());
        assertFalse(HelpdeskBot.answer("Алиса", "Неизвестная ситуация").urgent());
    }
}
