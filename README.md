# PieSocket Realtime SDK for Android

PieSocket Realtime SDK for Android written in Java.

## Installation
Let's start by adding PieSocket Android SDK as a dependency to your application. 

### Gradle (Kotlin)

```
implementation("com.piesocket:channels-sdk:7.0.0")
```

### Gradle (Java)

```
implementation 'com.piesocket:channels-sdk:7.0.0'
```

### Maven
```
<dependency>
    <groupId>com.piesocket</groupId>
    <artifactId>channels-sdk</artifactId>
    <version>7.0.0</version>
</dependency>
```

## Permissions

Setup manifest permissions as instructed [here](https://piehost.com/docs/3.0/android-websockets#permissions).

## Usage

### Managed PieSocket Server
Use following code to create a Channel with PieSocket's managed WebSocket servers.

Get your API key and Cluster ID here: [Get API Key](https://www.piesocket.com/app/v4/register)

```java
PieSocketOptions options = new PieSocketOptions();
options.setClusterId("demo");
options.setApiKey("VCXCEuvhGcBDP7XhiJJUDvR1e1D3eiVjgZ9VRiaV");

PieSocket piesocket = new PieSocket(options);
Channel channel = piesocket.join("chat-room-1");
```

### Self-hosted PieSocket Server
Use following code to create a Channel with PieSocket self-hosted realtime servers.


```java
PieSocketOptions options = new PieSocketOptions();
options.setClusterDomain("localhost:4001");
options.setSsl(false);

PieSocket piesocket = new PieSocket(options);
Channel channel = piesocket.join("chat-room-1");
```

[PieSocket Channels](https://piesocket.com/channels) is a scalable WebSocket API service with following features:
  - Authentication
  - Private Channels
  - Presence Channels
  - Publish messages with REST API
  - Auto-scalability
  - Webhooks
  - Analytics
  - Authentication
  - Upto 60% cost savings


## v4 — multi-channel over one connection

Set `options.setVersion("4")` to share a **single** WebSocket across every
`join()` call. `join()` still returns a `Channel` synchronously — the
multiplexing happens in the background.

```java
PieSocketOptions options = new PieSocketOptions();
options.setClusterId("demo");
options.setApiKey("VCXCEuvhGcBDP7XhiJJUDvR1e1D3eiVjgZ9VRiaV");
options.setVersion("4");

PieSocket piesocket = new PieSocket(options);
Channel chat = piesocket.join("chat-room");   // opens the socket
Channel alerts = piesocket.join("alerts");    // rides the same socket

chat.listen("message", event -> { /* ... */ });
alerts.publishEvent("ping", new JSONObject().put("at", System.currentTimeMillis()), null);
```

- **`join()` stays synchronous.** Listen for `system:connected` on the
  primary channel (the first `join()`); secondary channels' subscribe acks
  are handled internally, not delivered to their own listeners.
- **Delta presence.** Full roster once as `system::member_list`, then kept in
  sync from `system::member_joined` / `system::member_left`. Call
  `channel.refreshMembers()` (returns a `CompletableFuture<JSONArray>`).
- **All v4 system events are double-colon** (`system::member_joined`, …).
- **`publishEvent(event, data, meta)`** sends a structured payload directly.
- **Binary.** Inbound binary arrives as a `system::binary` event whose
  `data` is a base64 string. `channel.sendBinary(byte[])` sends a raw binary
  frame (primary channel only) — the server re-wraps it as `system::binary`
  for every other client, JS/Flutter peers included.

## PieRTC — WebRTC video/audio rooms (v4 only)

Programmable WebRTC over v4, built on the bundled
`io.github.webrtc-sdk:android`. Signalling rides its own `rtc::` namespace —
a plain relay, so Android, Flutter and JS/web clients interoperate in one
room.

```java
PieRTCOptions rtc = new PieRTCOptions(getApplicationContext());
rtc.video = true;
rtc.audio = true;
rtc.onLocalVideo = (stream, pieRTC) -> { /* stream.videoTracks.get(0).addSink(renderer) */ };
rtc.onParticipantJoined = (uuid, stream) -> { /* attach remote stream */ };
rtc.onParticipantLeft = uuid -> { /* remove renderer */ };

Channel room = piesocket.join("video-room", rtc);
```

- `PieRTCOptions.context` is **required**.
- `pieRTC.getEglBaseContext()` feeds `SurfaceViewRenderer.init(...)`.
- **`pieRTC.shareScreen()`** — one call. The SDK runs the `MediaProjection`
  permission prompt and the foreground service itself (both declared in the
  SDK manifest, merged automatically); `pieRTC.stopScreenShare()` stops.
- `pieRTC.dispose()` / `piesocket.leave(room)` releases native resources.

Manifest permissions for PieRTC:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

## Events
`system:connected` is the event fired when WebSocket connection is ready, get a full list system messages here: [PieSocket System Messages](https://www.piesocket.com/docs/3.0/events#system-events)

## Documentation
For usage examples and more information, refer to: [Official SDK docs](https://www.piehost.com/docs/3.0/android-websockets)
