package kz.zacklayr.tlschat;

@FunctionalInterface
public interface MessageListener {
    void onMessage(Message message);
}
