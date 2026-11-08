package kz.zacklayr.tlschat;

import java.util.concurrent.CopyOnWriteArrayList;

public final class MessageBroadcaster {
    private final CopyOnWriteArrayList<MessageListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(MessageListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        listeners.addIfAbsent(listener);
    }

    public void removeListener(MessageListener listener) {
        listeners.remove(listener);
    }

    public void broadcast(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }
        for (MessageListener listener : listeners) {
            listener.onMessage(message);
        }
    }
}
