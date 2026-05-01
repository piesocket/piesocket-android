# PieSocket Realtime SDK for Android

PieSocket Realtime SDK for Android written in Java.

## Installation
Let's start by adding PieSocket Android SDK as a dependency to your application. 

### Gradle (Kotlin)

```
implementation("com.piesocket:channels-sdk:2.1.0")
```

### Gradle (Java)

```
implementation 'com.piesocket:channels-sdk:2.1.0'
```

### Maven
```
<dependency>
    <groupId>com.piesocket</groupId>
    <artifactId>channels-sdk</artifactId>
    <version>2.1.0</version>
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


## Events
`system:connected` is the event fired when WebSocket connection is ready, get a full list system messages here: [PieSocket System Messages](https://www.piesocket.com/docs/3.0/events#system-events)

## Documentation
For usage examples and more information, refer to: [Official SDK docs](https://www.piehost.com/docs/3.0/android-websockets)
