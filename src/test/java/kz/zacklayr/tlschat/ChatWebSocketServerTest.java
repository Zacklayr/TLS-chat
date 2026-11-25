package kz.zacklayr.tlschat;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWebSocketServerTest {
    @Test
    void authenticatesSubscribesAndBroadcastsMessages() throws Exception {
        Path database = Files.createTempFile("tls-chat-ws-test-", ".db");
        try (ChatRepository repository = new ChatRepository("jdbc:sqlite:" + database)) {
            repository.createChat("ws-chat", "group", "Realtime", "c-blue", "", "", "", "");
            ChatRepository.Registration registration = repository.registerApplication(
                    "Realtime User", "Developer", "Engine", "Almaty", "+77000000111");
            repository.decideRegistration(registration.id(), "APPROVED", "Realtime User", "Developer",
                    "Engine", "Almaty", "+77000000111", "", "realtime", "pass123");
            repository.assignChatsForUser("realtime");
            String token = repository.createSession("USER", "realtime");

            int websocketPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                websocketPort = socket.getLocalPort();
            }
            ChatWebSocketServer server = new ChatWebSocketServer(websocketPort, repository);
            server.start();
            try {
                CountDownLatch subscribed = new CountDownLatch(1);
                CountDownLatch received = new CountDownLatch(1);
                CountDownLatch notificationReceived = new CountDownLatch(1);
                AtomicReference<String> receivedMessage = new AtomicReference<>("");
                AtomicReference<String> receivedNotification = new AtomicReference<>("");
                WebSocketClient client = new WebSocketClient(
                        URI.create("ws://127.0.0.1:" + server.getPort() + "/?token=" + token)) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                        send("{\"type\":\"subscribe\",\"chatId\":\"ws-chat\"}");
                    }

                    @Override
                    public void onMessage(String message) {
                        if (message.contains("\"type\":\"subscribed\"")) {
                            subscribed.countDown();
                        }
                        if (message.contains("Realtime hello")) {
                            receivedMessage.set(message);
                            received.countDown();
                        }
                        if (message.contains("Realtime emergency")) {
                            receivedNotification.set(message);
                            notificationReceived.countDown();
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                    }

                    @Override
                    public void onError(Exception exception) {
                    }
                };
                assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
                assertTrue(subscribed.await(5, TimeUnit.SECONDS));
                client.send("{\"type\":\"message\",\"chatId\":\"ws-chat\",\"text\":\"Realtime hello\"}");
                assertTrue(received.await(5, TimeUnit.SECONDS));
                assertTrue(receivedMessage.get().contains("Realtime hello"));
                server.broadcastNotification(repository.createNotification(
                        "", "Alert", "Realtime emergency", "emergency", "admin", null));
                assertTrue(notificationReceived.await(5, TimeUnit.SECONDS));
                assertTrue(receivedNotification.get().contains("\"type\":\"notification\""));
                client.closeBlocking();
            } finally {
                server.close();
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }
}
