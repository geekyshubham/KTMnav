# KTMnav — development notes

An independent Android companion that forwards navigation to compatible KTM and Husqvarna motorcycle dashboards. All implemented features are available without billing, accounts, subscriptions, or a hosted backend.

## Version 1.5

Version 1.5 ports the connection handling and the rider-facing extras of the decompiled RideConnect app (`RideConnect-decompiled/`, v0.0.7) and adds navigation that starts from KTMnav itself:

- **Connectivity:** an authenticated Gen 3 link that drops is retried directly on the retained address after 0.5, 1.5 and 3 s before the scan-then-settle search runs, so a short blip recovers in about a second instead of twelve. The handshake restarts in place when the dash re-sends its opening nonce and asks the dash to re-pair in place when it selects a key this phone does not have, instead of cooling down for 15 to 45 s. Dashboard text and icon characteristics are written without response and can no longer tear the link down on a slow callback; control writes and subscriptions are retried up to three times. Device-information, VIN, unknown-service and telemetry probing now start 3 s after authentication so the first turn is never queued behind them. Keepalive key re-selections are answered at most once per 200 ms. See [the RideConnect port report](RIDECONNECT-PORT-1.5.md).
- **Navigate from KTMnav:** the Ride tab gets a destination field. Type a place, an address, coordinates or a Google Maps link, pick the navigation app and tap Go; turn-by-turn guidance starts there and reaches the dashboard as before. Favourites (Home, Work, more), recent destinations, places shared from other apps, `geo:` links and an optional two-wheeler routing mode for Google Maps are included. The chosen app also serves the handlebar long-press shortcut.
- **Planned routes:** GPX files from Kurviger, Calimoto or any planner can be imported in Rides or opened from a file manager; a route can be started in the chosen app (Google Maps receives the start, up to nine of the sharpest bends and the end).
- **Waze** notifications are now forwarded to the dashboard. HERE WeGo, Sygic and Mapy.cz can be started from KTMnav but do not forward turns.
- **Icons:** every supported navigation app has a bundled icon when it is not installed (the real app icon is used when it is), and Garage shows a motorcycle silhouette per style; the model or style is chosen per bike and guessed from the dashboard name otherwise.

## Version 1.4

