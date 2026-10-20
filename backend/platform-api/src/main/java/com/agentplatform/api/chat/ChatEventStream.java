package com.agentplatform.api.chat;

import java.util.concurrent.Flow;

/** Internal lifecycle port. No Spring or Reactor types cross the API boundary. */
public interface ChatEventStream {
    record Frame(String comment, ChatApi.EventView event) {}
    interface Handle extends AutoCloseable {
        Flow.Publisher<Frame> frames();
        @Override void close();
    }
    Handle open(ChatApi.Caller caller, String conversation, long after, Runnable authorization);
    int activeStreams();
}
