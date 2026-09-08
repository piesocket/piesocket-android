package com.piesocket.channels;

import android.content.Context;

import org.webrtc.MediaStream;

/**
 * Options for a {@link PieRTC} room, set via {@link PieSocket#join}'s PieRTC
 * parameters. {@link #context} is required — Android WebRTC needs it for
 * camera enumeration and {@code PeerConnectionFactory} init; pass the
 * application context.
 */
public class PieRTCOptions {

    public interface OnLocalVideo {
        void call(MediaStream stream, PieRTC pieRTC);
    }

    public interface OnParticipant {
        void call(String uuid, MediaStream stream);
    }

    public interface OnParticipantLeft {
        void call(String uuid);
    }

    public interface OnScreenSharingStopped {
        void call(String uuid, String streamId);
    }

    public Context context;
    public boolean shouldBroadcast = true;
    public boolean video = false;
    public boolean audio = true;

    public OnLocalVideo onLocalVideo;
    public OnParticipant onParticipantJoined;
    public OnParticipantLeft onParticipantLeft;
    public OnScreenSharingStopped onScreenSharingStopped;

    public PieRTCOptions() {
    }

    public PieRTCOptions(Context context) {
        this.context = context;
    }
}
