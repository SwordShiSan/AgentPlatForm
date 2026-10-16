package com.agentplatform.domain.chat;

import com.agentplatform.domain.chat.ChatData.Envelope;
import java.util.function.Consumer;

public interface DispatchTransport extends AutoCloseable {
    interface Delivery { Envelope envelope(); void ack(); void retryAfterDisconnect(); }
    void publish(Envelope envelope);
    void consume(Consumer<Delivery> handler);
    boolean connected();
    @Override void close();
}
