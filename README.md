# Falsterboplan

An Android app for the home. Its Lights tab controls IKEA smart lights through a DIRIGERA hub, over the hub's local
network API. Nothing goes through the cloud; the phone has to be on the same Wi-Fi as the hub.

## What it does

The app's interface is in Swedish throughout.

**Lights tab (Lampor)**
- Finds the hub on the network (mDNS) or takes its IP address; pairs with the hub's button
- Lights grouped by room; per light on/off, brightness, warmth, colour and colour intensity
- Rename lights and rooms, add a room, move a light to another room, add code-less lamps
- "Släck allt" turns everything off; scenes save the current look and bring it back with a tap
- Timers (fired by the phone) and schedules (stored on the hub): at a clock time or relative to
  sunrise/sunset, on chosen weekdays; schedules can be edited
- Wake-up and wind-down: five stepped hub schedules that brighten before a time or dim after it
- Away mode: chosen rooms come on around sunset and go off late, at varying times, run by the hub
- Home and away: turn lights on when the phone comes home in the evening, and notify if lights
  were on when it left. "Home" means the hub answers, so no location permission is used

**To buy tab (Att köpa)**: priority, quantity, category and note per item; tick off when bought;
bought items are remembered as "buy again" suggestions. Stored on the phone.

**Vacuum tab (Dammsugare)**: a disabled placeholder.

**Home screen widget**: lights per room with brightness steps and warmth/colour swatches, and the
to-buy list.

**Google sign-in** (menu): Firebase Authentication. Put the Firebase project's
`google-services.json` in `app/` and register the debug key's SHA-1 (below) in the Firebase
console. Without the file the app builds and the menu entry explains that login isn't set up.

## Build

Needs JDK 17+ and the Android SDK (platform 36).

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

Or open the folder in Android Studio and run it.

Debug builds are signed with `keystore/debug.keystore`, which is kept in the repository so that
every build installs over the previous one. Its SHA-1 is
`C6:D4:6B:30:48:36:86:B8:F9:45:7B:77:B9:FE:08:24:AD:15:B5:77`.

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
