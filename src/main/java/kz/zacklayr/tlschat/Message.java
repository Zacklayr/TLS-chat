package kz.zacklayr.tlschat;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Message(long id, String sender, String senderName, String senderRole, String text, Instant sentAt, List<String> seenBy) {
    public Message {
        Objects.requireNonNull(sender, "sender is required");
        Objects.requireNonNull(text, "text is required");
        Objects.requireNonNull(sentAt, "sentAt is required");
        if (senderName == null) senderName = sender;
        if (senderRole == null) senderRole = "";
        if (seenBy == null) seenBy = List.of();
    }

    public Message(String sender, String text, Instant sentAt) {
        this(0, sender, sender, "", text, sentAt, List.of());
    }
}
