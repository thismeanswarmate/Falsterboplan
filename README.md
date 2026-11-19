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
bought items are remembered as "buy again" suggestions. The list is shared: it lives in the
Firebase project's Firestore database (collection `shopping`, plus `shoppingMeta/history`), so
every signed-in phone sees the same list live. A copy is kept on the phone for offline use.
Firestore must be created in the Firebase console, with rules that require sign-in.

**Calendar tab (Kalender)**: day or week view of the calendars Android already syncs to the phone
(a Google account's calendars, including shared ones), read through the system calendar provider
with the READ_CALENDAR permission. The household's shared Google calendar (`HOME_CALENDAR` in `data/CalendarRepo.kt`) is shown by default when the
phone has it; otherwise the tab offers a link to add it. Choose which calendars to show; tapping an event or "Ny
händelse" hands over to the phone's calendar app.

**Vacuum tab (Dammsugare)**: a disabled placeholder.

**Home screen widget**: every lamp with a switch, brightness steps and warmth/colour swatches, and the
to-buy list.

**Google sign-in**: required before the app or widget can be used (sign out from the menu).
Firebase Authentication against the `falsterboplan-6b048` project,
configured by `app/google-services.json`. The Firebase console must list the debug key's SHA-1
(below) for the Android app.

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

## Releasing to everyone

There is no app store involved. The workflow `.github/workflows/release.yml` ("Släpp ny
version", run by hand from the Actions tab) builds the APK and uploads it to **Firebase App
Distribution**, which e-mails the tester group (`hushall`).

The app uses the App Distribution library (`update/Updater.kt`) to ask for newer builds when it
opens and twice a day in the background, shows "Ny version finns", and downloads and installs
the build. Each phone links to App Distribution once, with the Google account that was invited.
Only invited testers can get the app. (Firebase Hosting was tried first as a plain download
site, but the free plan does not allow app files there.)

The version code is the number of git commits, so every commit is a newer version. The workflow
needs the repository secret `FIREBASE_SERVICE_ACCOUNT`, a service-account JSON key allowed to
upload to App Distribution.

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
