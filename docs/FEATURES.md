# KTMnav 1.3 feature coverage

KTMnav reconstructs the reachable companion-app behavior found in the supplied TurnIt/OpenDash code. All KTMnav features are free: there is no billing library, account requirement, entitlement check, subscription, or network permission. Navigation routes come from a supported navigation app; its own licensing still applies.

This is a static feature inventory, not a claim that every motorcycle firmware or navigation-app version has been tested. See [verification](VERIFICATION.md) for executed checks.

| Capability recovered from the supplied app | KTMnav implementation | Verification boundary |
|---|---|---|
| Gen 3 BCCU BLE connection, authentication and encrypted navigation | `BccuBleTransport`, `BccuCrypto`, `BccuProtocol` | Crypto vectors and framing tests; physical authentication pending |
| Gen 2 Classic RFCOMM, JSON UI frames and icon conversion | `Gen2RfcommTransport`, `Gen2Frames`, `Gen2Icons` | Framing/icon tests; physical dash pending |
| Gen 2/Gen 3 selection, multiple saved bikes, rename/select/forget and pairing keys | `BikeGeneration`, `BikeManager`, Garage | JVM migration tests and Android persistence checks |
| Failed provisional pairing restores the previous active bike | `BikeManager`, `Bridge` | Persisted restoration tested on Android; pairing timeout requires hardware |
| Foreground bridge notification, automatic connection and explicit stop | `Bridge`, `BridgeService`, `AutoConnectReceiver` | Android startup/stop and lifecycle review; OEM background policies pending |
| Filtered Gen 3 detection after a background idle wait | `BikeDetector`, `BikeDetectedReceiver`, `Bridge` | Address/preference guards and lifecycle review; offloaded BLE hardware pending |
| Distance, road, ETA, remaining distance and turn symbols | Navigation sources, `TurnIcon`, `NavTexts`, `RoundaboutGlyph` | JVM vectors and synthetic Android notification delivery |
| Google Maps maneuver classifier | `GoogleMapsNavSource`, `ManeuverClassifier`, bundled model | Model loads and runs on Android; real Google Maps samples pending |
| TomTom GO, OsmAnd, Kurviger, Calimoto and Organic Maps notifications | Corresponding `*NavSource` and `*Arrows` classes | Parser/hash tests; 39 recovered literal hash mappings checked; vendor updates pending |
| Locus Map direct navigation data | `LocusMapSource`, bundled official Locus API | SDK entry points resolve on Android; live Locus IPC pending |
| CoMaps direct navigation data and permission | `CoMapsNavSource`, `CoMapsDirections` | Direction tests and integration review; live CoMaps IPC pending |
| Per-source enable/disable and route removal | Apps screen, `NavListener`, `Bridge` | Synthetic source disable/re-enable/removal check |
| Idle playing-track display and media session observation | `IdleDisplayManager`, `NowPlayingSource`, `NowPlaying` | Text/timing tests and lifecycle review; physical media rendering pending |
| Personalized greeting and dashboard messages, including marquee | `BccuBleTransport`, `DashboardFeatures` | Packet/timer review and notification tests; physical display pending |
| Gen 3 RSSI, VIN/device information and PRPC telemetry | `DashboardFeatures`, `TelemetrySchema`, `Datapoint`, bundled signals contract | Binary decoding/schema tests; actual fields depend on firmware |
| Handlebar SET/BACK/DOWN/UP edges; BACK requests app return | RCM parser, `DashboardFeatures`, `Bridge` | Encrypted button-vector tests; Android may restrict background app launch |
| English, German, Spanish, Italian, French and two Portuguese variants | `UiText`, bundled language dictionaries, Settings picker | All assets parse on Android; German/English UI exercised; translations need human review |
| KTM, Husqvarna Blue and daylight appearance | Native Android UI, theme resources | Screenshots, compact/large-text/landscape/tablet checks |
| Private maneuver-image capture and user-initiated export | `NavListener`, `ArrowIcons`, `LogProvider`, Settings | Capture bounds/sanitization tests and synthetic PNG capture check |
| Local connection log, diagnostics sharing and license notices | `AppLog`, `LogProvider`, Settings | UI/provider review; sharing uses Android chooser |
| Gen 3 link aligned with field-tested upstream v0.3.0 (handshake byte, HELLO rule, stall budgets, scan-settle reconnect, banner, ASCII text) | `BccuLink`, `BccuHandshake`, `DashBannerArbiter`, `DashText`, `VehicleProfile` | Fake-dash byte-sequence tests; physical dash pending |
| Handlebar gestures and modes; incoming-call answer/silence | `HandlebarGestureRecognizer`, `HandlebarRouter`, `CallControl`, `CallStateMonitor` | JVM timing tests; encrypted frames and real emulator calls on Android |
| Message mirroring and track-change banners | `MessageMirror`, `TrackChangeBanner`, `NavListener` | Real emulator SMS through Google Messages |
| Text-first Google Maps maneuvers, icon calibration, symbol test | `ManeuverText`, `ManeuverResolver`, `IconCalibrationStore`, Settings | Phrase vectors; dash rendering pending |
| Turn beeper, overspeed alert, GPX rides, waypoint | `backend/ride/*`, `RideTrackView` | JVM tests; mock-GPS overspeed and recording on Android |
| Read-only OBD-II (ELM327 BLE/Classic), DTC read, learned gear estimate | `backend/obd/*` | Fake-ELM327 session tests and write whitelist; real adapter and bike pending |
| Direct fast reconnect after an authenticated drop, in-place handshake restart and re-pair, no-response display writes with control-op retries, deferred post-auth probing, keepalive throttle (1.5, from RideConnect) | `BccuLink`, `BccuHandshake`, `BccuBleTransport` | Fake-dash byte-sequence and virtual-time tests; physical dash pending |
| Navigate from KTMnav: destination field, favourites, recents, shared places, geo: links, per-app deep links, two-wheeler routing (1.5) | `NavApps`, `NavDestination`, `NavHistory`, `NavLauncher`, Ride tab | URI vectors and history tests; launching the real apps needs them installed |
| Imported GPX routes and "Navigate this route" with via-points for Google Maps (1.5, from RideConnect) | `RouteStore`, `GpxParser`, `NavApps.routeCandidates`, Rides | Import, listing and via-point selection tests |
| Waze turn-by-turn notifications (1.5, parser logic from RideConnect) | `WazeNavSource` | Text vectors; real Waze notifications pending |
| Bundled navigation-app icons and motorcycle silhouettes per style; per-bike model choice (1.5) | `BikeCatalog`, `BikeManager`, `res/drawable/ic_app_*`, `ic_bike_*` | Catalogue tests; screenshots in `verification-1.5/` |

