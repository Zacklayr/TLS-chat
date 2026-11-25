package kz.zacklayr.tlschat;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServerTest {
    @Test
    void exposesHealthEndpointAfterStarting() throws Exception {
        var database = Files.createTempFile("tls-chat-server-test-", ".db");
        try (ChatServer server = new ChatServer(0, database)) {
            server.start();
            URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/health");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(endpoint).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"ok\""));
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
            assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(""));

            HttpResponse<String> webPage = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, webPage.statusCode());
            assertTrue(webPage.body().contains("compressImage"));
            assertTrue(webPage.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));

            HttpResponse<String> adminPage = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/admin")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, adminPage.statusCode());
            assertTrue(adminPage.body().contains("/api/admin/users"));

            HttpResponse<String> manifest = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/manifest.webmanifest")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, manifest.statusCode());
            assertTrue(manifest.body().contains("TLS Chat"));

            HttpResponse<String> icon = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/icons/tls-chat.svg")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, icon.statusCode());
            assertTrue(icon.headers().firstValue("Content-Type").orElse("").contains("image/svg+xml"));

            HttpResponse<String> protectedPhoto = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/photos/marsel/test.png")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, protectedPhoto.statusCode());
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void runsAuthenticatedApiFlowAgainstTemporaryDatabase() throws Exception {
        var database = Files.createTempFile("tls-chat-auth-test-", ".db");
        try (ChatRepository seed = new ChatRepository("jdbc:sqlite:" + database.toAbsolutePath())) {
            var request = seed.registerApplication("Test Employee", "Developer", "Engine", "Almaty", "+70000000000");
            seed.decideRegistration(request.id(), "APPROVED", request.fullName(), request.profession(),
                    request.workplace(), request.region(), request.phone(), "global_write", "employee", "password123");
            seed.assignChatsForUser("employee");
        }
        try (ChatServer server = new ChatServer(0, database)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"employee\",\"password\":\"password123\"}"))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            String token = jsonValue(login.body(), "token");
            HttpResponse<String> chats = client.send(HttpRequest.newBuilder(URI.create(base + "/api/chats"))
                            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, chats.statusCode());
            assertTrue(chats.body().contains("general"));
            HttpResponse<String> profile = client.send(HttpRequest.newBuilder(URI.create(base + "/api/profile"))
                            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, profile.statusCode());
            assertTrue(profile.body().contains("employee"));

            HttpResponse<String> message = client.send(HttpRequest.newBuilder(URI.create(base + "/api/chats/general/messages"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"integration message\"}"))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, message.statusCode());
            assertTrue(message.body().contains("integration message"));

            byte[] fileContent = "integration attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            HttpResponse<String> upload = client.send(HttpRequest.newBuilder(URI.create(base + "/api/files?chatId=general"))
                            .header("Authorization", "Bearer " + token)
                            .header("X-Filename", "integration.txt")
                            .header("Content-Type", "text/plain")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(fileContent))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, upload.statusCode());
            String attachmentId = jsonValue(upload.body(), "id");
            HttpResponse<byte[]> download = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/api/files/" + attachmentId))
                            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, download.statusCode());
            org.junit.jupiter.api.Assertions.assertArrayEquals(fileContent, download.body());

            HttpResponse<String> logout = client.send(HttpRequest.newBuilder(URI.create(base + "/api/logout"))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, logout.statusCode());
            HttpResponse<String> afterLogout = client.send(HttpRequest.newBuilder(URI.create(base + "/api/profile"))
                            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, afterLogout.statusCode());
        } finally {
            Files.deleteIfExists(database);
        }
    }

    @Test
    void runsTwoPartyCallLifecycleOverHttp() throws Exception {
        var database = Files.createTempFile("tls-chat-call-test-", ".db");
        String directChatId;
        try (ChatRepository seed = new ChatRepository("jdbc:sqlite:" + database.toAbsolutePath())) {
            var ceoRequest = seed.registerApplication("Call CEO", "Director", "Leadership", "Almaty", "+70000000001");
            seed.decideRegistration(ceoRequest.id(), "APPROVED", ceoRequest.fullName(), ceoRequest.profession(),
                    ceoRequest.workplace(), ceoRequest.region(), ceoRequest.phone(), "", "call_ceo", "password123");
            seed.setUserPermissions("call_ceo", "ceo");
            var employeeRequest = seed.registerApplication("Call Employee", "Developer", "Engine", "Almaty", "+70000000002");
            seed.decideRegistration(employeeRequest.id(), "APPROVED", employeeRequest.fullName(), employeeRequest.profession(),
                    employeeRequest.workplace(), employeeRequest.region(), employeeRequest.phone(), "", "call_employee", "password123");
            directChatId = seed.ensureDirectChat(seed.authenticate("call_ceo", "password123"), "call_employee").id();
        }
        try (ChatServer server = new ChatServer(0, database)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            String callerToken = loginToken(client, base, "call_ceo");
            String calleeToken = loginToken(client, base, "call_employee");

            HttpResponse<String> created = client.send(HttpRequest.newBuilder(URI.create(base + "/api/calls"))
                    .header("Authorization", "Bearer " + callerToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"chatId\":\"" + directChatId
                            + "\",\"username\":\"call_employee\",\"mediaType\":\"audio\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode());
            String callId = jsonValue(created.body(), "id");

            HttpResponse<String> incoming = client.send(HttpRequest.newBuilder(URI.create(base + "/api/calls/incoming"))
                    .header("Authorization", "Bearer " + calleeToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, incoming.statusCode());
            assertTrue(incoming.body().contains(callId));

            HttpResponse<String> accepted = postCallEvent(client, base, callId, calleeToken, "accept");
            assertEquals(201, accepted.statusCode());
            HttpResponse<String> active = client.send(HttpRequest.newBuilder(URI.create(base + "/api/calls/" + callId))
                    .header("Authorization", "Bearer " + callerToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, active.statusCode());
            assertTrue(active.body().contains("\"status\":\"ACTIVE\""));

            HttpResponse<String> ended = postCallEvent(client, base, callId, callerToken, "end");
            assertEquals(201, ended.statusCode());
            HttpResponse<String> finished = client.send(HttpRequest.newBuilder(URI.create(base + "/api/calls/" + callId))
                    .header("Authorization", "Bearer " + calleeToken).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, finished.statusCode());
            assertTrue(finished.body().contains("\"status\":\"ENDED\""));
        } finally {
            Files.deleteIfExists(database);
        }
    }

    private static String loginToken(HttpClient client, String base, String username) throws Exception {
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username
                        + "\",\"password\":\"password123\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        return jsonValue(login.body(), "token");
    }

    private static HttpResponse<String> postCallEvent(HttpClient client, String base, String callId,
                                                       String token, String type) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/calls/" + callId + "/events"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"" + type + "\",\"payload\":\"\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonValue(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        assertTrue(start >= 0, "Missing JSON field " + key);
        start += marker.length();
        int end = json.indexOf('"', start);
        assertTrue(end >= start, "Invalid JSON field " + key);
        return json.substring(start, end);
    }
}