Version 1.4 is a UI refresh with no backend behaviour change: KTM orange (#FF6600) everywhere including a new adaptive launcher icon, compact tab headers with a connection chip, Material Symbols icons, a grouped Settings list, a Setup checklist that shows only what still needs action (and disappears when done), real app icons in Apps, a simpler Garage add-motorcycle flow, and much less text. Design tokens and patterns: `design-system/ktmnav/pages/native-app.md`; results and screenshots: `docs/verification-1.4/`.

## Version 1.3

Version 1.3 ports the features of the open-source KTM-Nav-GEN3 (Navigator Gen3) and ktm-Connect-App projects and aligns the Gen 3 link with the upstream release that was field-tested on a 390 Adventure. It also fixes a handshake bug present in 1.0 to 1.2 (the dash's auth command was read from the wrong byte). New in 1.3:

- **Gen 3 link:** corrected handshake and silent-reconnect rules, scan-then-settle reconnect, renderable banner icon, ASCII-safe dash text and an unsupported-dash message.
- **Handlebar:** tap, double tap and long press, Music, Controller, Custom and Off modes, and long-press shortcuts.
- **Calls:** SET answers and BACK silences, with the caller name on the dash.
- **Dash banners:** message mirroring (opt-in) and track changes.
- **Dashboard symbol test** and turn-icon calibration.
- **Ride tools:** turn beeper, GPS overspeed alert, GPX ride recording with stats and track view, and a saved waypoint.
- **Read-only OBD-II** through an ELM327 adapter, with live data, fault codes and self-learning gear estimate.

All of these are in Settings → Riding features and the Handlebar button mappings dialog. See [the port report](OPEN-SOURCE-PORT-1.3.md) for sources, licensing, the conformance table and what was not ported.

## Install and set up

Install the APK in `delivery/` on Android 12L (API 32) or newer. Open KTMnav and allow Nearby devices. Enable KTMnav in Android **Notification access**, then turn on your motorcycle and use **Garage → Scan for motorcycles**. Select the appropriate dashboard generation, save the bike, and connect.

- **Gen 3:** Encrypted BCCU Bluetooth LE. Open the motorcycle dashboard Bluetooth menu for the first pairing prompt.
- **Gen 2:** Bluetooth Classic RFCOMM. Pair in Android Bluetooth settings and enable Navigation in the dashboard Bluetooth menu.
- Start navigation in a supported app. CoMaps also requires its navigation data permission.
- Android permission prompts and motorcycle pairing are required setup steps. They cannot be pre-granted by an APK.

Ride shows the observed bridge state and current guidance. The separate **Preview dashboard** option is explicitly labeled and sends nothing to the motorcycle. Settings includes background connection and filtered Gen 3 detection, reconnect, idle/media display, themes, seven app languages, dashboard data, a phone-only button lab, diagnostics, and license notices. Only values received from the motorcycle appear in the dashboard-data view.

Third-party navigation apps retain their own licensing requirements. KTMnav does not remove restrictions in other apps.

## Build

Use JDK 17, Android SDK platform 36, and Android build tools. Set the SDK path in `local.properties` (`sdk.dir=/absolute/path/to/sdk`) or use `ANDROID_HOME`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The installable debug-signed APK is `app/build/outputs/apk/debug/app-debug.apk`. The Gradle wrapper pins its distribution checksum. No API key or server configuration is needed.

## Google Play release

The signed Android App Bundle and listing assets for 1.4.1 (code 6) are in `delivery/play-store/`; it added an offline privacy policy under Settings → About & help. Version 1.5.0 (code 7) is the current source; build a new bundle with the command below before uploading. Release unit tests pass with no release lint errors. The bundled 64-bit native libraries use 16 KB load alignment.

Release signing loads the local, ignored `release-signing.properties` file. Its `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` properties refer to the upload key in `.signing/`. Keep both files private and back them up securely; neither is included in the source archive or store assets. Without this properties file, the release bundle is unsigned.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:bundleRelease :app:testReleaseUnitTest :app:lintRelease
```

The Play Console app is `KTM Nav`, package `app.ktmnav`. Public release requires finishing the console declarations and store listing, a publicly hosted privacy-policy URL, and the account's mandatory closed test with at least 12 testers opted in continuously for 14 days before applying for production access. Creating the console app or building a signed bundle does not publish it.

## Verification

With one Android emulator or device connected:

```sh
./gradlew :app:connectedDebugAndroidTest
python3 scripts/check_android_ui.py --serial emulator-5554
python3 scripts/check_features_ui.py
python3 scripts/check_feature_runtime.py
```

`check_feature_runtime.py` drives real emulator SMS, incoming calls and mock GPS while the instrumentation checks message mirroring, call answer/silence, overspeed and ride recording. It runs only on an emulator and restores the permissions it grants.

The instrumentation checks real Android startup and persisted bike management. UI checks exercise the four destinations, explicit preview, compact width, larger system text, landscape, and crash logs. See `docs/verification-1.5/` for version 1.5 results and screenshots, including `scripts/check_navigate_ui.py` for the Navigate card, Garage silhouettes, bundled app icons and GPX routes (older evidence under `verification-1.4/`, `verification-1.3/`, `verification-1.2/` and `verification-gen3/`); `docs/FEATURES.md` links feature coverage to the recovered evidence.

Bluetooth authentication, dashboard rendering, firmware variants, and third-party navigation updates require physical motorcycle and installed navigation-app validation. An emulator cannot establish those behaviors. See [the verification report](VERIFICATION.md) for the actual tested scope.

## Gen 3 correction

Version 1.0 mistakenly labeled the encrypted BLE route as Gen 1. The supplied code defines Gen 2 and Gen 3 only. Version 1.1 uses the original Gen 3 identity and migrates old GEN1 saved-bike settings without changing pairing keys. See [the Gen 3 port ledger](GEN3-PORT.md).

## Provenance

The supplied APK bundle and original decompilation remain under their original names. KTMnav is a new Android project (`app.ktmnav`), with new interface and backend code. Archive observations and analysis limits are recorded in `docs/evidence/`. OpenDash and Locus API MIT notices, LiteRT Apache 2.0, and Chakra Petch OFL are bundled in the app and available in Settings.

Backend implementation was assigned to `gemini-3.8-flash-high` through the local `agy` CLI after switching the native account with VibeRouter. UI direction follows the ui-ux-pro-max design system and a native-app override under `design-system/ktmnav/`.

## Handlebar controls

Version 1.2 adds configurable actions for real Gen 3 handlebar presses: Android Back, scrolling, focus/selection, media, volume and opening KTMnav. Enable the mapping switch and the Android access required by your chosen actions. See [setup and limits](HANDLEBAR-CONTROLS.md). Settings → Included features links to all navigation integrations, background connection and the multiple-bike Garage without purchase checks.

## Duke 390 phone controls

The Gen 3 button lab is a local UP/DOWN/SET/BACK preview that sends no motorcycle commands. Remote cruise control is not implemented: the supplied protocol establishes receiving handlebar events, not injecting them or controlling the vehicle. See [the feasibility report](DUKE390-CRUISE-FEASIBILITY.md) for the India-launch-2023 Gen 3 context and manufacturer references.
