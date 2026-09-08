package com.piesocket.channels;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;

import com.piesocket.channels.misc.Logger;
import com.piesocket.channels.misc.RtcObserver;
import com.piesocket.channels.misc.SimpleSdpObserver;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PieRTC — programmable WebRTC video/audio rooms over a v4 {@link Channel}.
 *
 * <p>Method-for-method port of piesocket-js's {@code PieRTC.js} /
 * piesocket-flutter's {@code pie_rtc.dart}, adapted to the {@code org.webrtc}
 * API. Signalling rides the channel via {@link Channel#publishEvent} on the
 * same {@code rtc::} namespace those SDKs use — a plain PieSocket relay, no
 * server-side special-casing — so an Android, a Flutter and a JS client can
 * all be mixed in one room.
 *
 * <p>Mutating operations are serialised on a single-thread executor;
 * {@code org.webrtc} observer callbacks (a different thread) re-post onto it.
 */
public class PieRTC {

    final Channel channel;
    final PieRTCOptions identity;
    private final Logger logger;
    private final Context context;

    private static volatile boolean factoryInitialized = false;

    private final EglBase eglBase;
    private final PeerConnectionFactory factory;
    private final List<PeerConnection.IceServer> iceServers;

    public MediaStream localStream;
    public MediaStream displayStream;

    private VideoCapturer cameraCapturer;
    private VideoCapturer screenCapturer;
    private SurfaceTextureHelper cameraHelper;
    private SurfaceTextureHelper screenHelper;
    private VideoTrack screenTrack;

    private final Map<String, Participant> participants = new ConcurrentHashMap<>();
    private final Map<String, Boolean> isNegotiating = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static class Participant {
        PeerConnection rtc;
    }

    public PieRTC(Channel channel, PieRTCOptions identity, Logger logger) {
        this.channel = channel;
        this.identity = identity;
        this.logger = logger;
        this.context = identity.context;

        if (this.context == null) {
            throw new IllegalStateException(
                    "PieRTCOptions.context is required — pass the application Context.");
        }

        ensureFactoryInitialized(this.context);
        this.eglBase = EglBase.create();
        this.factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                        new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        this.iceServers = java.util.Arrays.asList(
                PeerConnection.IceServer.builder("stun:stun.stunprotocol.org:3478").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        logger.log("Initializing video room");
        executor.execute(this::init);
    }

    private static synchronized void ensureFactoryInitialized(Context context) {
        if (factoryInitialized) {
            return;
        }
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.getApplicationContext())
                        .createInitializationOptions());
        factoryInitialized = true;
    }

    /** The shared EGL context — pass to {@code SurfaceViewRenderer.init(...)}. */
    public EglBase.Context getEglBaseContext() {
        return eglBase.getEglBaseContext();
    }

    // ===== Local media =====

    private void init() {
        if (!identity.video && !identity.audio) {
            requestPeerVideo();
            return;
        }
        try {
            MediaStream stream = createLocalStream();
            getUserMediaSuccess(stream);
        } catch (Exception e) {
            logger.log("PieRTC: local media setup failed: " + e.getMessage());
        }
    }

    private MediaStream createLocalStream() {
        MediaStream stream = factory.createLocalMediaStream("PIE_local");

        if (identity.audio) {
            AudioSource audioSource = factory.createAudioSource(new MediaConstraints());
            AudioTrack audioTrack = factory.createAudioTrack("PIE_audio", audioSource);
            stream.addTrack(audioTrack);
        }

        if (identity.video) {
            cameraCapturer = createCameraCapturer();
            if (cameraCapturer != null) {
                VideoSource videoSource = factory.createVideoSource(cameraCapturer.isScreencast());
                cameraHelper = SurfaceTextureHelper.create("PIE_capture", eglBase.getEglBaseContext());
                cameraCapturer.initialize(cameraHelper, context, videoSource.getCapturerObserver());
                cameraCapturer.startCapture(1280, 720, 30);
                VideoTrack videoTrack = factory.createVideoTrack("PIE_video", videoSource);
                stream.addTrack(videoTrack);
            } else {
                logger.log("PieRTC: no camera available");
            }
        }

        return stream;
    }

    private VideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(context)
                ? new Camera2Enumerator(context)
                : new Camera1Enumerator(true);

        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) {
                VideoCapturer capturer = enumerator.createCapturer(name, null);
                if (capturer != null) {
                    return capturer;
                }
            }
        }
        for (String name : enumerator.getDeviceNames()) {
            VideoCapturer capturer = enumerator.createCapturer(name, null);
            if (capturer != null) {
                return capturer;
            }
        }
        return null;
    }

    private void getUserMediaSuccess(MediaStream stream) {
        localStream = stream;
        if (identity.onLocalVideo != null) {
            identity.onLocalVideo.call(stream, this);
        }
        requestPeerVideo();
    }

    // ===== Signalling =====

    void requestPeerVideo() {
        String eventName = identity.shouldBroadcast ? "rtc::broadcaster" : "rtc::watcher";
        channel.publishEvent(eventName, obj(
                "from", channel.uuid,
                "isBroadcasting", identity.shouldBroadcast), null);
    }

    void requestOfferFromPeer() {
        channel.publishEvent("rtc::request", obj(
                "from", channel.uuid,
                "isBroadcasting", identity.shouldBroadcast), null);
    }

    /**
     * Handle an inbound {@code rtc::*} signalling frame — {@link Channel}
     * forwards every event here; non-{@code rtc::} events are ignored.
     */
    public void handleSignal(String eventName, JSONObject data) {
        if (eventName == null || !eventName.startsWith("rtc::") || data == null) {
            return;
        }
        String from = data.optString("from", null);
        String to = data.optString("to", null);
        boolean fromSelf = channel.uuid.equals(from);
        boolean toSelf = channel.uuid.equals(to);

        switch (eventName) {
            case "rtc::broadcaster":
                if (!fromSelf) {
                    executor.execute(this::requestOfferFromPeer);
                }
                break;
            case "rtc::stopped_screen":
                if (!fromSelf) {
                    onRemoteScreenStopped(from, data.optString("streamId", null));
                }
                break;
            case "rtc::watcher":
            case "rtc::request":
                if (!fromSelf) {
                    executor.execute(() -> shareVideo(data, true));
                }
                break;
            case "rtc::candidate":
                if (toSelf) {
                    executor.execute(() -> addIceCandidate(data));
                }
                break;
            case "rtc::offer":
                if (toSelf) {
                    executor.execute(() -> createAnswer(data));
                }
                break;
            case "rtc::answer":
                if (toSelf) {
                    executor.execute(() -> handleAnswer(data));
                }
                break;
            default:
                break;
        }
    }

    private void shareVideo(JSONObject signal, boolean isCaller) {
        final String from = signal.optString("from", null);
        if (from == null) {
            return;
        }

        if (!identity.shouldBroadcast && isCaller && !signal.optBoolean("isBroadcasting", false)) {
            logger.log("Refusing to call, denied broadcast request");
            return;
        }

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        final Participant participant = new Participant();

        PeerConnection pc = factory.createPeerConnection(config, new RtcObserver() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                channel.publishEvent("rtc::candidate", obj(
                        "from", channel.uuid,
                        "to", from,
                        "ice", obj(
                                "candidate", candidate.sdp,
                                "sdpMid", candidate.sdpMid,
                                "sdpMLineIndex", candidate.sdpMLineIndex)), null);
            }

            @Override
            public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
                MediaStreamTrack track = receiver.track();
                if (!(track instanceof VideoTrack) || mediaStreams.length == 0) {
                    return;
                }
                if (identity.onParticipantJoined != null) {
                    identity.onParticipantJoined.call(from, mediaStreams[0]);
                }
            }

            @Override
            public void onAddStream(MediaStream stream) {
                if (!stream.videoTracks.isEmpty() && identity.onParticipantJoined != null) {
                    identity.onParticipantJoined.call(from, stream);
                }
            }

            @Override
            public void onSignalingChange(PeerConnection.SignalingState state) {
                isNegotiating.put(from, state != PeerConnection.SignalingState.STABLE);
            }

            @Override
            public void onRenegotiationNeeded() {
                executor.execute(() -> sendVideoOffer(from));
            }
        });

        if (pc == null) {
            logger.log("PieRTC: failed to create peer connection for " + from);
            return;
        }
        participant.rtc = pc;

        if (localStream != null) {
            addStreamTracks(pc, localStream);
        }
        if (displayStream != null) {
            addStreamTracks(pc, displayStream);
        }

        isNegotiating.put(from, false);
        participants.put(from, participant);
    }

    private void addStreamTracks(PeerConnection pc, MediaStream stream) {
        List<String> ids = Collections.singletonList(stream.getId());
        for (VideoTrack track : stream.videoTracks) {
            pc.addTrack(track, ids);
        }
        for (AudioTrack track : stream.audioTracks) {
            pc.addTrack(track, ids);
        }
    }

    private void sendVideoOffer(String from) {
        Participant participant = participants.get(from);
        if (participant == null || participant.rtc == null) {
            return;
        }
        if (Boolean.TRUE.equals(isNegotiating.get(from))) {
            logger.log("SKIP nested negotiations");
            return;
        }
        isNegotiating.put(from, true);

        final PeerConnection pc = participant.rtc;
        pc.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription desc) {
                pc.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        channel.publishEvent("rtc::offer", obj(
                                "from", channel.uuid,
                                "to", from,
                                "sdp", obj(
                                        "type", desc.type.canonicalForm(),
                                        "sdp", desc.description)), null);
                    }
                }, desc);
            }
        }, new MediaConstraints());
    }

    private void addIceCandidate(JSONObject signal) {
        Participant participant = participants.get(signal.optString("from", null));
        if (participant == null || participant.rtc == null) {
            return;
        }
        JSONObject ice = signal.optJSONObject("ice");
        if (ice == null) {
            return;
        }
        participant.rtc.addIceCandidate(new IceCandidate(
                ice.optString("sdpMid", null),
                ice.optInt("sdpMLineIndex"),
                ice.optString("candidate", null)));
    }

    private void createAnswer(JSONObject signal) {
        final String from = signal.optString("from", null);
        if (from == null) {
            return;
        }

        if (participants.get(from) == null || participants.get(from).rtc == null) {
            logger.log("Starting call in createAnswer");
            shareVideo(signal, false);
        }

        Participant participant = participants.get(from);
        if (participant == null || participant.rtc == null) {
            return;
        }
        final PeerConnection pc = participant.rtc;

        JSONObject sdp = signal.optJSONObject("sdp");
        if (sdp == null) {
            return;
        }
        final String sdpType = sdp.optString("type", "offer");
        SessionDescription remote = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdpType), sdp.optString("sdp", null));

        pc.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                if (!"offer".equals(sdpType)) {
                    return;
                }
                pc.createAnswer(new SimpleSdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription desc) {
                        pc.setLocalDescription(new SimpleSdpObserver() {
                            @Override
                            public void onSetSuccess() {
                                channel.publishEvent("rtc::answer", obj(
                                        "from", channel.uuid,
                                        "to", from,
                                        "sdp", obj(
                                                "type", desc.type.canonicalForm(),
                                                "sdp", desc.description)), null);
                            }
                        }, desc);
                    }
                }, new MediaConstraints());
            }
        }, remote);
    }

    private void handleAnswer(JSONObject signal) {
        Participant participant = participants.get(signal.optString("from", null));
        if (participant == null || participant.rtc == null) {
            return;
        }
        JSONObject sdp = signal.optJSONObject("sdp");
        if (sdp == null) {
            return;
        }
        participant.rtc.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdp.optString("type", "answer")),
                sdp.optString("sdp", null)));
    }

    /** A participant left the room — tear down their peer connection. */
    public void removeParticipant(String uuid) {
        executor.execute(() -> {
            Participant participant = participants.remove(uuid);
            isNegotiating.remove(uuid);
            if (participant != null && participant.rtc != null) {
                try {
                    participant.rtc.dispose();
                } catch (Exception ignored) {
                }
            }
            if (identity.onParticipantLeft != null) {
                identity.onParticipantLeft.call(uuid);
            }
        });
    }

    void onRemoteScreenStopped(String uuid, String streamId) {
        if (identity.onScreenSharingStopped != null) {
            identity.onScreenSharingStopped.call(uuid, streamId);
        }
    }

    // ===== Screen share =====

    /**
     * Start sharing this device's screen with everyone in the room.
     *
     * <p>One call — the SDK asks the system for the {@code MediaProjection}
     * permission (via a transparent activity it ships), runs the foreground
     * service Android requires for screen capture, builds the screen video
     * track and renegotiates it onto every peer connection. The screen track is
     * sent <em>in addition to</em> the camera, so remote peers see both.
     *
     * <p>Call {@link #stopScreenShare()} to stop; the SDK also stops
     * automatically if the user taps the system "Stop sharing" control, and in
     * both cases fires {@link PieRTCOptions#onScreenSharingStopped} with this
     * client's own uuid and publishes {@code rtc::stopped_screen} to the room.
     */
    public void shareScreen() {
        if (displayStream != null || screenCapturer != null) {
            logger.log("PieRTC: screen share already active");
            return;
        }
        final Context appContext = context.getApplicationContext();
        PieScreenCaptureService.start(appContext);
        PieScreenPermissionActivity.launch(context, (resultCode, data) -> {
            if (resultCode != Activity.RESULT_OK || data == null) {
                logger.log("PieRTC: screen capture permission denied");
                PieScreenCaptureService.stop(appContext);
                return;
            }
            executor.execute(() -> beginScreenCapture(data));
        });
    }

    /**
     * Advanced entry point for callers that run the {@code MediaProjection}
     * permission flow themselves — pass the result {@code Intent}. The SDK still
     * runs its own foreground service. Prefer {@link #shareScreen()}.
     */
    public void shareScreen(Intent mediaProjectionPermissionResult) {
        if (mediaProjectionPermissionResult == null) {
            shareScreen();
            return;
        }
        if (displayStream != null || screenCapturer != null) {
            logger.log("PieRTC: screen share already active");
            return;
        }
        PieScreenCaptureService.start(context.getApplicationContext());
        final Intent data = mediaProjectionPermissionResult;
        executor.execute(() -> beginScreenCapture(data));
    }

    private void beginScreenCapture(Intent permissionData) {
        try {
            screenCapturer = new ScreenCapturerAndroid(permissionData, new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    executor.execute(() -> teardownScreenShare(true));
                }
            });

            VideoSource source = factory.createVideoSource(true);
            screenHelper = SurfaceTextureHelper.create("PIE_screen", eglBase.getEglBaseContext());
            screenCapturer.initialize(screenHelper, context, source.getCapturerObserver());
            screenCapturer.startCapture(1280, 720, 15);

            screenTrack = factory.createVideoTrack("PIE_screen", source);
            MediaStream stream = factory.createLocalMediaStream("PIE_screen_stream");
            stream.addTrack(screenTrack);
            displayStream = stream;

            List<String> ids = Collections.singletonList(stream.getId());
            for (Participant participant : participants.values()) {
                if (participant.rtc != null) {
                    participant.rtc.addTrack(screenTrack, ids); // -> onRenegotiationNeeded
                }
            }
        } catch (Exception e) {
            logger.log("PieRTC: screen share failed: " + e.getMessage());
            teardownScreenShare(false);
        }
    }

    /** Stop screen sharing started by {@link #shareScreen()}. */
    public void stopScreenShare() {
        executor.execute(() -> teardownScreenShare(true));
    }

    private void teardownScreenShare(boolean announce) {
        if (displayStream == null && screenCapturer == null) {
            return;
        }
        final String streamId = displayStream != null ? displayStream.getId() : "";

        if (screenTrack != null) {
            for (Participant participant : participants.values()) {
                if (participant.rtc == null) {
                    continue;
                }
                for (RtpSender sender : participant.rtc.getSenders()) {
                    MediaStreamTrack track = sender.track();
                    if (track != null && track.id().equals(screenTrack.id())) {
                        try {
                            participant.rtc.removeTrack(sender); // -> onRenegotiationNeeded
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        stopCapturer(screenCapturer);
        screenCapturer = null;
        if (screenHelper != null) {
            screenHelper.dispose();
            screenHelper = null;
        }
        if (displayStream != null) {
            try {
                displayStream.dispose();
            } catch (Exception ignored) {
            }
            displayStream = null;
        }
        screenTrack = null;

        PieScreenCaptureService.stop(context.getApplicationContext());

        if (announce) {
            channel.publishEvent("rtc::stopped_screen", obj(
                    "from", channel.uuid, "streamId", streamId), null);
            if (identity.onScreenSharingStopped != null) {
                identity.onScreenSharingStopped.call(channel.uuid, streamId);
            }
        }
    }

    // ===== Teardown =====

    public void dispose() {
        executor.execute(() -> {
            teardownScreenShare(false);
            stopCapturer(cameraCapturer);
            for (Participant participant : participants.values()) {
                if (participant.rtc != null) {
                    try {
                        participant.rtc.dispose();
                    } catch (Exception ignored) {
                    }
                }
            }
            participants.clear();
            try {
                if (cameraHelper != null) cameraHelper.dispose();
                if (screenHelper != null) screenHelper.dispose();
                if (localStream != null) localStream.dispose();
                if (displayStream != null) displayStream.dispose();
                factory.dispose();
                eglBase.release();
            } catch (Exception ignored) {
            }
        });
        executor.shutdown();
    }

    private void stopCapturer(VideoCapturer capturer) {
        if (capturer == null) {
            return;
        }
        try {
            capturer.stopCapture();
        } catch (InterruptedException ignored) {
        }
        try {
            capturer.dispose();
        } catch (Exception ignored) {
        }
    }

    // ===== JSON helper =====

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        } catch (Exception ignored) {
        }
        return o;
    }
}
