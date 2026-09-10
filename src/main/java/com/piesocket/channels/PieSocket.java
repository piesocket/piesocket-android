package com.piesocket.channels;

import com.piesocket.channels.misc.AuthResolver;
import com.piesocket.channels.misc.Logger;
import com.piesocket.channels.misc.PieSocketEvent;
import com.piesocket.channels.misc.PieSocketException;
import com.piesocket.channels.misc.PieSocketOptions;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PieSocket {

    public HashMap<String, Channel> rooms;
    public PieSocketOptions options;
    public Logger logger;

    /**
     * The shared v4 socket every multiplexed {@code join()} rides — null under
     * v3, or before the first v4 {@code join()} has opened it.
     */
    public Connection connection;

    /**
     * Set while the primary connection is resolving auth — a {@code join()}
     * racing in during that window attaches once this resolves instead of
     * opening a second primary. Completes with the opened Connection, or null
     * if opening the primary failed.
     */
    private CompletableFuture<Connection> multiplexOpening;

    private static final ExecutorService DEFER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "piesocket-defer");
        t.setDaemon(true);
        return t;
    });

    public PieSocket(PieSocketOptions pieSocketOptions) {
        this.rooms = new HashMap<>();
        this.options = pieSocketOptions;
        this.logger = new Logger(this.options.getEnableLogs());
        validateOptions();
    }

    private void validateOptions() {
        if (this.options.getClusterId() == null) {
            throw new PieSocketException("Cluster ID is not provided");
        }
        if (this.options.getApiKey() == null) {
            throw new PieSocketException("API Key is not provided");
        }
    }

    /**
     * Subscribe to a room. Always returns synchronously — under
     * {@code version: "4"}, the first call opens a shared socket (this room
     * becomes primary) and every later call rides it via a
     * {@code system::subscribe} control frame sent in the background.
     */
    public Channel join(String roomId) {
        return join(roomId, null);
    }

    /**
     * Subscribe to a room as a PieRTC (WebRTC) room — only meaningful under
     * {@code version: "4"}. {@code channel.pieRTC} is attached once the room's
     * connection resolves.
     */
    public Channel join(String roomId, PieRTCOptions rtcOptions) {
        if (this.rooms.containsKey(roomId)) {
            logger.log("Returning existing room instance: " + roomId);
            return this.rooms.get(roomId);
        }

        Channel room = "4".equals(this.options.getVersion())
                ? joinMultiplexed(roomId, rtcOptions)
                : new Channel(roomId, this.options, this.logger);

        this.rooms.put(roomId, room);
        return room;
    }

    private Channel joinMultiplexed(String roomId, PieRTCOptions rtcOptions) {
        if (this.connection != null) {
            return attachSecondary(roomId, this.connection, rtcOptions);
        }

        if (this.multiplexOpening != null) {
            // A primary is already resolving — attach once it's ready.
            Channel channel = new Channel(roomId, this.options, this.logger, null);
            this.multiplexOpening.whenComplete((c, t) -> attachOrRetry(roomId, channel, rtcOptions));
            return channel;
        }

        return openPrimary(roomId, rtcOptions, null);
    }

    private void attachOrRetry(String roomId, Channel channel, PieRTCOptions rtcOptions) {
        if (this.connection != null) {
            resolveAndAttach(roomId, channel, this.connection, rtcOptions);
            return;
        }
        if (this.multiplexOpening != null) {
            this.multiplexOpening.whenComplete((c, t) -> attachOrRetry(roomId, channel, rtcOptions));
            return;
        }
        openPrimary(roomId, rtcOptions, channel);
    }

    private Channel openPrimary(String roomId, PieRTCOptions rtcOptions, Channel existing) {
        Channel resolved = existing != null
                ? existing
                : new Channel(roomId, this.options, this.logger, null);
        CompletableFuture<Connection> completer = new CompletableFuture<>();
        this.multiplexOpening = completer;

        AuthResolver.resolve(roomId, resolved.uuid, this.options, this.logger,
                jwt -> {
                    Connection conn = new Connection(
                            roomId, this.options, this.logger, resolved.uuid, resolved, jwt);
                    resolved.hub = conn;
                    this.connection = conn;
                    if (rtcOptions != null) {
                        resolved.pieRTC = new PieRTC(resolved, rtcOptions, this.logger);
                    }
                    this.multiplexOpening = null;
                    completer.complete(conn);
                },
                error -> {
                    logger.log("PieSocket: auth resolution failed for \"" + roomId + "\": " + error);
                    fireErrorNextTick(resolved, error);
                    this.multiplexOpening = null;
                    completer.complete(null);
                });

        return resolved;
    }

    private Channel attachSecondary(String roomId, Connection conn, PieRTCOptions rtcOptions) {
        Channel channel = new Channel(roomId, this.options, this.logger, conn);
        resolveAndAttach(roomId, channel, conn, rtcOptions);
        return channel;
    }

    private void resolveAndAttach(String roomId, Channel channel, Connection conn, PieRTCOptions rtcOptions) {
        AuthResolver.resolve(roomId, channel.uuid, this.options, this.logger,
                jwt -> {
                    boolean presence = this.options.getPresence() == 1 || roomId.startsWith("presence-");
                    JSONObject params = new JSONObject();
                    try {
                        params.put("channel", roomId);
                        params.put("presence", presence);
                        params.put("uuid", channel.uuid);
                        if (jwt != null) {
                            params.put("jwt", jwt);
                        }
                        if (this.options.getUserId() != null) {
                            params.put("user", this.options.getUserId());
                        }
                    } catch (Exception e) {
                        logger.log("PieSocket: subscribe params error: " + e.getMessage());
                        return;
                    }

                    channel.subscribeParams = params;
                    channel.hub = conn;
                    conn.attachChannel(roomId, channel);
                    if (rtcOptions != null) {
                        channel.pieRTC = new PieRTC(channel, rtcOptions, this.logger);
                    }

                    conn.subscribeChannel(roomId, params).exceptionally(t -> {
                        logger.log("PieSocket: subscribe failed for \"" + roomId + "\": " + t);
                        return null;
                    });
                },
                error -> {
                    logger.log("PieSocket: auth resolution failed for \"" + roomId + "\": " + error);
                    fireErrorNextTick(channel, error);
                });
    }

    private void fireErrorNextTick(Channel channel, Object error) {
        DEFER.execute(() -> {
            PieSocketEvent event = new PieSocketEvent("system:error");
            event.setData(String.valueOf(error));
            channel.fireEvent(event);
        });
    }

    public void leave(String roomId) {
        if (!this.rooms.containsKey(roomId)) {
            logger.log("DISCONNECT: Room does not exist: " + roomId);
            return;
        }

        Channel channel = this.rooms.get(roomId);
        Connection conn = this.connection;

        // Stop any WebRTC media now, regardless of which teardown path runs
        // below (the primary-promotion branch doesn't call channel.disconnect()).
        if (channel.pieRTC != null) {
            PieRTC rtc = channel.pieRTC;
            channel.pieRTC = null;
            rtc.dispose();
        }

        if (conn != null && channel.hub != null) {
            if (roomId.equals(conn.primaryChannelId)) {
                List<String> others = new ArrayList<>();
                for (String id : conn.channels.keySet()) {
                    if (!id.equals(roomId)) {
                        others.add(id);
                    }
                }

                if (others.isEmpty()) {
                    conn.close();
                    this.connection = null;
                } else {
                    String newPrimaryId = others.get(0);
                    Channel newPrimary = conn.channels.get(newPrimaryId);
                    conn.detachChannel(roomId);

                    AuthResolver.resolve(newPrimaryId, newPrimary.uuid, this.options, this.logger,
                            jwt -> {
                                String endpoint = Channel.buildUrl(
                                        newPrimaryId, this.options, newPrimary.uuid, jwt);
                                conn.migratePrimary(newPrimaryId, endpoint, newPrimary.uuid, jwt);
                            },
                            error -> {
                                logger.log("PieSocket: auth resolution failed while promoting \""
                                        + newPrimaryId + "\", closing shared connection: " + error);
                                List<String> remaining = new ArrayList<>(conn.channels.keySet());
                                conn.close();
                                this.connection = null;
                                for (String id : remaining) {
                                    Channel ch = this.rooms.remove(id);
                                    if (ch != null) {
                                        fireErrorNextTick(ch, error);
                                    }
                                }
                            });
                }
            } else {
                channel.disconnect();
            }

            this.rooms.remove(roomId);
            return;
        }

        logger.log("DISCONNECT: Closing room connection: " + roomId);
        channel.disconnect();
        this.rooms.remove(roomId);
    }

    public HashMap<String, Channel> getAllRooms() {
        return this.rooms;
    }
}
