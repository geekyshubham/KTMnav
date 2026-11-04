# RideConnect port (KTMnav 1.5)

Source: `RideConnect-decompiled/` (com.akshayysk.rideconnect, version 0.0.7, versionCode 13; jadx and apktool output of the Play Store XAPK). RideConnect is a Kotlin/Compose app with a Hilt-injected BLE manager, an AES challenge handshake identical in bytes to KTMnav's `BccuCrypto`, six notification providers and a Room ride database. Its code is obfuscated by R8; the behaviour below was reconstructed from the decompiled classes and, for `BleQueue.execute`, from smali. Nothing was copied verbatim: KTMnav keeps its own architecture (pure `BccuLink` state machine, Android adapter in `BccuBleTransport`) and the field-tested timings of the Navigator Gen3 v0.3.0 upstream where RideConnect had none.

## Connectivity: what RideConnect does and what KTMnav 1.5 took from it

| RideConnect behaviour | KTMnav 1.4 | KTMnav 1.5 |
|---|---|---|
| Link loss → `connectGatt` on the retained `BluetoothDevice` after 0.5 / 1.5 / 3 / 5 / 5 s, no scan, no settle, cap 5 then "reconnect manually" | DISCONNECTED → Bridge reconnect in 1.5 s → new transport → LE scan by address → 10 s dash-boot settle → connect (≥ 12 s per try) | After an authenticated drop `BccuLink` retries directly after 0.5 / 1.5 / 3 s with an 8 s connect watchdog each (`FAST_RECONNECT_DELAYS_MS`, `FAST_CONNECT_TIMEOUT_MS`), reports CONNECTING throughout, then hands over to the existing scan-and-settle search. A detector or ACL hit during the wait connects at once. Scan-and-settle stays for cold starts, where the dash-boot grace is real. |
| Dash re-sends its opening nonce m1 ("ROUND 1 REPEAT") → reset and answer with a fresh m2 | A repeated m1 was treated as an encrypted control block → ignored → handshake budget (25/75 s) expired → cooldown | `BccuHandshake` remembers m1 and restarts the nonce exchange in place (`getNonceRestarts()`). |
| Dash selects key index without a stored pool → reply 0, dash re-runs HELLO and key generation in the same connection | `KEY_POOL_MISSING` → drop the link, 15/45 s cooldown, rescan | Reply 0 in place before authentication; the handshake budget still guards the attempt. Mid-session the current key is kept (unchanged). |
| Display characteristics 0704–0709 written WRITE_NO_RESPONSE with 50 ms pacing; control writes wait for a callback and are retried 3× (250 ms gap, 5 s each) | Everything WRITE_TYPE_DEFAULT with a 4 s timeout and no retry; one handshake write timeout or three timeouts of any kind tore the link down | Display characteristics (icon, distance, info, road, ETA, remaining, banner) are written without response when the dash offers it, with a 1.5 s timeout that never counts towards teardown. CCCD writes and control writes (auth reply, navigation state, requests) are retried up to 3× after an initiate failure or an error status; CCCD writes also after a timeout. The teardown rule now counts control timeouts only. |
| One DIS read after Ready; PRPC subscribes 1 s later | Right after auth: RCM subscribe, TBT subscribe, nav state, RSSI, greeting, up to 5 DIS reads, VIN subscribe+write+read, every characteristic of 0200/0300, PRPC subscribes and ten PRPC writes | Rider-visible writes stay immediate; RSSI, DIS, VIN, unknown services and PRPC start `POST_AUTH_PROBE_DELAY_MS` (3 s) later. |
| Keepalive auth challenges throttled to one per 200 ms | Each answered at once | `KEY_RESELECT_THROTTLE_MS` = 200 ms. |
| Navigation notification removal ends guidance after a 2 s debounce; a re-post cancels it | Immediate end | `NavListener.REMOVAL_DEBOUNCE_MS` = 2 s, so the Ride tab no longer flickers when Google Maps re-posts its notification. |

Things RideConnect does that KTMnav already did better and kept: MTU fallback timer, connect watchdog, service-discovery failure handling, GATT cache refresh, per-address session keys, handshake budget, unlimited background retry with the idle watcher and the offloaded `BikeDetector`, boot/ACL/adapter receivers, `disconnect()` before `close()`, both `onCharacteristicChanged` overloads, service restart of the desired connection.

Not ported, on purpose:

