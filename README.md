# Nosmai Effects with WebRTC on a local network on Android

Publish a camera with Nosmai beauty effects from a native Kotlin app and watch the processed video on a second Android device. The host previews the same effects locally.

## Requirements

- Android Studio, JDK 17 and Android SDK Platform 35.
- Two ARM Android devices running API 24 or later for a host and viewer session.
- A Nosmai Effects licence for the host application ID, `com.nosmai.quickstart.webrtc`.
- The project pins Nosmai Android SDK 3.0.7 and WebRTC Android 144.7559.09. Gradle downloads both dependencies.

## Configure and build

From the project root, create the local settings file.

```sh
cp app/src/main/assets/nosmai.example.properties app/src/main/assets/nosmai.properties
```

Set `nosmaiLicenseKey` for the host in the new file. A viewer-only installation can leave it empty. If you change the application ID, use a licence registered for the new ID. Gradle packages this file in the APK, so use it only for client-side settings.

Use the same `roomName` on two Android devices connected to the same trusted Wi-Fi. The host advertises a service with Android Network Service Discovery. The peers exchange SDP and ICE over a local TCP connection. This sample requires no separate signalling server.

Open the project in Android Studio, or build the debug APK from the command line.

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Start a session

1. Install the app on both phones. Choose **Go Live (Host)** on the broadcaster and grant camera and microphone access.
2. Choose **Watch (Audience)** on the second phone and join the same room or session.
3. Apply a visible effect while moving in front of the host camera. On the viewer, check the effect, motion, orientation and microphone audio.

## Connect an existing app

The sample passes Camera2 YUV frames to `NosmaiPreviewView.processYuvFrame`. With `NosmaiSDK.RenderMode.DUAL_OUTPUT`, the host gets a local preview and processed I420 frames through `NosmaiSDK.setFrameCallback`. The streaming SDK carries the microphone audio.

Create a WebRTC video source on the host. Copy the processed I420 planes into a WebRTC-owned buffer and forward each frame through `capturerObserver.onFrameCaptured`. Keep your existing audio track, peer connection and signalling in an existing application.

On leaving, stop forwarding frames and detach the Nosmai callback before releasing the sender or video source. See the [host and viewer implementation](app/src/main/java/com/nosmai/quickstart/webrtc/LiveActivity.kt) for setup, frame ownership and teardown.

## Troubleshooting

If discovery fails, check the room name and confirm the access point permits multicast and connections between clients. The Android LAN example uses its own signalling protocol. For a browser viewer use the separate Android-to-browser example.

## References

- [Nosmai Effects for Android](https://nosmai.com/docs/effects/installation/android/)
- [Nosmai Android-to-browser example](https://github.com/nosmai/nosmai-webrtc-android-example)
