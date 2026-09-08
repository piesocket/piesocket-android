package com.piesocket.channels.misc;

import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

/**
 * No-op {@link SdpObserver} adapter — override only the callbacks you need.
 */
public class SimpleSdpObserver implements SdpObserver {
    @Override
    public void onCreateSuccess(SessionDescription sessionDescription) {
    }

    @Override
    public void onSetSuccess() {
    }

    @Override
    public void onCreateFailure(String s) {
    }

    @Override
    public void onSetFailure(String s) {
    }
}
