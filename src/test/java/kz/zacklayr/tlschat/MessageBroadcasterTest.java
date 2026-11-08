package kz.zacklayr.tlschat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageBroadcasterTest {
    @Test
    void broadcastsMessageToAllRegisteredListeners() {
        MessageBroadcaster broadcaster = new MessageBroadcaster();
        List<Message> receivedByFirst = new ArrayList<>();
        List<Message> receivedBySecond = new ArrayList<>();
        Message message = new Message("alice", "Hello", Instant.parse("2026-01-01T00:00:00Z"));

        broadcaster.addListener(receivedByFirst::add);
        broadcaster.addListener(receivedBySecond::add);

        broadcaster.broadcast(message);

        assertEquals(List.of(message), receivedByFirst);
        assertEquals(List.of(message), receivedBySecond);
    }

    @Test
    void removedListenerDoesNotReceiveMessages() {
        MessageBroadcaster broadcaster = new MessageBroadcaster();
        List<Message> received = new ArrayList<>();
        MessageListener listener = received::add;

        broadcaster.addListener(listener);
        broadcaster.removeListener(listener);
        broadcaster.broadcast(new Message("alice", "Hello", Instant.now()));

        assertEquals(List.of(), received);
    }
}
