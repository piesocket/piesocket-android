package com.piesocket.channels;

import com.piesocket.channels.misc.PieSocketEvent;
import com.piesocket.channels.misc.PieSocketEventListener;
import com.piesocket.channels.misc.Logger;
import com.piesocket.channels.misc.PieSocketException;
import com.piesocket.channels.misc.PieSocketOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class Channel extends WebSocketListener implements Callback {

    private static final int NORMAL_CLOSURE_STATUS = 1000;
    public String id;
    public WebSocket ws;
    public String uuid;

    /**
     * Set when this handle rides a shared v4 {@link Connection} instead of
     * owning its own socket. {@link #ws} is unused in that mode.
     */
    public Connection hub;

    /**
     * True for any multiplexed instance, even before {@link #hub} is attached
     * (a guarded primary/secondary sits with {@code hub == null} while its
     * authEndpoint fetch is in flight).
     */
    private boolean isMultiplexed = false;

    /**
     * Control-frame params replayed by {@link Connection} across a reconnect —
     * only meaningful for a secondary (non-primary) multiplexed channel.
     */
    public JSONObject subscribeParams;

    /**
     * Set when this is a PieRTC (WebRTC) room — see {@link PieSocket#join}'s
     * {@code video}/{@code audio}/{@code pieRTC} params. v4 only.
     */
    public PieRTC pieRTC;

    private HashMap<String, ArrayList<PieSocketEventListener>> listeners;
    private Logger logger;
    private PieSocketOptions options;
    private JSONArray members;
    private Boolean shouldReconnect;

    public Channel(String roomId, PieSocketOptions pieSocketOptions, Logger logger) {
        this.listeners = new HashMap<>();
        this.id = roomId;
        this.logger = logger;
        this.options = pieSocketOptions;
        this.uuid = UUID.randomUUID().toString();
        this.shouldReconnect = false;
        this.members = new JSONArray();

        this.connect(roomId);
    }

    public Channel(String webSocketURL, boolean enableLogs) {
        this.listeners = new HashMap<>();
        this.id = "standalone";
        this.logger = new Logger(enableLogs);
        this.uuid = UUID.randomUUID().toString();
        this.shouldReconnect = false;
        this.members = new JSONArray();

        this.options = new PieSocketOptions();
        this.options.setWebSocketEndpoint(webSocketURL);

        this.connect(this.id);
    }

    /**
     * A v4 handle sharing {@code hub}'s socket instead of opening its own — see
     * {@link PieSocket#join} under {@code version: "4"}. The primary channel is
     * live immediately; secondary channels become live once their
     * {@code system::subscribe} control frame is acked.
     */
    public Channel(String channelId, PieSocketOptions pieSocketOptions, Logger logger, Connection hub) {
        this.listeners = new HashMap<>();
        this.id = channelId;
        this.logger = logger;
        this.options = pieSocketOptions;
        this.uuid = UUID.randomUUID().toString();
        this.shouldReconnect = false;
        this.members = new JSONArray();
        this.isMultiplexed = true;
        this.hub = hub;
    }

    public static String buildUrl(String channelId, PieSocketOptions options, String channelUuid, String jwt) {
        if (options.getWebSocketEndpoint() != null) {
            return options.getWebSocketEndpoint();
        }

        String clusterDomain = options.getClusterDomain() != null
                ? options.getClusterDomain()
                : options.getClusterId() + ".piesocket.com";
        String protocol = options.getSsl() ? "wss" : "ws";

        String endpoint = protocol + "://" + clusterDomain + "/v" + options.getVersion() + "/" + channelId
                + "?api_key=" + options.getApiKey()
                + "&notify_self=" + options.getNotifySelf()
                + "&source=androidsdk&v=1&presence=" + options.getPresence();

        if (jwt != null) {
            endpoint = endpoint + "&jwt=" + jwt;
        }
        if (options.getUserId() != null) {
            endpoint = endpoint + "&user=" + options.getUserId();
        }
        endpoint = endpoint + "&uuid=" + channelUuid;

        return endpoint;
    }

    public String buildEndpoint() {
        return buildUrl(this.id, this.options, this.uuid, this.getAuthToken());
    }

    public Boolean isGuarded() {
        if (this.options.getForceAuth()) {
            return true;
        }
        return this.id.startsWith("private-");
    }

    public String getAuthToken() {
        if (this.options.getJwt() != null) {
            return this.options.getJwt();
        }

        if (this.isGuarded()) {
            if (this.options.getAuthEndpoint() != null) {
                getAuthTokenFromServer();
                throw new PieSocketException("JWT not provided, will fetch from authEndpoint.");
            } else {
                throw new PieSocketException(
                        "Neither JWT, nor authEndpoint is provided for private channel authentication.");
            }
        }

        return null;
    }

    public void getAuthTokenFromServer() {
        OkHttpClient okHttpClient = new OkHttpClient();
        Request request = new Request.Builder().url(this.options.getAuthEndpoint()).build();
        okHttpClient.newCall(request).enqueue(this);
    }

    public void connect(String roomId) {
        logger.log("Connecting to: " + roomId);

        try {
            String endpoint = this.buildEndpoint();
            this.logger.log("WebSocket Endpoint: " + endpoint);
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build();

            Request request = new Request.Builder().url(endpoint).build();
            this.ws = client.newWebSocket(request, this);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("will fetch from authEndpoint")) {
                logger.log("Defer connection: " + e.getMessage());
            } else {
                throw e;
            }
        }
    }

    public void disconnect() {
        this.shouldReconnect = false;

        // Tear down WebRTC before dropping the channel, so the camera/mic stop
        // and peer connections close instead of streaming on headless.
        if (this.pieRTC != null) {
            PieRTC rtc = this.pieRTC;
            this.pieRTC = null;
            rtc.dispose();
        }

        if (this.hub != null) {
            // A multiplexed secondary channel has no socket of its own — the
            // primary/promotion dance lives in PieSocket.leave(), which only
            // calls disconnect() for non-primary channels.
            Connection oldHub = this.hub;
            oldHub.unsubscribeChannel(this.id).exceptionally(t -> null);
            oldHub.detachChannel(this.id);
            this.hub = null;
            return;
        }

        if (this.isMultiplexed) {
            // hub not attached yet (still resolving auth) — nothing to close.
            return;
        }

        if (this.ws != null) {
            this.ws.close(NORMAL_CLOSURE_STATUS, null);
        }
    }

    public void reconnect() {
        if (this.shouldReconnect) {
            this.connect(this.id);
        }
    }

    public void listen(String eventName, PieSocketEventListener callback) {
        ArrayList<PieSocketEventListener> callbacks;
        if (this.listeners.containsKey(eventName)) {
            callbacks = listeners.get(eventName);
        } else {
            callbacks = new ArrayList<>();
        }
        callbacks.add(callback);
        listeners.put(eventName, callbacks);
    }

    public void removeListener(String eventName, PieSocketEventListener callback) {
        if (this.listeners.containsKey(eventName)) {
            this.listeners.get(eventName).remove(callback);
        }
    }

    public void removeAllListeners(String eventName) {
        if (this.listeners.containsKey(eventName)) {
            this.listeners.remove(eventName);
        }
    }

    public void publish(PieSocketEvent event) {
        if (this.hub != null) {
            this.hub.send(this.id, event);
            return;
        }
        if (this.isMultiplexed) {
            throw new PieSocketException("Channel \"" + this.id
                    + "\" is not connected yet — its authEndpoint fetch is still in flight.");
        }
        this.ws.send(event.toString());
    }

    /**
     * Publish a structured payload directly, without pre-stringifying it into a
     * {@link PieSocketEvent} first. {@code data}/{@code meta} may be a
     * {@link JSONObject}, {@link JSONArray}, String, or primitive.
     */
    public void publishEvent(String eventName, Object data, Object meta) {
        try {
            JSONObject frame = new JSONObject();
            frame.put("event", eventName);
            if (data != null) {
                // wrap() turns a Map/Collection/array into JSONObject/JSONArray so
                // callers can pass native containers, not just pre-built JSON.
                frame.put("data", JSONObject.wrap(data));
            }
            if (meta != null) {
                frame.put("meta", JSONObject.wrap(meta));
            }
            this.send(frame.toString());
        } catch (Exception e) {
            logger.log("PieSocket: publishEvent failed: " + e.getMessage());
        }
    }

    public void send(String text) {
        if (this.hub != null) {
            this.hub.sendRaw(this.id, text);
            return;
        }
        if (this.isMultiplexed) {
            throw new PieSocketException("Channel \"" + this.id
                    + "\" is not connected yet — its authEndpoint fetch is still in flight.");
        }
        this.ws.send(text);
    }

    /**
     * Send a raw binary WebSocket frame (v4). The server wraps any inbound
     * binary frame as a {@code system::binary} event (base64 {@code data})
     * before relaying it, so a browser/JS peer receives this exactly as it
     * would a binary frame from another JS client — decode with base64.
     *
     * <p>Only supported on a channel's primary connection; a secondary
     * channel's {@code sendBinary} throws.
     */
    public void sendBinary(byte[] bytes) {
        if (this.hub != null) {
            this.hub.sendBinary(this.id, bytes);
            return;
        }
        if (this.isMultiplexed) {
            throw new PieSocketException("Channel \"" + this.id
                    + "\" is not connected yet — its authEndpoint fetch is still in flight.");
        }
        this.ws.send(ByteString.of(bytes));
    }

    /**
     * Re-sync this channel's presence roster from the server (v4 only) via
     * {@code system::get_members}. On v3 resolves with the roster already held.
     */
    public CompletableFuture<JSONArray> refreshMembers() {
        if (this.hub != null) {
            return this.hub.requestMembers(this.id);
        }
        CompletableFuture<JSONArray> future = new CompletableFuture<>();
        future.complete(this.members);
        return future;
    }

    public void fireEvent(PieSocketEvent event) {
        logger.log("Event: " + event.getEvent() + ",  " + event.toString());

        if (this.listeners.containsKey(event.getEvent())) {
            doFireEvents(event.getEvent(), event);
        }
        if (this.listeners.containsKey("*")) {
            doFireEvents("*", event);
        }
    }

    private void doFireEvents(String listenerKey, PieSocketEvent event) {
        ArrayList<PieSocketEventListener> callbacks = new ArrayList<>(this.listeners.get(listenerKey));
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).handleEvent(event);
        }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        PieSocketEvent event = new PieSocketEvent();
        event.setEvent("system:connected");
        this.fireEvent(event);

        this.shouldReconnect = true;
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {

        if (this.listeners.containsKey("system:message")) {
            PieSocketEvent payload = new PieSocketEvent();
            payload.setEvent("system:message");
            payload.setData(text);
            doFireEvents("system:message", payload);
        }

        try {
            JSONObject obj = new JSONObject(text);
            if (obj.has("event")) {
                String eventName = obj.getString("event");

                if ("system:boot".equals(eventName)) {
                    onOpen(webSocket, null);
                    return;
                }

                PieSocketEvent payload = new PieSocketEvent();
                payload.setEvent(eventName);

                if (obj.has("data") && !obj.isNull("data")) {
                    Object data = obj.get("data");
                    payload.setData(data instanceof String ? (String) data : data.toString());
                }
                if (obj.has("meta") && !obj.isNull("meta")) {
                    Object meta = obj.get("meta");
                    payload.setMeta(meta instanceof String ? (String) meta : meta.toString());
                }

                this.handleSystemEvents(payload);
                this.handlePieRTCEvent(eventName, obj.opt("data"));
                this.fireEvent(payload);
            }
            if (obj.has("error")) {
                this.shouldReconnect = false;
                PieSocketEvent payload = new PieSocketEvent();
                payload.setEvent("system:error");
                payload.setData(obj.getString("error"));
                this.fireEvent(payload);
            }
        } catch (Throwable tx) {
            logger.log("Non-json message received: " + text);
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        // Standalone v3 channels don't use inbound binary frames; v4 routes
        // through Connection which wraps binary as system::binary text.
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(NORMAL_CLOSURE_STATUS, null);

        PieSocketEvent event = new PieSocketEvent();
        event.setEvent("system:closed");
        event.setData(reason);
        this.fireEvent(event);

        this.reconnect();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        logger.log("Connection closed");
        PieSocketEvent event = new PieSocketEvent();
        event.setEvent("system:error");
        event.setData(t.getMessage());
        this.fireEvent(event);

        this.reconnect();
    }

    public JSONObject getMemberByUUID(String uuid) {
        for (int i = 0; i < this.members.length(); i++) {
            try {
                JSONObject member = this.members.getJSONObject(i);
                if (member.getString("uuid").equals(uuid)) {
                    return member;
                }
            } catch (Exception e) {
                // Ignore errors, member can be a string
            }
        }
        return null;
    }

    public JSONObject getCurrentMember() {
        return this.getMemberByUUID(this.uuid);
    }

    public JSONArray getAllMembers() {
        return this.members;
    }

    public void handleSystemEvents(PieSocketEvent event) {
        // v4 delivers presence as deltas: member_joined / member_left carry
        // only the member that changed, and the full roster arrives once as
        // member_list. v4's system events are double-colon (`system::x`);
        // v3's stay single-colon.
        boolean deltaPresence = "4".equals(this.options.getVersion());
        String memberListEvent = deltaPresence ? "system::member_list" : "system:member_list";
        String memberJoinedEvent = deltaPresence ? "system::member_joined" : "system:member_joined";
        String memberLeftEvent = deltaPresence ? "system::member_left" : "system:member_left";

        try {
            String name = event.getEvent();
            if (name == null || event.getData() == null) {
                return;
            }

            if (name.equals(memberListEvent)) {
                JSONObject data = new JSONObject(event.getData());
                this.members = data.optJSONArray("members") != null
                        ? data.getJSONArray("members") : new JSONArray();
            } else if (name.equals(memberJoinedEvent)) {
                JSONObject data = new JSONObject(event.getData());
                if (deltaPresence) {
                    addMember(data.opt("member"));
                } else {
                    this.members = data.optJSONArray("members") != null
                            ? data.getJSONArray("members") : new JSONArray();
                }
                if (this.pieRTC != null) {
                    this.pieRTC.onMemberJoined();
                }
            } else if (name.equals(memberLeftEvent)) {
                JSONObject data = new JSONObject(event.getData());
                if (deltaPresence) {
                    removeMember(data.opt("member"));
                } else {
                    this.members = data.optJSONArray("members") != null
                            ? data.getJSONArray("members") : new JSONArray();
                }
                Object member = data.opt("member");
                if (this.pieRTC != null && member instanceof JSONObject) {
                    String uuid = ((JSONObject) member).optString("uuid", null);
                    if (uuid != null) {
                        this.pieRTC.removeParticipant(uuid);
                    }
                }
            }
        } catch (Exception e) {
            throw new PieSocketException(e.getMessage());
        }
    }

    /**
     * Routes PieRTC's {@code rtc::*} signalling frames. No-op when
     * {@link #pieRTC} isn't attached.
     */
    private void handlePieRTCEvent(String eventName, Object data) {
        if (this.pieRTC == null || !(data instanceof JSONObject)) {
            return;
        }
        this.pieRTC.handleSignal(eventName, (JSONObject) data);
    }

    private String memberKey(Object member) {
        if (member instanceof JSONObject) {
            JSONObject o = (JSONObject) member;
            return o.has("uuid") ? "uuid:" + o.optString("uuid") : "obj:" + o.toString();
        }
        return "val:" + String.valueOf(member);
    }

    private void addMember(Object member) {
        if (member == null) {
            return;
        }
        String key = memberKey(member);
        for (int i = 0; i < this.members.length(); i++) {
            if (memberKey(this.members.opt(i)).equals(key)) {
                return;
            }
        }
        this.members.put(member);
    }

    private void removeMember(Object member) {
        if (member == null) {
            return;
        }
        String key = memberKey(member);
        JSONArray next = new JSONArray();
        for (int i = 0; i < this.members.length(); i++) {
            Object m = this.members.opt(i);
            if (!memberKey(m).equals(key)) {
                next.put(m);
            }
        }
        this.members = next;
    }

    @Override
    public void onFailure(Call call, IOException e) {
        throw new PieSocketException("Auth Token Server Error" + e.getMessage());
    }

    @Override
    public void onResponse(Call call, Response response) throws IOException {
        String responseText = response.body().string();
        this.logger.log("Auth Token Server Response: " + responseText);
        try {
            JSONObject serverResponse = new JSONObject(responseText);
            String jwt = serverResponse.getString("auth");
            if (jwt != null) {
                this.logger.log("Auth token fetched, resuming connection");
                this.options.setJwt(jwt);
                this.connect(this.id);
            }
        } catch (Exception e) {
            throw new PieSocketException("Auth Token Response Parsing Error: " + e.getMessage());
        }
    }
}
