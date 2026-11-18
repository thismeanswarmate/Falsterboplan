# Falsterboplan

An Android app for controlling IKEA smart lights through a DIRIGERA hub, over the hub's local
network API. Nothing goes through the cloud; the phone has to be on the same Wi-Fi as the hub.

## What it does

- Finds the hub on the network (mDNS), or takes its IP address
- Pairs by pressing the action button on the underside of the hub
- Lists lights grouped by room, with a switch per room
- Per light: on/off, brightness, and (tap the light) warmth and colour where the bulb supports it
- Updates live when lights are changed from elsewhere (remotes, the IKEA app)

## Build

Needs JDK 17+ and the Android SDK (platform 36).

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

Or open the folder in Android Studio and run it.

## How it talks to the hub

`https://<hub>:8443/v1`, bearer token from a PKCE pairing flow (`data/DirigeraClient.kt`).
The hub's certificate is self-signed, so the app remembers the hub's public key when pairing and
refuses any other key afterwards (`data/HubTls.kt`). "Forget hub" in the menu clears the token
and the remembered key.

IKEA does not publish this API; it is the one the community libraries use, and a hub firmware
update could change it.
