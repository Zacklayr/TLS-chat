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
import static org.junit.jupiter.api.Assertions.assertEquals;

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
            WebSocketTicketStore ticketStore = new WebSocketTicketStore();
            ChatWebSocketServer server = new ChatWebSocketServer(websocketPort, repository, ticketStore);
            server.start();
            try {
                CountDownLatch subscribed = new CountDownLatch(1);
                CountDownLatch received = new CountDownLatch(1);
                CountDownLatch notificationReceived = new CountDownLatch(1);
                CountDownLatch typingReceived = new CountDownLatch(1);
                CountDownLatch readReceived = new CountDownLatch(1);
                CountDownLatch deliveredReceived = new CountDownLatch(1);
                CountDownLatch presenceReceived = new CountDownLatch(1);
                CountDownLatch helpdeskReplyReceived = new CountDownLatch(1);
                CountDownLatch marselReplyReceived = new CountDownLatch(1);
                AtomicReference<String> receivedMessage = new AtomicReference<>("");
                AtomicReference<String> receivedNotification = new AtomicReference<>("");
                WebSocketClient client = new WebSocketClient(
                        URI.create("ws://127.0.0.1:" + server.getPort() + "/?token=" + token)) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                        send("{\"type\":\"subscribe\",\"chatId\":\"ws-chat\"}");
                        send("{\"type\":\"subscribe\",\"chatId\":\"helpdesk\"}");
                        send("{\"type\":\"subscribe\",\"chatId\":\"marsel-cat\"}");
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
                        if (message.contains("\"type\":\"typing\"")) {
                            typingReceived.countDown();
                        }
                        if (message.contains("\"type\":\"read\"")) {
                            readReceived.countDown();
                        }
                        if (message.contains("\"type\":\"delivered\"")) {
                            deliveredReceived.countDown();
                        }
                        if (message.contains("\"type\":\"presence\"")) {
                            presenceReceived.countDown();
                        }
                        if (message.contains("AI Helpdesk Bot") && message.contains("Категория:")) {
                            helpdeskReplyReceived.countDown();
                        }
                        if (message.contains("Марсель 🐾")) {
                            marselReplyReceived.countDown();
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
                assertTrue(deliveredReceived.await(5, TimeUnit.SECONDS));
                assertTrue(receivedMessage.get().contains("Realtime hello"));
                client.send("{\"type\":\"message\",\"chatId\":\"helpdesk\",\"text\":\"Help request\"}");
                assertTrue(helpdeskReplyReceived.await(5, TimeUnit.SECONDS));
                client.send("{\"type\":\"message\",\"chatId\":\"marsel-cat\",\"text\":\"Нужна пауза\"}");
                assertTrue(marselReplyReceived.await(5, TimeUnit.SECONDS));
                client.send("{\"type\":\"typing\",\"chatId\":\"ws-chat\",\"active\":\"true\"}");
                assertTrue(typingReceived.await(5, TimeUnit.SECONDS));
                client.send("{\"type\":\"read\",\"chatId\":\"ws-chat\"}");
                assertTrue(readReceived.await(5, TimeUnit.SECONDS));
                server.broadcastNotification(repository.createNotification(
                        "", "Alert", "Realtime emergency", "emergency", "admin", null));
                assertTrue(notificationReceived.await(5, TimeUnit.SECONDS));
                assertTrue(receivedNotification.get().contains("\"type\":\"notification\""));
                String realtimeTicket = ticketStore.issue("realtime");
                WebSocketClient secondClient = new WebSocketClient(
                        URI.create("ws://127.0.0.1:" + server.getPort() + "/?ticket=" + realtimeTicket)) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                    }

                    @Override
                    public void onMessage(String message) {
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                    }

                    @Override
                    public void onError(Exception exception) {
                    }
                };
                assertTrue(secondClient.connectBlocking(5, TimeUnit.SECONDS));
                assertTrue(presenceReceived.await(5, TimeUnit.SECONDS));
                org.junit.jupiter.api.Assertions.assertNull(ticketStore.consume(realtimeTicket));
                assertTrue(server.hasActiveUser("realtime"));
                client.closeBlocking();
                Thread.sleep(200);
                assertEquals("online", repository.findProfile("realtime").presence());
                secondClient.closeBlocking();
                Thread.sleep(200);
                assertTrue(!server.hasActiveUser("realtime"));
                assertEquals("offline", repository.findProfile("realtime").presence());
            } finally {
                server.close();
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }
}
