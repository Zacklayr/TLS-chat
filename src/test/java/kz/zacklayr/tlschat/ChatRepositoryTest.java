package kz.zacklayr.tlschat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

            assertTrue(repository.findChatsForUser(repository.authenticate("alice", "1111"))
                    .stream().anyMatch(c -> "developers".equals(c.id())));
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
            assertTrue(chats.stream().anyMatch(c -> "support".equals(c.id())));
            assertTrue(chats.stream().anyMatch(c -> "general".equals(c.id())));
        }
    }

    @Test
    void generalChatRestrictedToCeoAndDeputy() throws Exception {
        database = Files.createTempFile("tls-chat-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            // Register regular developer
            ChatRepository.Registration devApp = repository.registerApplication(
                    "Bob Dev", "Программист", "Разработка движка", "Алматы", "+77001112233");
            repository.decideRegistration(devApp.id(), "APPROVED", "Bob Dev", "Программист",
                    "Разработка движка", "Алматы", "+77001112233", "", "bob", "pass123");
            repository.assignChatsForUser("bob");

            // Register deputy director with global_write
            ChatRepository.Registration depApp = repository.registerApplication(
                    "Sanjar Deputy", "Зам. гендиректора", "Высшее руководство", "Алматы", "+77002223344");
            repository.decideRegistration(depApp.id(), "APPROVED", "Sanjar Deputy", "Зам. гендиректора",
                    "Высшее руководство", "Алматы", "+77002223344", "global_write", "sanjar", "pass123");
            repository.assignChatsForUser("sanjar");

            // Register CEO
            ChatRepository.Registration ceoApp = repository.registerApplication(
                    "Erasyl CEO", "Гендиректор", "Высшее руководство", "Алматы", "+77003334455");
            repository.decideRegistration(ceoApp.id(), "APPROVED", "Erasyl CEO", "Гендиректор",
                    "Высшее руководство", "Алматы", "+77003334455", "", "erasyl", "pass123");
            repository.setUserPermissions("erasyl", "ceo");
            repository.assignChatsForUser("erasyl");

            // Everyone has general chat
            assertTrue(repository.findChatsForUser(repository.authenticate("bob", "pass123"))
                    .stream().anyMatch(c -> "general".equals(c.id())));
            assertTrue(repository.findChatsForUser(repository.authenticate("sanjar", "pass123"))
                    .stream().anyMatch(c -> "general".equals(c.id())));
            assertTrue(repository.findChatsForUser(repository.authenticate("erasyl", "pass123"))
                    .stream().anyMatch(c -> "general".equals(c.id())));

            // CEO and Deputy can post to general
            repository.addMessage("general", "erasyl", "ceo", "Привет всем от гендиректора!");
            repository.addMessage("general", "sanjar", "global_write", "Объявление от зам. гендиректора!");

            // Regular employee cannot post to general
            assertThrows(IllegalArgumentException.class,
                    () -> repository.addMessage("general", "bob", "", "Попытка написать обычного сотрудника"));
        }
    }

    @Test
    void anonymousChatMasksSender() throws Exception {
        database = Files.createTempFile("tls-chat-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            ChatRepository.Registration devApp = repository.registerApplication(
                    "Secret Employee", "Тестировщик", "QA", "Талгар", "+77009998877");
            repository.decideRegistration(devApp.id(), "APPROVED", "Secret Employee", "Тестировщик",
                    "QA", "Талгар", "+77009998877", "", "secret_user", "pass123");
            repository.assignChatsForUser("secret_user");

            repository.addMessage("anonymous", "secret_user", "", "Здесь можно писать без правил");
            var messages = repository.findMessages("anonymous", "secret_user");
            assertEquals(1, messages.size());
            assertEquals("Аноним", messages.get(0).sender());
            assertEquals("Здесь можно писать без правил", messages.get(0).text());
        }
    }

    @Test
    void defaultChatsAutoAssignBasedOnDepartmentAndRegion() throws Exception {
        database = Files.createTempFile("tls-chat-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            ChatRepository.Registration app = repository.registerApplication(
                    "Talgar Engine Dev", "Инженер движка", "Разработка движка", "Талгар", "+77007776655");
            repository.decideRegistration(app.id(), "APPROVED", "Talgar Engine Dev", "Инженер движка",
                    "Разработка движка", "Талгар", "+77007776655", "", "talgar_dev", "pass123");
            repository.assignChatsForUser("talgar_dev");

            var userChats = repository.findChatsForUser(repository.authenticate("talgar_dev", "pass123"))
                    .stream().map(ChatRepository.ChatSummary::id).toList();

            // General, common gamedev, specific dept, local branch, and public channels
            assertTrue(userChats.contains("general"));
            assertTrue(userChats.contains("gamedev-all"));
            assertTrue(userChats.contains("dept-engine"));
            assertTrue(userChats.contains("region-talgar"));
            assertTrue(userChats.contains("helpdesk"));
            assertTrue(userChats.contains("anonymous"));
            assertTrue(userChats.contains("marsel-cat"));

            // Must NOT contain other departments or other regions
            assertTrue(!userChats.contains("dept-art"));
            assertTrue(!userChats.contains("region-almaty"));
            assertTrue(!userChats.contains("region-astana"));
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
            repository.deleteSession(token);
            assertNull(repository.findSession(token));

            ChatRepository.ChatDefinition direct = repository.ensureDirectChat(ceo, "employee");
            ChatRepository.Attachment attachment = repository.createAttachment(
                    direct.id(), "ceo", "design.pdf", "stored-design.pdf", "application/pdf", 128);
            assertEquals("design.pdf", repository.findAttachment(attachment.id()).originalName());
            ChatRepository.Call call = repository.createCall(direct.id(), "ceo", "employee", "video");
            repository.addCallEvent(call.id(), "ceo", "offer", "{\"sdp\":\"test-offer\"}");
            assertEquals(1, repository.findCallEvents(call.id(), "employee", 0).size());
            ChatRepository.Call missedCall = repository.createCall(direct.id(), "ceo", "employee", "audio");
            repository.expireStaleCallsAt(Instant.now().plus(3, ChronoUnit.MINUTES));
            assertEquals("MISSED", repository.findCall(missedCall.id()).status());
            assertThrows(IllegalArgumentException.class,
                    () -> repository.addCallEvent(missedCall.id(), "employee", "accept", ""));
            repository.addMessage(direct.id(), "ceo", ceo.permissions(), "Important message");
            assertEquals(1, repository.findMessages(direct.id(), "employee").size());
        }
    }

    @Test
    void marksMessagesAsReadAndReturnsSeenByList() throws Exception {
        database = Files.createTempFile("tls-chat-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            ChatRepository.Registration dev1 = repository.registerApplication(
                    "Алиса", "Разработчик", "Разработка движка", "Алматы", "+77001110001");
            repository.decideRegistration(dev1.id(), "APPROVED", "Алиса", "Разработчик",
                    "Разработка движка", "Алматы", "+77001110001", "", "alisa", "pass1");
            repository.assignChatsForUser("alisa");

            ChatRepository.Registration dev2 = repository.registerApplication(
                    "Боб", "Тестировщик", "Разработка движка", "Алматы", "+77001110002");
            repository.decideRegistration(dev2.id(), "APPROVED", "Боб", "Тестировщик",
                    "Разработка движка", "Алматы", "+77001110002", "", "bob_reader", "pass2");
            repository.assignChatsForUser("bob_reader");

            repository.addMessage("dept-engine", "alisa", "", "Коллеги, новый билд готов!");

            // Before bob opens: bob hasn't seen it
            var msgsBefore = repository.findMessages("dept-engine", "alisa");
            assertEquals(1, msgsBefore.size());
            assertEquals("Алиса", msgsBefore.get(0).senderName());
            assertEquals("Разработчик", msgsBefore.get(0).senderRole());
            assertTrue(msgsBefore.get(0).seenBy().stream().noneMatch(s -> s.contains("Боб")));

            // Bob opens the chat
            var msgsBob = repository.findMessages("dept-engine", "bob_reader");
            assertEquals(1, msgsBob.size());
            assertTrue(msgsBob.get(0).seenBy().stream().anyMatch(s -> s.contains("Боб")));

            // When Alice checks again, she sees that Bob read it
            var msgsAliceAgain = repository.findMessages("dept-engine", "alisa");
            assertTrue(msgsAliceAgain.get(0).seenBy().stream().anyMatch(s -> s.contains("Боб")));
        }
    }

    @Test
    void allowsBotsToPostMessagesWithoutChatMembership() throws Exception {
        database = Files.createTempFile("tls-chat-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            // Marsel bot in marsel-cat chat
            Message m1 = repository.addMessage("marsel-cat", "Марсель 🐾", "", "Мяу! 🐾");
            assertEquals("Марсель 🐾", m1.sender());
            assertEquals("Мяу! 🐾", m1.text());

            // AI Helpdesk Bot in helpdesk chat
            Message m2 = repository.addMessage("helpdesk", "AI Helpdesk Bot", "", "Обращение принято");
            assertEquals("AI Helpdesk Bot", m2.sender());
            assertEquals("Обращение принято", m2.text());
        }
    }

    @Test
    void supportsProfilesNotificationsScheduledCallsAndAdminAccess() throws Exception {
        database = Files.createTempFile("tls-chat-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            repository.createChat("planning", "group", "Planning", "c-blue", "", "", "", "");
            var first = repository.registerApplication("First Employee", "Developer", "Engine", "Almaty", "+77000000001");
            repository.decideRegistration(first.id(), "APPROVED", "First Employee", "Developer", "Engine", "Almaty",
                    "+77000000001", "", "first", "pass123");
            var second = repository.registerApplication("Second Employee", "QA", "Engine", "Almaty", "+77000000002");
            repository.decideRegistration(second.id(), "APPROVED", "Second Employee", "QA", "Engine", "Almaty",
                    "+77000000002", "", "second", "pass123");
            repository.assignChatsForUser("first");
            repository.assignChatsForUser("second");

            var profile = repository.updateProfile("first", "Работаю над сетевым слоем", "busy");
            assertEquals("busy", profile.presence());
            assertEquals("Работаю над сетевым слоем", profile.about());

            var notification = repository.createNotification("", "Важное объявление", "Планёрка в 10:00",
                    "emergency", "admin", Instant.now().plusSeconds(3600));
            assertEquals(1, repository.findNotifications("first").size());
            repository.markNotificationRead(notification.id(), "first");
            assertTrue(repository.findNotifications("first").get(0).read());

            var scheduled = repository.scheduleCall("planning", "first", "second", "video",
                    Instant.now().plusSeconds(3600));
            assertEquals(1, repository.findScheduledCalls("second").size());
            assertEquals("CANCELLED", repository.cancelScheduledCall(scheduled.id(), "second").status());

            var adminUser = repository.updateUserAccess("second", "SUSPENDED", "support_agent");
            assertEquals("SUSPENDED", adminUser.status());
            assertEquals("support_agent", adminUser.permissions());
        }
    }

    @AfterEach
    void removeDatabase() throws Exception {
        if (database != null) {
            Files.deleteIfExists(database);
        }
    }
}
