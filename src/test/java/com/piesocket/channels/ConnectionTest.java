package com.piesocket.channels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.piesocket.channels.misc.Logger;
import com.piesocket.channels.misc.PieSocketEvent;
import com.piesocket.channels.misc.PieSocketException;
import com.piesocket.channels.misc.PieSocketOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ConnectionTest {

    private PieSocketOptions v4Options() {
        PieSocketOptions o = new PieSocketOptions();
        o.setVersion("4");
        o.setEnableLogs(false);
        return o;
    }

    private Connection testConn(List<String> sent) {
        return new Connection("room-1", v4Options(), new Logger(false), sent::add);
    }

    // ===== Outbound tagging =====

    @Test
    public void send_tagsSystemChannelForSecondaryNotPrimary() throws Exception {
        List<String> sent = new ArrayList<>();
        Connection conn = testConn(sent);

        conn.send("room-1", new PieSocketEvent("chat").setData("hi"));
        conn.send("room-2", new PieSocketEvent("chat").setData("hi"));

        JSONObject primary = new JSONObject(sent.get(0));
        JSONObject secondary = new JSONObject(sent.get(1));

        assertFalse(primary.has("system::channel"));
        assertEquals("room-2", secondary.getString("system::channel"));
    }

    @Test
    public void sendRaw_tagsJsonStringTheSameWay() throws Exception {
        List<String> sent = new ArrayList<>();
        Connection conn = testConn(sent);

        conn.sendRaw("room-2", new JSONObject().put("event", "ping").toString());

        JSONObject frame = new JSONObject(sent.get(0));
        assertEquals("room-2", frame.getString("system::channel"));
    }

    @Test
    public void sendRaw_sendsNonJsonVerbatim() {
        List<String> sent = new ArrayList<>();
        Connection conn = testConn(sent);

        conn.sendRaw("room-2", "plain text");
        assertEquals("plain text", sent.get(0));
    }

    @Test
    public void sendBinary_emitsRawBytesForPrimary() {
        List<byte[]> sentBytes = new ArrayList<>();
        Connection conn = new Connection("room-1", v4Options(), new Logger(false), s -> {});
        conn.sendBinaryOverride = sentBytes::add;

        conn.sendBinary("room-1", new byte[]{1, 2, 3, 4});

        assertEquals(1, sentBytes.size());
        assertEquals(4, sentBytes.get(0).length);
        assertEquals(3, sentBytes.get(0)[2]);
    }

    @Test
    public void sendBinary_throwsForSecondary() {
        Connection conn = new Connection("room-1", v4Options(), new Logger(false), s -> {});
        conn.sendBinaryOverride = b -> {};
        try {
            conn.sendBinary("room-2", new byte[]{1, 2, 3});
            fail("expected PieSocketException");
        } catch (PieSocketException expected) {
            // ok
        }
    }

    // ===== Subscribe control frames =====

    @Test
    public void subscribeChannel_sendsSubscribeOnceConnected() throws Exception {
        List<String> sent = new ArrayList<>();
        Connection conn = testConn(sent);

        conn.onOpen(null, null);
        JSONObject params = new JSONObject().put("channel", "room-2").put("uuid", "u1");
        conn.subscribeChannel("room-2", params);

        assertEquals(1, sent.size());
        JSONObject frame = new JSONObject(sent.get(0));
        assertEquals("system::subscribe", frame.getString("event"));
        assertEquals("room-2", frame.getJSONObject("data").getString("channel"));
    }

    @Test
    public void subscribeChannel_defersUntilConnectedThenReplays() throws Exception {
        List<String> sent = new ArrayList<>();
        Connection conn = testConn(sent);

        conn.subscribeChannel("room-2", new JSONObject().put("channel", "room-2"));
        assertTrue(sent.isEmpty());

        conn.onOpen(null, null);
        assertEquals(1, sent.size());
        assertEquals("system::subscribe", new JSONObject(sent.get(0)).getString("event"));
    }

    @Test
    public void subscribeChannel_resolvesOnSubscribeSuccess() throws Exception {
        List<String> sent = new ArrayList<>();
        Connection conn = testConn(sent);
        conn.onOpen(null, null);

        CompletableFuture<Void> f = conn.subscribeChannel("room-2",
                new JSONObject().put("channel", "room-2"));

        conn.onMessage((okhttp3.WebSocket) null, new JSONObject()
                .put("event", "system::subscribe_success")
                .put("data", new JSONObject().put("channel", "room-2"))
                .toString());

        f.get(1, TimeUnit.SECONDS); // resolves without throwing
    }

    @Test
    public void subscribeChannel_rejectsOnSubscribeError() throws Exception {
        List<String> sent = new ArrayList<>();
        Connection conn = testConn(sent);
        conn.onOpen(null, null);

        CompletableFuture<Void> f = conn.subscribeChannel("room-2",
                new JSONObject().put("channel", "room-2"));

        conn.onMessage((okhttp3.WebSocket) null, new JSONObject()
                .put("event", "system::subscribe_error")
                .put("data", new JSONObject().put("channel", "room-2").put("error", "nope"))
                .toString());

        try {
            f.get(1, TimeUnit.SECONDS);
            fail("expected rejection");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof PieSocketException);
        }
    }

    @Test
    public void unsubscribeChannel_rejectsForPrimary() {
        Connection conn = testConn(new ArrayList<>());
        CompletableFuture<Void> f = conn.unsubscribeChannel("room-1");
        assertTrue(f.isCompletedExceptionally());
    }

    // ===== Inbound routing =====

    @Test
    public void routesAppFrameToChannelNamedBySystemChannel() throws Exception {
        Connection conn = testConn(new ArrayList<>());
        PieSocketOptions opts = v4Options();
        Channel primary = new Channel("room-1", opts, new Logger(false), conn);
        Channel secondary = new Channel("room-2", opts, new Logger(false), conn);
        conn.attachChannel("room-1", primary);
        conn.attachChannel("room-2", secondary);

        AtomicReference<String> gotOnPrimary = new AtomicReference<>();
        AtomicReference<String> gotOnSecondary = new AtomicReference<>();
        primary.listen("chat", e -> gotOnPrimary.set(e.getData()));
        secondary.listen("chat", e -> gotOnSecondary.set(e.getData()));

        conn.onMessage((okhttp3.WebSocket) null, new JSONObject()
                .put("event", "chat")
                .put("data", "hello")
                .put("system::channel", "room-2")
                .toString());

        assertEquals("hello", gotOnSecondary.get());
        assertEquals(null, gotOnPrimary.get());
    }

    @Test
    public void fallsBackToPrimaryWhenNoSystemChannel() throws Exception {
        Connection conn = testConn(new ArrayList<>());
        Channel primary = new Channel("room-1", v4Options(), new Logger(false), conn);
        conn.attachChannel("room-1", primary);

        AtomicReference<String> got = new AtomicReference<>();
        primary.listen("chat", e -> got.set(e.getData()));

        conn.onMessage((okhttp3.WebSocket) null, new JSONObject()
                .put("event", "chat").put("data", "hi").toString());

        assertEquals("hi", got.get());
    }

    @Test
    public void systemBinaryCarriesBase64DataThrough() throws Exception {
        Connection conn = testConn(new ArrayList<>());
        Channel primary = new Channel("room-1", v4Options(), new Logger(false), conn);
        conn.attachChannel("room-1", primary);

        AtomicReference<String> got = new AtomicReference<>();
        primary.listen("system::binary", e -> got.set(e.getData()));

        conn.onMessage((okhttp3.WebSocket) null, new JSONObject()
                .put("event", "system::binary").put("data", "aGVsbG8=").toString());

        assertEquals("aGVsbG8=", got.get());
    }

    @Test
    public void requestMembers_sendsGetMembersAndResolvesOnMemberList() throws Exception {
        List<String> sent = new ArrayList<>();
        Connection conn = testConn(sent);
        Channel primary = new Channel("room-1", v4Options(), new Logger(false), conn);
        conn.attachChannel("room-1", primary);

        CompletableFuture<JSONArray> f = conn.requestMembers("room-1");

        JSONObject getFrame = new JSONObject(sent.get(0));
        assertEquals("system::get_members", getFrame.getString("event"));

        conn.onMessage((okhttp3.WebSocket) null, new JSONObject()
                .put("event", "system::member_list")
                .put("data", new JSONObject()
                        .put("channel", "room-1")
                        .put("members", new JSONArray().put(new JSONObject().put("uuid", "a"))))
                .toString());

        JSONArray members = f.get(1, TimeUnit.SECONDS);
        assertEquals(1, members.length());
        assertEquals("a", members.getJSONObject(0).getString("uuid"));
    }
}
