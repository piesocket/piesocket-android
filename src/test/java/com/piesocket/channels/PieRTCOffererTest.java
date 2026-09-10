package com.piesocket.channels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The offerer rule is the one cross-SDK-critical invariant in the PieRTC
 * handshake: for every peer pair exactly one side creates offers, decided
 * from the two uuids, and Android / Flutter / JS must all agree on which.
 */
public class PieRTCOffererTest {

    @Test
    public void largerUuidIsTheOfferer() {
        assertTrue(PieRTC.isOffererByUuid("zzz", "aaa"));
        assertFalse(PieRTC.isOffererByUuid("aaa", "zzz"));
    }

    @Test
    public void theRuleIsSymmetric() {
        String a = "3f7c1a90-1111-4b2c-8a1e-000000000001";
        String b = "a1b2c3d4-2222-4b2c-8a1e-000000000002";
        assertTrue(PieRTC.isOffererByUuid(a, b) != PieRTC.isOffererByUuid(b, a));
    }

    @Test
    public void nullsAreNeverTheOfferer() {
        assertFalse(PieRTC.isOffererByUuid(null, "aaa"));
        assertFalse(PieRTC.isOffererByUuid("aaa", null));
    }
}
