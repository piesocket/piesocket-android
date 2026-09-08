package com.piesocket.channels;

import static org.junit.Assert.assertTrue;

import com.piesocket.channels.misc.PieSocketEvent;
import com.piesocket.channels.misc.PieSocketOptions;

import org.junit.Ignore;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Opens a REAL v4 connection to PieSocket's public `demo` cluster and verifies
 * two clients on one shared socket can see each other — same channels / event
 * names / payload shapes the demo apps (and the JS/Flutter/Swift demos) use.
 *
 * Network-dependent, so {@code @Ignore}d by default — delete the annotation and
 * run {@code ./gradlew testDebugUnitTest --tests '*LiveV4IntegrationTest'} to
 * exercise it against the real cluster.
 */
@Ignore("network-dependent; remove @Ignore to run against the live demo cluster")
public class LiveV4IntegrationTest {

    private static final String KEY = "wDouy4TMvN5Qw4SkhmZ35skbCBKnlv0fDqKQfhmp";

    private PieSocket socket(String user) {
        PieSocketOptions o = new PieSocketOptions();
        o.setClusterId("demo");
        o.setApiKey(KEY);
        o.setVersion("4");
        o.setEnableLogs(false);
        o.setPresence(true);
        o.setUserId(user);
        return new PieSocket(o);
    }

    @Test
    public void twoClientsSeeEachOtherOverOneSharedSocket() throws Exception {
        PieSocket a = socket("alice");
        PieSocket b = socket("bob");

        Channel aGeneral = a.join("general");
        Channel aRandom = a.join("random");   // secondary channel, multiplexed
        Channel bGeneral = b.join("general");

        CountDownLatch bConnected = new CountDownLatch(1);
        CountDownLatch gotMessage = new CountDownLatch(1);

        final Map<String, Object> body = new HashMap<>();
        body.put("text", "hello from android A");
        final Map<String, Object> from = new HashMap<>();
        from.put("from", "alice");

        bGeneral.listen("system:connected", e -> bConnected.countDown());
        bGeneral.listen("chat-message", event -> {
            System.out.println("B received: data=" + event.getData() + " meta=" + event.getMeta());
            if (event.getMeta() != null && event.getMeta().contains("alice")) {
                gotMessage.countDown();
            }
        });

        aGeneral.listen("system:connected", e -> {
            System.out.println("A: #general connected, publishing");
            new Thread(() -> {
                try {
                    Thread.sleep(1500); // let B's subscribe settle
                } catch (InterruptedException ignored) {
                }
                aGeneral.publishEvent("chat-message", body, from);
                aRandom.publishEvent("chat-message", body, from);
            }).start();
        });

        assertTrue("B never connected", bConnected.await(15, TimeUnit.SECONDS));
        assertTrue("B never received A's #general message",
                gotMessage.await(20, TimeUnit.SECONDS));

        a.leave("general");
        a.leave("random");
        b.leave("general");
    }
}
