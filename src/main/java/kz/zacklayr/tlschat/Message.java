package kz.zacklayr.tlschat;

import java.time.Instant;
import java.util.Objects;

public record Message(String sender, String text, Instant sentAt) {
    public Message {
        Objects.requireNonNull(sender, "sender is required");
        Objects.requireNonNull(text, "text is required");
        Objects.requireNonNull(sentAt, "sentAt is required");
    }
}
