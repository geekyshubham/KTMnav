# Gen 3 port evidence and work ledger

## Correction to version 1.0

The supplied app's recovered `turtle/slap/turnit/bike/BikeGeneration.java` declares **GEN2** and **GEN3**. `OpenDashBikeLink.getBikeGeneration` defaults non-Gen-2 saved devices to GEN3. Its Gen-2 route starts `gen2/Gen2ConnectionService`; its Gen-3 route starts the OpenDash BCCU BLE connection service.

KTMnav 1.0 incorrectly named the BLE route GEN1. This was a naming/model error in the reconstruction. Version 1.1 uses Gen 3 for BCCU BLE and preserves Gen 2 for Classic RFCOMM. Legacy `GEN1` preferences must migrate to `GEN3` while preserving names, addresses, pairing flags and session keys; the old label does not establish support for a separate Gen-1 protocol.

## Static evidence

- Generation and routing: `bike/BikeGeneration.java`, `bike/OpenDashBikeLink.java`, `com/opendash/app/settings/AppSettings.java` (generation default 3).
- First-pair prompt: base `strings.xml` `info_quirk_gen3_pairing_body` says to open the dashboard Bluetooth menu.
- Low-power detection: `bike/BikeDetector.java`, `BikeDetectedReceiver.java`, `IdleBridgeWatcher.java`; address-filtered PendingIntent BLE scan, wait before low-power mode, saved-address validation.
- Pairing rollback: `OpenDashBikeLink` and AppSettings provisional-restore persistence.
- Telemetry, signal, VIN/device probing and button events: `com/opendash/app/ble/BccuConnectionService.java`, `BccuProtocol.java`, `TelemetrySchema.java`, `Datapoint.java`, `assets/bike_signals_contract.json`.
- Long notification scrolling and greeting: `BccuConnectionService.sendNotification`, `sendGreeting`, related continuation Smali.
- Language picker: `MainActivity.wireLanguageSetting`, base locale config (en, de, es, it, fr, pt-BR, pt-PT) and language-split resources.
- Husqvarna Blue: original `AppTheme` and `husqvarna_blue` color. The reconstruction keeps its own accessible interface while restoring the original blue theme identity.

The embedded `com.opendash.app.ui.MainActivity` simply subclasses the primary app Activity. `RemoteControlAccessibilityService` is a static boolean stub, not an implemented Android accessibility service. There is no functioning packaged accessibility/gamepad feature to copy.

The original BLE handler subscribes to TBT_NAV_REQUEST but does not handle that characteristic in `handleIncoming`. No TBT response semantics are established by those bytes. Subscribe behavior and guidance-screen state can be preserved; an invented request protocol cannot be called a port.

## Verification boundary

Static recovery establishes implementation intent and packet construction, not successful operation with physical hardware. This workspace has no connected physical motorcycle. Hardware authentication, dash rendering, firmware timing and ignition-cycle recovery remain pending physical verification. Later sections and `VERIFICATION.md` record only checks actually executed.

## Version 1.1 reconstruction

- Canonical Gen 3 selection and persisted migration of the old GEN1 label preserve saved motorcycle identity and pairing keys. Pending provisional restoration is persisted and recovered after process restart.
- Gen 3 GATT reads, authenticated PRPC subscriptions/configuration, RSSI polling, device/VIN probing, handlebar rising edges and dashboard banners are wired into the active transport and Settings view. Stale transports cannot populate the current bike's data.
- Guidance-screen state uses the original volume sentinel 255. Greeting expiry is 3 seconds; subsequent guidance removes the greeting. External route clearing uses the recovered 4-second grace, canceled by new guidance. Media idle drawing uses its separate 4.5-second delay.
- The background wait preference is stored in milliseconds (30,000 / 60,000 / 120,000 / 300,000). Retry attempts preserve the first pending background deadline. Passive suspension stops active transports/providers/service while retaining user connection intent. Gen 3 can arm an address-filtered PendingIntent scan; Gen 2 waits for a matching Classic ACL event. An explicit disconnect cancels automatic intent.
- The new native UI uses the ui-ux-pro-max design guidance and the local Android override: grouped data cards, actual-data-only empty states, 48dp minimum interactive targets, orange/blue/daylight tokens, scalable text, system insets, four destinations and native Back. Unknown or absent maneuvers use a neutral waiting graphic.
- Seven language assets have identical canonical lookup keys. Localized text has not received professional translator review. Diagnostic arrow capture is opt-in, private and bounded to 100 PNGs; export is initiated by the user through Android's sharing chooser.

Unit tests, Android runtime checks and synthetic notification checks are documented separately in VERIFICATION.md. They do not establish real-bike behavior. The APK requires nearby-device permission, Bluetooth pairing and any navigation-source permissions; these setup steps cannot be pre-granted by a rebuilt APK.