See [the 1.3 port report](OPEN-SOURCE-PORT-1.3.md) for the open-source sources and [the 1.5 RideConnect port report](RIDECONNECT-PORT-1.5.md) for what was taken from the decompiled RideConnect app and what was deliberately not.

The dashboard-data view shows only values received from a connected Gen 3 motorcycle. Missing data stays unavailable. Firmware may omit telemetry or information characteristics. PRPC uses service `0600` with `0601/0602/0603`; RCM uses service `0100`, characteristic `0103`. RCM button bits are SET=0, BACK=1, DOWN=2, UP=3.

The embedded OpenDash activity is a subclass of the original main activity, not a separate missing application. The original accessibility-control class is a static stub, not a working Android service. The original subscribes to TBT_NAV_REQUEST but has no incoming handler for it; no speculative response protocol was added. These findings are recorded in [the Gen 3 port evidence](GEN3-PORT.md).

KTMnav additionally offers an explicit local dashboard preview and clock/custom-text idle modes. Preview never marks the bike connected or sends packets.

Original evidence remains in `TurnIt-decompiled/` in the workspace. It is excluded from the source delivery together with the original APK, local accounts, build caches and credentials. License notices for OpenDash, Locus API, LiteRT and the bundled font are shipped in the app. Source evidence and the REA provider limitation are documented in [evidence/README.md](evidence/README.md).

A hardware-free Gen 3 button lab provides a local UP/DOWN/SET/BACK menu preview. It sends no vehicle commands. Phone-controlled cruise control is **not implemented**: the supplied protocol only establishes incoming handlebar events. See [Duke 390 feasibility](DUKE390-CRUISE-FEASIBILITY.md).

Version 1.2 adds configurable real Gen 3 phone-side handlebar actions through native Android accessibility/media APIs, with an execution switch, saved per-button assignments, defaults reset and last-action feedback. See [handlebar controls](HANDLEBAR-CONTROLS.md). The original TurnIt Pro groups are navigation integrations, background sync and multiple bikes; Settings → Included features links to their free KTMnav configuration.
