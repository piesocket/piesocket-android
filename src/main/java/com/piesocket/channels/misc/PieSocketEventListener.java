package com.piesocket.channels.misc;

/**
 * Callback for a channel event. A single-method interface so it works as a
 * Java lambda / Kotlin SAM as well as an anonymous class:
 *
 * <pre>{@code
 * channel.listen("chat", event -> { ... });
 * }</pre>
 */
public interface PieSocketEventListener {
    void handleEvent(PieSocketEvent event);
}
