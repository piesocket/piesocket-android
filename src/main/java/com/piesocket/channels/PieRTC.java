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
import org.webrtc.CameraVideoCapturer;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PieRTC — programmable WebRTC video/audio rooms over a v4 {@link Channel}.
 *
 * <p>Port of piesocket-js's {@code PieRTC.js} / piesocket-flutter's
 * {@code pie_rtc.dart}, adapted to the {@code org.webrtc} API. Signalling rides
 * the channel via {@link Channel#publishEvent} on the same {@code rtc::}
 * namespace those SDKs use — a plain PieSocket relay, no server-side
 * special-casing — so an Android, a Flutter and a JS client can all be mixed in
 * one room.
 *
 * <p><b>Handshake (collision-free, and not dependent on
 * {@code onRenegotiationNeeded} for the first offer — it is unreliable on
 * mobile):</b>
 * <ul>
 *   <li>Each client announces with {@code rtc::broadcaster} (or
 *       {@code rtc::watcher}), and re-announces whenever a member joins, so a
 *       peer already in the room hears a late joiner.</li>
 *   <li>For every peer pair the client with the larger uuid is the <b>sole
 *       offerer</b>: on hearing a peer it creates the connection and sends an
 *       offer outright; the other side only ever answers, and pokes the offerer
 *       with {@code rtc::request} (and, post-connection,
 *       {@code rtc::renegotiate}).</li>
 *   <li>At most one {@link PeerConnection} per remote peer; ICE candidates that
 *       arrive before the remote description is applied are buffered and
 *       flushed.</li>
 * </ul>
 *
 * <p>Mutating operations are serialised on a single-thread executor;
 * {@code org.webrtc} observer callbacks (a different thread) re-post onto it.
 */
public class PieRTC {

    /** Result callback for {@link #switchCamera(OnCameraSwitch)}. */
    public interface OnCameraSwitch {
        void call(boolean isFrontCamera);
    }

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

    /** Current camera — starts at {@link PieRTCOptions#cameraFacing}. */
    private boolean frontCamera = true;

    /**
     * Set once we have media (or are a no-media room) and have made our first
     * announcement — gates {@link #onMemberJoined()} so we don't announce
     * before we can carry the call.
     */
    private boolean announced = false;
    private volatile boolean disposed = false;

    private final ConcurrentHashMap<String, Participant> participants = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static class Participant {
        PeerConnection rtc;
        /** True when this client is the designated offerer for the peer. */
        boolean amOfferer;
        /** An offer we've sent and not yet had answered. */
        boolean makingOffer;
        /** True once a remote description is applied (candidates can flush). */
        boolean remoteDescriptionSet;
        final List<IceCandidate> pendingCandidates = new ArrayList<>();
        /** Stream ids already surfaced through onParticipantJoined. */
        final Set<String> announcedStreams = new HashSet<>();
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

        this.frontCamera = !"environment".equals(identity.cameraFacing);

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

    /** Current camera — kept in sync by {@link #switchCamera(OnCameraSwitch)}. */
    public boolean isFrontCamera() {
        return frontCamera;
    }

    /**
     * Deterministic, symmetric offerer rule — both sides compute the same
     * answer, by code-unit order (matches Dart {@code String.compareTo} and the
     * JS SDK), so the SDKs agree on the offerer in a mixed room.
     */
    static boolean isOffererByUuid(String self, String peer) {
        return self != null && peer != null && self.compareTo(peer) > 0;
    }

    private boolean amOffererFor(String peer) {
        return isOffererByUuid(channel.uuid, peer);
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

        String[] names = enumerator.getDeviceNames();
        // Preferred facing first.
        for (String name : names) {
            if (enumerator.isFrontFacing(name) == frontCamera) {
                VideoCapturer capturer = enumerator.createCapturer(name, null);
                if (capturer != null) {
                    return capturer;
                }
            }
        }
        for (String name : names) {
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

    /**
     * Flip between the front and rear camera on the live call — the same track
     * keeps streaming from the other lens, no renegotiation. {@code callback}
     * (nullable) receives the new {@link #isFrontCamera()} value.
     */
    public void switchCamera(final OnCameraSwitch callback) {
        executor.execute(() -> {
            if (!(cameraCapturer instanceof CameraVideoCapturer)) {
                if (callback != null) {
                    callback.call(frontCamera);
                }
                return;
            }
            ((CameraVideoCapturer) cameraCapturer).switchCamera(
                    new CameraVideoCapturer.CameraSwitchHandler() {
                        @Override
                        public void onCameraSwitchDone(boolean isFront) {
                            frontCamera = isFront;
                            if (callback != null) {
                                callback.call(isFront);
                            }
                        }

                        @Override
                        public void onCameraSwitchError(String error) {
                            logger.log("PieRTC: switchCamera failed: " + error);
                            if (callback != null) {
                                callback.call(frontCamera);
                            }
                        }
                    });
        });
    }

    /** @see #switchCamera(OnCameraSwitch) */
    public void switchCamera() {
        switchCamera(null);
    }

    // ===== Signalling =====

    void requestPeerVideo() {
        announced = true;
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
     * A member joined the room — re-announce so a peer already here learns
     * about this client (and vice versa), and a late joiner triggers a fresh
     * offer.
     */
    public void onMemberJoined() {
        executor.execute(() -> {
            if (announced) {
                requestPeerVideo();
            }
        });
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
        boolean fromSelf = channel.uuid.equals(from);
        boolean toSelf = channel.uuid.equals(data.optString("to", null));

        switch (eventName) {
            case "rtc::broadcaster":
            case "rtc::watcher":
            case "rtc::request":
                if (!fromSelf) {
                    executor.execute(() -> onPeerSignal(data));
                }
                break;
            case "rtc::stopped_screen":
                if (!fromSelf) {
                    onRemoteScreenStopped(from, data.optString("streamId", null));
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
            case "rtc::renegotiate":
                if (toSelf) {
                    final String peer = from;
                    executor.execute(() -> renegotiate(peer));
                }
                break;
            default:
                break;
        }
    }

    /**
     * A peer announced itself ({@code rtc::broadcaster}/{@code rtc::watcher}) or
     * asked us for an offer ({@code rtc::request}). The whole handshake trigger.
     */
    private void onPeerSignal(JSONObject signal) {
        String from = signal.optString("from", null);
        if (from == null || from.equals(channel.uuid)) {
            return;
        }
        if (amOffererFor(from)) {
            sendOffer(from);
        } else {
            // The peer is the offerer — make sure it knows we're here.
            requestOfferFromPeer();
        }
    }

    /** The peer poked us (its designated offerer) for a fresh offer. */
    private void renegotiate(String from) {
        if (!amOffererFor(from)) {
            return;
        }
        sendOffer(from);
    }

    private Participant ensurePeer(String from) {
        Participant existing = participants.get(from);
        if (existing != null && existing.rtc != null) {
            return existing;
        }
        return createPeer(from);
    }

    private Participant createPeer(final String from) {
        logger.log("PieRTC: creating peer connection for " + from);

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        final Participant participant = new Participant();
        participant.amOfferer = amOffererFor(from);
        participants.put(from, participant);

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
                if (mediaStreams.length == 0) {
                    return;
                }
                announceStream(from, participant, mediaStreams[0]);
            }

            @Override
            public void onAddStream(MediaStream stream) {
                announceStream(from, participant, stream);
            }

            @Override
            public void onRenegotiationNeeded() {
                executor.execute(() -> {
                    // Only after the first connection — the first offer is
                    // sent explicitly, not via this callback.
                    if (!participant.remoteDescriptionSet) {
                        return;
                    }
                    if (participant.amOfferer) {
                        sendOffer(from);
                    } else {
                        channel.publishEvent("rtc::renegotiate", obj(
                                "from", channel.uuid, "to", from), null);
                    }
                });
            }
        });

        if (pc == null) {
            logger.log("PieRTC: failed to create peer connection for " + from);
            participants.remove(from);
            return null;
        }
        participant.rtc = pc;

        if (localStream != null) {
            addStreamTracks(pc, localStream);
        }
        if (displayStream != null) {
            addStreamTracks(pc, displayStream);
        }

        return participant;
    }

    private void announceStream(String from, Participant participant, MediaStream stream) {
        if (stream == null) {
            return;
        }
        if (participant.announcedStreams.add(stream.getId())
                && identity.onParticipantJoined != null) {
            identity.onParticipantJoined.call(from, stream);
        }
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

    private void sendOffer(final String from) {
        final Participant participant = ensurePeer(from);
        if (participant == null || participant.rtc == null || participant.makingOffer) {
            return;
        }
        final PeerConnection pc = participant.rtc;
        PeerConnection.SignalingState state = pc.signalingState();
        if (state != null && state != PeerConnection.SignalingState.STABLE) {
            return;
        }
        participant.makingOffer = true;

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

                    @Override
                    public void onSetFailure(String s) {
                        executor.execute(() -> participant.makingOffer = false);
                    }
                }, desc);
            }

            @Override
            public void onCreateFailure(String s) {
                executor.execute(() -> participant.makingOffer = false);
            }
        }, new MediaConstraints());
    }

    private void createAnswer(JSONObject signal) {
        final String from = signal.optString("from", null);
        if (from == null) {
            return;
        }
        JSONObject sdp = signal.optJSONObject("sdp");
        if (sdp == null) {
            return;
        }
        final String sdpType = sdp.optString("type", "offer");
        if (!"offer".equals(sdpType)) {
            return;
        }
        if (amOffererFor(from)) {
            // We're the offerer for this peer — a crossing offer is spurious.
            logger.log("Ignoring offer from " + from + " — we are the offerer");
            return;
        }

        final Participant participant = ensurePeer(from);
        if (participant == null || participant.rtc == null) {
            return;
        }
        final PeerConnection pc = participant.rtc;

        SessionDescription remote = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdpType), sdp.optString("sdp", null));

        pc.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                executor.execute(() -> {
                    flushCandidates(participant);
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
                });
            }
        }, remote);
    }

    private void handleAnswer(JSONObject signal) {
        final Participant participant = participants.get(signal.optString("from", null));
        if (participant == null || participant.rtc == null) {
            return;
        }
        final PeerConnection pc = participant.rtc;
        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            logger.log("Ignoring answer from " + signal.optString("from", null)
                    + " — not expecting one");
            return;
        }
        JSONObject sdp = signal.optJSONObject("sdp");
        if (sdp == null) {
            return;
        }
        SessionDescription remote = new SessionDescription(
                SessionDescription.Type.fromCanonicalForm(sdp.optString("type", "answer")),
                sdp.optString("sdp", null));

        pc.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                executor.execute(() -> {
                    participant.makingOffer = false;
                    flushCandidates(participant);
                });
            }
        }, remote);
    }

    private void addIceCandidate(JSONObject signal) {
        Participant participant = participants.get(signal.optString("from", null));
        if (participant == null) {
            return;
        }
        JSONObject ice = signal.optJSONObject("ice");
        if (ice == null) {
            return;
        }
        IceCandidate candidate = new IceCandidate(
                ice.optString("sdpMid", null),
                ice.optInt("sdpMLineIndex"),
                ice.optString("candidate", null));

        if (participant.rtc == null || !participant.remoteDescriptionSet) {
            participant.pendingCandidates.add(candidate);
            return;
        }
        participant.rtc.addIceCandidate(candidate);
    }

    private void flushCandidates(Participant participant) {
        participant.remoteDescriptionSet = true;
        if (participant.pendingCandidates.isEmpty() || participant.rtc == null) {
            participant.pendingCandidates.clear();
            return;
        }
        for (IceCandidate candidate : participant.pendingCandidates) {
            participant.rtc.addIceCandidate(candidate);
        }
        participant.pendingCandidates.clear();
    }

    /** A participant left the room — tear down their peer connection. */
    public void removeParticipant(String uuid) {
        executor.execute(() -> {
            Participant participant = participants.remove(uuid);
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

    /**
     * Tear the room down: close every peer connection and stop the local
     * camera/mic (and any screen share) so nothing keeps capturing or streaming
     * after the call ends. Called automatically when the channel is left; safe
     * to call more than once.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
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
                participant.pendingCandidates.clear();
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
