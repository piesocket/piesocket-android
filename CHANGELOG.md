# Changelog

## 7.0.0

Version realigned to match the other PieSocket client SDKs (piesocket-js,
piesocket-flutter). This is the release that follows `2.1.0` — the jump is
version-numbering only, there are no extra breaking changes beyond the ones
listed below.

Add **v4 protocol** support and **PieRTC** (WebRTC video/audio rooms) — the
Android counterparts of the same features in piesocket-js and
piesocket-flutter. v3 usage is unchanged and still the default.

### v4 — multi-channel over one connection

Set `options.setVersion("4")` to share a **single** WebSocket across every
`join()` call. `join()` still returns a `Channel` synchronously — the
`system::subscribe` control frame is sent in the background.

- New `Connection` class owns the shared socket; secondary channels ride it
  tagged with `system::channel`, inbound frames route back by the same key.
- **Delta presence.** The full roster arrives once as `system::member_list`;
  after that the member list is kept in sync from `system::member_joined` /
  `system::member_left` deltas. `channel.refreshMembers()` re-syncs on demand
  (returns a `CompletableFuture<JSONArray>`).
- **All v4 system events are double-colon** (`system::member_joined`, …),
  unlike v3's single-colon `system:` events.
- **`channel.publishEvent(event, data, meta)`** sends a structured payload
  (`JSONObject` / `JSONArray` / primitive) without pre-stringifying it.
- **Binary.** Any inbound binary frame arrives as a `system::binary` event
  whose `data` is a base64 string. `channel.sendBinary(byte[])` sends a raw
  binary frame (primary channel only) that the server re-wraps as
  `system::binary` for every other client — JS/Flutter peers included.
- **Guarded channels** (`private-` prefix / `setForceAuth(true)`) resolve
  their JWT from `authEndpoint` in the background without `join()` blocking.

### PieRTC — WebRTC rooms (v4 only)

Programmable WebRTC over v4, built on
[`io.github.webrtc-sdk:android`](https://github.com/webrtc-sdk/android)
(bundled). Signalling uses its own `rtc::` namespace — a plain relay, so an
Android, a Flutter and a JS/web client can share the same room.

```java
PieRTCOptions rtc = new PieRTCOptions(getApplicationContext());
rtc.video = true;
rtc.audio = true;
rtc.onLocalVideo = (stream, pieRTC) -> { /* attach stream.videoTracks to a renderer */ };
rtc.onParticipantJoined = (uuid, stream) -> { /* attach remote stream */ };
rtc.onParticipantLeft = uuid -> { /* remove renderer */ };

Channel room = piesocket.join("video-room", rtc);
```

- `PieRTCOptions.context` is **required** — pass the application context.
- `pieRTC.getEglBaseContext()` feeds `SurfaceViewRenderer.init(...)`.
- **`pieRTC.shareScreen()` is one call** — the SDK asks for the
  `MediaProjection` permission (a transparent activity it ships), runs the
  foreground service Android requires, builds the screen track and
  renegotiates it onto every peer. `pieRTC.stopScreenShare()` stops it; the
  SDK also stops when the user taps the system "Stop sharing" control, firing
  `onScreenSharingStopped` (with this client's own uuid) and publishing
  `rtc::stopped_screen`. The `<service>`, the transparent activity and the
  `FOREGROUND_SERVICE*` permissions are in this SDK's manifest and merge into
  the host app automatically — no manifest edits needed.
- Call `pieRTC.dispose()` (or `piesocket.leave(room)`) to release native
  resources.

### Other

- `PieSocketEventListener` is now an **interface** (Java lambda / Kotlin SAM).
  Anonymous-class usage is unaffected; `extends PieSocketEventListener`
  becomes `implements`.
- okhttp `3.6.0` → `3.12.13`.
- Removed vestigial library resources (`res/values/themes.xml`, …) and slimmed
  `AndroidManifest.xml` to the permissions it needs — this also fixed a
  release-build resource-linking failure.

## 2.1.0

Improvements.
