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
        } finally {
            Files.deleteIfExists(database);
        }
    }
}
