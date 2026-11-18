# Falsterboplan

"Home app": an Android app for the home. Its Lights tab controls IKEA smart lights through a DIRIGERA hub, over the hub's local
network API. Nothing goes through the cloud; the phone has to be on the same Wi-Fi as the hub.

## What it does

- Finds the hub on the network (mDNS), or takes its IP address
- Pairs by pressing the action button on the underside of the hub
- Lists lights grouped by room, with a switch per room
- Per light: on/off and brightness; tap a light for warmth, colour and colour intensity where the
  bulb supports them
- Updates live when lights are changed from elsewhere (remotes, the IKEA app)
- **Timers**: turn a light or a room on/off in N minutes. Fired by the phone, so the phone has to
  be on the home Wi-Fi when the timer ends
- **Schedules**: turn a light or a room on/off at a time on chosen weekdays. Stored on the hub as
  a scene with a time trigger, so they run without the phone (and also show up in the IKEA app)
- **Add a lamp** (menu): opens the hub's pairing window for lamps that join without a code.
  Lamps that pair with a QR or number code (Matter, e.g. KAJPLATS) are handed over to the IKEA
  Home smart app, because no way for a third-party app to pass that code to the hub is known
- **To buy** tab: a shopping list with High/Medium/Low priority; tick items off when bought.
  Kept on the phone
- **Home screen widget**: a switch per room

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

## Look

Colours, type and shapes live in `ui/Theme.kt`. Artwork (house mark, launcher icon, on/off bulbs)
is in `res/drawable-nodpi` and `res/mipmap-xxxhdpi`. Fonts are Fraunces and Nunito, bundled under
the SIL Open Font License (see `licenses/`).
