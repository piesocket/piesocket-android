package com.piesocket.channels;

import com.piesocket.channels.misc.Logger;
import com.piesocket.channels.misc.PieSocketEvent;
import com.piesocket.channels.misc.PieSocketException;
import com.piesocket.channels.misc.PieSocketOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * A single WebSocket shared by many {@link Channel} handles (PieSocket v4).
 *
 * <p>The channel named at connect time is the "primary" — its lifecycle is the
 * socket's lifecycle. Every other channel is subscribed with a
 * {@code system::subscribe} control frame and rides the same socket; outbound
 * frames for it are tagged with {@code system::channel}, and inbound frames are
 * routed back to it by the {@code system::channel} / {@code data.channel} the
 * server stamps on them.
 *
 * <p>All v4 control/system events are {@code system::x} (double colon) — a
 * separate, untouched convention from v3's single-colon {@code system:} events
 * that {@link Channel} still speaks natively when not attached to a hub.
 *
 * <p>Port of the piesocket-flutter {@code Connection} (itself a port of
 * piesocket-js's {@code Connection.js}).
 */
public class Connection extends WebSocketListener {

    private static final int CONTROL_TIMEOUT_MS = 10000;
    private static final int NORMAL_CLOSURE_STATUS = 1000;
    private static final long RECONNECT_BASE_DELAY_MS = 1000;
    private static final long RECONNECT_MAX_DELAY_MS = 30000;

    public String primaryChannelId;
    private final PieSocketOptions options;
    private final Logger logger;

    public final Map<String, Channel> channels = new LinkedHashMap<>();
    private final Map<String, PendingControl> pending = new LinkedHashMap<>();
    private final Map<String, List<CompletableFuture<JSONArray>>> memberRequests = new LinkedHashMap<>();

    private String primaryUuid;
    private String primaryJwt;
    private WebSocket ws;
    private boolean connected = false;
    private boolean shouldReconnect = false;
    private boolean migrating = false;
    private boolean openedOnce = false;
    private int reconnectAttempt = 0;
    private ScheduledFuture<?> pendingReconnect;

    private final ScheduledExecutorService scheduler;

    /** Shared across reconnects — a fresh client per reconnect leaks OkHttp's threads. */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

    /** Set by PieSocket to learn when the primary channel's socket opens/fails. */
    public Runnable onPrimaryConnected;
    public RawErrorSink onPrimaryError;

    /** Overridable for tests — avoids needing a real socket to test routing. */
    public RawSender sendOverride;
    /** Overridable for tests — binary counterpart of {@link #sendOverride}. */
    public RawBinarySender sendBinaryOverride;

    public interface RawSender {
        void send(String data);
    }

    public interface RawBinarySender {
        void send(byte[] bytes);
    }

    public interface RawErrorSink {
        void call(Throwable error);
    }

    /**
     * Opens the shared socket with {@code primaryChannelId} as primary.
     * {@code primaryChannel} is attached before the socket connects so the
     * {@code onOpen} lookup that fires {@code system:connected} is never too
     * late.
     */
    public Connection(
            String primaryChannelId,
            PieSocketOptions options,
            Logger logger,
            String uuid,
            Channel primaryChannel,
            String jwt) {
        this.primaryChannelId = primaryChannelId;
        this.options = options;
        this.logger = logger;
        this.primaryUuid = uuid;
        this.primaryJwt = jwt;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "piesocket-connection");
            t.setDaemon(true);
            return t;
        });
        this.channels.put(primaryChannelId, primaryChannel);
        connect(Channel.buildUrl(primaryChannelId, options, uuid, jwt));
    }

    /** Test seam — no real socket. */
    public Connection(String primaryChannelId, PieSocketOptions options, Logger logger,
                      RawSender sendOverride) {
        this.primaryChannelId = primaryChannelId;
        this.options = options;
        this.logger = logger;
        this.sendOverride = sendOverride;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "piesocket-connection-test");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isPrimary(String channelId) {
        return channelId.equals(primaryChannelId);
    }

    public void attachChannel(String channelId, Channel channel) {
        channels.put(channelId, channel);
    }

    public void detachChannel(String channelId) {
        channels.remove(channelId);
        settleMemberRequests(channelId,
                new PieSocketException("Channel detached"));
    }

    // ===== Outbound =====

    private void rawSend(String data) {
        if (sendOverride != null) {
            sendOverride.send(data);
            return;
        }
        if (ws != null) {
            ws.send(data);
        }
    }

    private void rawSendBinary(byte[] bytes) {
        if (sendBinaryOverride != null) {
            sendBinaryOverride.send(bytes);
            return;
        }
        if (ws != null) {
            ws.send(ByteString.of(bytes));
        }
    }

    public void sendControl(String eventName, JSONObject data) {
        try {
            JSONObject frame = new JSONObject();
            frame.put("event", eventName);
            frame.put("data", data);
            rawSend(frame.toString());
        } catch (Exception e) {
            logger.log("PieSocket: control frame send failed: " + e.getMessage());
        }
    }

    /**
     * Send an application frame on behalf of {@code channelId}. Secondary
     * channels get a {@code system::channel} tag so the receiving multiplexed
     * client (and the server) can attribute the frame to the right subscription.
     */
    public void send(String channelId, PieSocketEvent event) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("event", event.getEvent());
            if (event.getData() != null) {
                payload.put("data", event.getData());
            }
            if (event.getMeta() != null) {
                payload.put("meta", event.getMeta());
            }
            if (!isPrimary(channelId)) {
                payload.put("system::channel", channelId);
            }
            rawSend(payload.toString());
        } catch (Exception e) {
            logger.log("PieSocket: send failed: " + e.getMessage());
        }
    }

    /**
     * Like {@link #send}, but for an already-serialised (or non-JSON) payload —
     * used by {@link Channel#send(String)} and {@link Channel#publishEvent}.
     * Tries to parse it as JSON to tag {@code system::channel} on a secondary
     * channel; falls back to sending it verbatim if it isn't JSON.
     */
    public void sendRaw(String channelId, String text) {
        if (!isPrimary(channelId)) {
            try {
                JSONObject obj = new JSONObject(text);
                obj.put("system::channel", channelId);
                rawSend(obj.toString());
                return;
            } catch (Exception e) {
                // Not JSON — fall through and send verbatim.
            }
        }
        rawSend(text);
    }

    /**
     * Send a raw binary frame — see {@link Channel#sendBinary}. Only valid for
     * the primary channel: raw bytes carry no {@code system::channel} tag, so a
     * secondary channel's frame can't be attributed server-side.
     */
    public void sendBinary(String channelId, byte[] bytes) {
        if (!isPrimary(channelId)) {
            throw new PieSocketException(
                    "Binary frames are only supported on the primary channel, not \"" + channelId + "\".");
        }
        rawSendBinary(bytes);
    }

    // ===== Subscription control =====

    public CompletableFuture<Void> subscribeChannel(String channelId, JSONObject params) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (pending.remove(channelId) != null && !future.isDone()) {
                future.completeExceptionally(
                        new PieSocketException("system::subscribe timed out for \"" + channelId + "\""));
            }
        }, CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        pending.put(channelId, new PendingControl(future, timer, params));

        if (connected) {
            sendControl("system::subscribe", params);
        }
        // Otherwise onOpen() replays every pending subscribe.
        return future;
    }

    public CompletableFuture<Void> unsubscribeChannel(String channelId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (isPrimary(channelId)) {
            future.completeExceptionally(
                    new PieSocketException("Cannot unsubscribe the primary channel directly"));
            return future;
        }

        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (pending.remove(channelId) != null && !future.isDone()) {
                future.complete(null);
            }
        }, CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        pending.put(channelId, new PendingControl(future, timer, null));
        try {
            JSONObject data = new JSONObject().put("channel", channelId);
            sendControl("system::unsubscribe", data);
        } catch (Exception ignored) {
        }
        return future;
    }

    public CompletableFuture<JSONArray> requestMembers(String channelId) {
        CompletableFuture<JSONArray> future = new CompletableFuture<>();
        memberRequests.computeIfAbsent(channelId, k -> new ArrayList<>()).add(future);

        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (!future.isDone()) {
                List<CompletableFuture<JSONArray>> list = memberRequests.get(channelId);
                if (list != null) {
                    list.remove(future);
                }
                future.completeExceptionally(new PieSocketException(
                        "system::get_members timed out for \"" + channelId + "\""));
            }
        }, CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        future.whenComplete((v, t) -> timer.cancel(false));

        try {
            JSONObject data = new JSONObject().put("channel", channelId);
            sendControl("system::get_members", data);
        } catch (Exception ignored) {
        }
        return future;
    }

    /**
     * Re-open the socket with {@code newPrimaryId} as the primary channel,
     * keeping every other subscription. Frames in flight during the swap may be
     * missed.
     */
    public void migratePrimary(String newPrimaryId, String endpoint, String newUuid, String newJwt) {
        migrating = true;
        cancelPendingReconnect();
        reconnectAttempt = 0;

        WebSocket old = ws;
        if (old != null) {
            try {
                old.cancel();
            } catch (Exception ignored) {
            }
        }

        primaryChannelId = newPrimaryId;
        Channel newPrimary = channels.get(newPrimaryId);
        if (newPrimary != null) {
            newPrimary.subscribeParams = null;
        }
        if (newUuid != null) {
            primaryUuid = newUuid;
        }
        primaryJwt = newJwt;

        connected = false;
        connect(endpoint);
        migrating = false;
    }

    public void close() {
        shouldReconnect = false;
        cancelPendingReconnect();
        try {
            if (ws != null) {
                ws.close(NORMAL_CLOSURE_STATUS, null);
            }
        } catch (Exception ignored) {
        }
        scheduler.shutdownNow();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    // ===== Socket lifecycle =====

    private void connect(String endpoint) {
        logger.log("PieSocket: opening shared v4 connection: " + endpoint);
        Request request = new Request.Builder().url(endpoint).build();
        this.ws = httpClient.newWebSocket(request, this);
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        connected = true;
        shouldReconnect = true;
        reconnectAttempt = 0;

        // Replay every secondary subscription (reconnect / primary migration).
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            if (isPrimary(entry.getKey())) {
                continue;
            }
            JSONObject params = entry.getValue().subscribeParams;
            if (params != null) {
                sendControl("system::subscribe", params);
            }
        }

        // Replay subscribes that were still in flight across a reconnect.
        for (PendingControl entry : pending.values()) {
            if (entry.params != null) {
                sendControl("system::subscribe", entry.params);
            }
        }

        if (!openedOnce) {
            openedOnce = true;
            if (onPrimaryConnected != null) {
                onPrimaryConnected.run();
            }
        }

        Channel primary = channels.get(primaryChannelId);
        if (primary != null) {
            primary.onOpen(webSocket, response);
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        JSONObject obj;
        try {
            obj = new JSONObject(text);
        } catch (Exception e) {
            Channel primary = channels.get(primaryChannelId);
            if (primary != null) {
                primary.onMessage(webSocket, text);
            }
            return;
        }

        String event = obj.optString("event", null);

        if ("system::subscribe_success".equals(event)
                || "system::subscribe_error".equals(event)
                || "system::unsubscribe_success".equals(event)
                || "system::unsubscribe_error".equals(event)) {
            settleControl(event, obj);
            return;
        }

        if ("system::member_list_error".equals(event)) {
            JSONObject data = obj.optJSONObject("data");
            String channelId = data != null ? data.optString("channel", primaryChannelId) : primaryChannelId;
            String err = data != null ? data.optString("error", "Could not fetch members") : "Could not fetch members";
            settleMemberRequests(channelId, new PieSocketException(err));
            return;
        }

        String channelId = obj.optString("system::channel", null);
        if (channelId == null) {
            JSONObject data = obj.optJSONObject("data");
            if (data != null) {
                channelId = data.optString("channel", null);
            }
        }
        if (channelId == null) {
            channelId = primaryChannelId;
        }

        Channel channel = channels.get(channelId);
        if (channel == null) {
            channel = channels.get(primaryChannelId);
        }
        if (channel == null) {
            return;
        }

        channel.onMessage(webSocket, text);

        // Only settle under `channelId` if that's genuinely the channel this
        // frame resolved to (not a fallback to primary after a detach).
        if ("system::member_list".equals(event) && channels.get(channelId) == channel) {
            settleMemberRequestsWithMembers(channelId, channel.getAllMembers());
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        // v4 never uses raw binary frames inbound — the server always wraps
        // binary as a system::binary JSON text event. Ignore defensively.
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        try {
            webSocket.close(NORMAL_CLOSURE_STATUS, null);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        handleClose();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        logger.log("PieSocket: connection error: " + t.getMessage());

        if (response != null && (response.code() == 401 || response.code() == 403)) {
            // Auth rejection during the WebSocket upgrade — retrying with the
            // same credentials would just fail again, forever. Give up instead
            // of looping (even with backoff) against a permanent rejection.
            shouldReconnect = false;
        }

        if (!connected && onPrimaryError != null) {
            onPrimaryError.call(t);
        }

        for (Channel channel : channels.values()) {
            PieSocketEvent event = new PieSocketEvent("system:error");
            event.setData(String.valueOf(t.getMessage()));
            channel.fireEvent(event);
        }

        handleClose();
    }

    private void handleClose() {
        connected = false;

        for (Channel channel : channels.values()) {
            channel.fireEvent(new PieSocketEvent("system:closed"));
        }

        if (shouldReconnect && !migrating && autoReconnectEnabled()) {
            scheduleReconnect();
        }
    }

    private boolean autoReconnectEnabled() {
        Boolean v = options.getAutoReconnect();
        return v == null || v;
    }

    /** Backs off exponentially with jitter instead of reconnecting immediately. */
    private void scheduleReconnect() {
        cancelPendingReconnect();
        long delay = nextReconnectDelayMs();
        logger.log("PieSocket: reconnecting multiplexed connection in " + delay + "ms");
        try {
            pendingReconnect = scheduler.schedule(() -> {
                if (shouldReconnect && !migrating) {
                    connect(Channel.buildUrl(primaryChannelId, options, primaryUuid, primaryJwt));
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // close() shut the scheduler down concurrently — nothing to reconnect.
        }
    }

    private long nextReconnectDelayMs() {
        long capped = Math.min(RECONNECT_MAX_DELAY_MS,
                (long) (RECONNECT_BASE_DELAY_MS * Math.pow(2, reconnectAttempt)));
        reconnectAttempt++;
        return ThreadLocalRandom.current().nextLong(capped + 1);
    }

    private void cancelPendingReconnect() {
        if (pendingReconnect != null) {
            pendingReconnect.cancel(false);
            pendingReconnect = null;
        }
    }

    // ===== Internals =====

    private void settleControl(String event, JSONObject message) {
        JSONObject data = message.optJSONObject("data");
        String channelId = data != null ? data.optString("channel", null) : null;
        if (channelId == null) {
            return;
        }

        PendingControl entry = pending.remove(channelId);
        if (entry == null) {
            return;
        }
        entry.timer.cancel(false);

        if (event.endsWith("_success")) {
            if (!entry.future.isDone()) {
                entry.future.complete(null);
            }
        } else {
            if (!entry.future.isDone()) {
                String err = data != null ? data.optString("error", event) : event;
                entry.future.completeExceptionally(new PieSocketException(err));
            }
        }
    }

    private void settleMemberRequests(String channelId, Throwable error) {
        List<CompletableFuture<JSONArray>> list = memberRequests.remove(channelId);
        if (list == null) {
            return;
        }
        for (CompletableFuture<JSONArray> future : list) {
            if (!future.isDone()) {
                future.completeExceptionally(error);
            }
        }
    }

    private void settleMemberRequestsWithMembers(String channelId, JSONArray members) {
        List<CompletableFuture<JSONArray>> list = memberRequests.remove(channelId);
        if (list == null) {
            return;
        }
        for (CompletableFuture<JSONArray> future : list) {
            if (!future.isDone()) {
                future.complete(members);
            }
        }
    }

    private static class PendingControl {
        final CompletableFuture<Void> future;
        final ScheduledFuture<?> timer;
        final JSONObject params;

        PendingControl(CompletableFuture<Void> future, ScheduledFuture<?> timer, JSONObject params) {
            this.future = future;
            this.timer = timer;
            this.params = params;
        }
    }
}