- **TFT navigation handshake** (`070C ← 0x64`, then `0703 ← [03 31]`, `[03 32]`): RideConnect interprets byte 1 of NAVIGATION_STATE as a route state, KTMnav's upstream as a volume byte. Which one the dash wants could not be settled from the decompilation; KTMnav keeps the field-tested `[03 FF]` write. Worth an A/B on a bike.
- **PRPC v2 on service 0200** (request framing without the 16-byte random prefix): RideConnect treats 0200 as a second telemetry service version. KTMnav still probes it generically; telemetry on such dashes stays "not offered".
- **Reconnect cap of 5 with a manual-reconnect error state**: KTMnav keeps retrying in the background.
- `clearCommunicationDevice()` / `setMode(MODE_NORMAL)` at service start: a no-op for an app that never selects a communication device, and overriding the audio mode during a real call would be harmful.
- `BleDiagnostics`, `Scanning`/`DeviceFound`/`Syncing` states: dead code in RideConnect.

## Features ported

| RideConnect | KTMnav 1.5 |
|---|---|
| "NAVIGATE VIA" on a ride or imported route: Google Maps directions link with start, up to 9 via-points chosen by heading change, end (`dirflg=d`); Organic Maps and Waze get the end point | `NavApps.routeCandidates`: same via-point selection for Google Maps (plus `dirflg=l` when two-wheeler routing is on); every other catalogue app is sent to the end point through its own deep link, `google.navigation:` or `geo:` |
| GPX import from file managers (`ACTION_VIEW`/`ACTION_SEND`, `*.gpx` and XML types) and an in-app import button, imported rides listed separately | `RouteStore` (files/routes, 50 routes, 16 MB each, validated by the streaming parser, `trkpt` and `rtept`), manifest filters on `MainActivity`, "Import GPX route" and "Planned routes" in Rides, share through `LogProvider` |
| Waze provider: keyword table for the arrow, first distance for the countdown, minutes or "arrive hh:mm" for the ETA, notification progress for the remaining distance, placeholders for distance-only texts, 16-sector roundabout mapping | `WazeNavSource` with the same rules on the notification extras (title, text, big text, sub text, text lines, ticker); roundabouts use KTMnav's right-hand sector mapping; no RemoteViews reflection |
| Preferred provider and "Auto-detect" | KTMnav's existing per-app switches |
| Connection tips and FAQ copy | Folded into the Garage pairing help |
| Bike picker (four PNG photos, 0.5 MB each) | Catalogue of KTM, Husqvarna and GasGas models mapped to eight silhouette styles drawn as tintable vector drawables; chosen per bike in Garage, guessed from the dashboard name otherwise |
| Text badges "GMAP"/"OMAP"/"WAZE" for navigation apps | Twelve bundled vector icons (`ic_app_*`), used only when the app is not installed; the real launcher icon is used when it is |

Not ported: the osmdroid map views (would need the INTERNET permission KTMnav does not request), segments and leaderboards, reverse-geocoded ride names, compass/altitude/temperature cards, the HUD simulator, the eleven-step picture guide, auto ride recording tied to navigation state (KTMnav's "While connected" mode covers the use case).

## New in KTMnav that RideConnect does not have

- A destination field on the Ride tab (`NavDestination`, `NavHistory`, `NavLauncher`): free text, coordinates, `geo:` URIs and Google Maps links; favourites and recents in the app preferences; places shared from other apps and `geo:` links open it prefilled; Locus Map is started through its API, OsmAnd through `osmand.api://navigate`, Waze through `waze://`, HERE WeGo and Sygic through their coordinate schemes, everything else through `google.navigation:` or `geo:` with the package set, falling back to opening the app.
- The Navigate app choice shares the `preferred_nav_app` preference with the handlebar long-press shortcut.

## Verification

- JVM: `BccuLinkFakeDashTest` (25 tests, incl. direct reconnect ladder, hand-over to the search, bike-seen shortcut, keepalive throttle, display-write tolerance, in-place re-pair), `BccuHandshakeTest` (8, incl. m1 repeat), `WazeNavSourceTest` (6), `NavAppsTest` (6), `BikeCatalogTest` (3), `RouteStoreTest` (3); 428 tests in total, none failing.
- Android: lint has no errors; the 1.4 UI scripts (23 + 25 assertions) and the new `scripts/check_navigate_ui.py` (13 checks: Navigate card, Go to Google Maps, recents, favourites, geo: prefill, Garage silhouettes and model picker, bundled app icons, Navigation settings, GPX import by intent and route start) pass on the API 36 emulator; screenshots and logs in `docs/verification-1.5/`.
- Physical motorcycle, real Waze notifications and the installed navigation apps' handling of the deep links remain to be validated on hardware.
