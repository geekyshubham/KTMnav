# KTMnav 1.4 verification — 21 October 2026

Build `app.ktmnav` 1.4 (code 5). Version 1.4 is a UI/UX redesign on the unchanged 1.3 backend (backend and unit-test sources are byte-identical to 1.3). Evidence is under `verification-1.4/`; design decisions are in `design-system/ktmnav/pages/native-app.md` ("Version 1.4 refresh").

Changes: KTM orange #FF6600 as the brand fill in every theme, with an adaptive launcher icon; compact tab headers with a connection chip; Material Symbols icon set (Apache-2.0); grouped Settings list with an icon on every row; a Setup section that shows only unfinished items (Nearby devices, Bluetooth, notification access, notifications, battery optimisation, a saved motorcycle) and disappears when everything is done; real installed app icons in Apps; simplified Garage without generation explainer text (generation preselected from discovery, still editable); Ride shows the forwarding source with its real icon and a one-tap "Open <app>"; much shorter text throughout.

| Check | Result |
|---|---|
| Clean build, unit tests, lint | 403 tests passed, 0 failed; lint 0 errors; 886 identical keys in all seven dictionaries |
| Smoke and feature-screen instrumentation | Passed |
| Handlebar runtime, feature runtime (real SMS, calls, mock GPS) | Passed |
| Redesign checks | 25 passed: no marketing headlines, #FF6600 sampled from screenshots in dark and daylight, real Google Maps icon and monogram fallback, battery and notification-access items hide once configured and return when revoked, no Gen paragraphs in Garage, every Settings row has an icon and is at least 48dp, no clipping at 2x text or landscape, orange launcher icon |
| Design tokens | 42 colour pairs meet WCAG thresholds |
| Earlier UI suites | 23 UI assertions, Gen 3 screens, 8 handlebar-mapping checks, notification bridge, 75 feature UI checks: all passed |
| APK | v2 signature verified; 16 KB alignment passed; no INTERNET or billing permission |

Notes: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is restricted on Google Play to apps whose core function needs it; this debug build is side-loaded. Physical-motorcycle limits from 1.3 still apply.

---

# KTMnav 1.3 verification — 21 October 2026

Build `app.ktmnav` 1.3 (code 4), minimum API 32, target API 36, debug-signed. Version 1.3 ports the features of the open-source KTM-Nav-GEN3 and ktm-Connect-App projects, aligns the Gen 3 link with the field-tested upstream v0.3.0 release, and fixes the Gen 3 handshake command-byte bug present in 1.0 to 1.2. Evidence is under `verification-1.3/`. Sources, licensing and the conformance table are in [the port report](OPEN-SOURCE-PORT-1.3.md).

| Check | Result | Evidence |
|---|---|---|
| Clean assembly, unit tests, lint | Build passed; 403 JVM tests across 57 suites, 0 failed/errors/skipped; lint 0 errors, 27 warnings | `verification-1.3/gradle-validation.txt` |
| Gen 3 fake-dash protocol suite | Exact GATT operation order and decrypted bytes for first pairing, silent reconnect from the stored key pool, stall and recovery cooldowns, first-pairing drop, missing pool, mid-session key reselect, ACL settle, adapter off/on, unsupported dash, guidance, banner, clear and symbol tests. Reintroducing the old byte-0 bug fails 14 tests | `BccuLinkFakeDashTest`, `BccuHandshakeTest` |
| Safety | PRPC setValue refused for any datapoint except 200 (TimeSync); OBD command builder rejects every mode except 01/03/07/09 02 and non-whitelisted AT commands (full 00–FF sweep) | `PrpcProtocolTest`, `Elm327ProtocolTest` |
| Android runtime smoke (API 36 emulator) | Passed (model, Locus SDK, AES, frames, bike lifecycle, migration, timing, no fabricated telemetry, languages) | `verification-1.3/android-smoke.txt` |
| Feature screens and dictionaries | Every 1.3 screen opens in all seven languages; 810 identical keys per dictionary | `verification-1.3/feature-screens.txt` |
| Handlebar actions and gestures on real Android APIs | Passed: scroll, focus, select, Back, media, volume; encrypted frames through Bridge → gesture router: tap, held tap without repeat, double tap, single tap after the double window, long-press UP mode change, Music preset, inactive frames ignored, RSSI no replay, master switch off, disconnect cancels a pending double tap | `verification-1.3/handlebar-runtime.txt`, `handlebar-denied.txt` |
| Messages, calls and ride tools with real emulator apps | Passed: real SMS through Google Messages mirrored as a MESSAGE banner; hide content; disabled ignores; real incoming call detected (state and dialer notification); SET answers the call; BACK silences without answering; mock GPS at 100 km/h triggers OVERSPEED; manual recording writes a GPX ride (8 points, 194 m, max 100 km/h) that can be deleted | `verification-1.3/feature-runtime.txt` |
| New UI | 76 checks: all new screens, opt-in disclosures, native permission prompts (denied), validation errors, rides list/detail/delete, OBD disabled/scan/pause states, disabled controls while disconnected, 2× text, landscape, daylight, German | `verification-1.3/ui/` |
| Earlier suites on 1.3 | 23 UI assertions, Gen 3 controls/language, notification bridge, 8 handlebar-mapping checks: all passed | `verification-1.3/regression/` |
| APK integrity and permissions | v2 signature verified; 16 KB zipalign passed; no INTERNET or billing permission. New runtime permissions (location, phone) are opt-in | `apk-signature.txt`, `zipalign.txt`, `permissions.txt` |

An independent code review of the merged backend found 18 issues, including a possible foreground-service crash on background reconnect (Android 14+), an idle watcher that could interrupt handshakes, and stale banner frames. Seventeen were fixed with tests; the remaining item (documenting display-only writes) is recorded here. Session keys remain in app-private storage and are now excluded from backup and device transfer.

Test limits: GPS was supplied through Android's mock-location provider because the headless emulator ignored NMEA injection; the app must be in the foreground or the bridge service must be running for while-in-use location, as on a real phone. OBD was tested against a simulated ELM327 only.

Still needs the motorcycle: whether the corrected handshake completes and silent reconnect survives ignition cycles on the India 2023 Gen 3 390 Duke; which turn and banner icons render (use the symbol test); whether turn-by-turn needs KTM connectivity activation; whether PRPC telemetry starts; real OBD adapter pin compatibility; and accuracy of the learned gear ratios. Cruise control remains unsupported: no source contains a vehicle-control command.

---

# KTMnav 1.2 verification — 20 October 2026

Build `app.ktmnav` 1.2 (code 3), minimum API 32, target API 36, debug-signed. Version 1.2 adds configurable Gen 3 handlebar button mappings and the Settings → Included features page (TurnIt Pro coverage). Evidence is under `verification-1.2/`.

| Check | Result | Evidence |
|---|---|---|
| Clean assembly, unit tests, lint | Build passed; 114 JVM tests passed across 24 suites, 0 failed/errors/skipped; lint 0 errors, 28 warnings | `verification-1.2/gradle-validation.txt` |
| Android runtime smoke (API 36 emulator) | Passed: model inference, Locus SDK, AES, Gen 3/Gen 2 frames, bike lifecycle and migration, background timing, no fabricated telemetry, seven languages | `verification-1.2/android-smoke.txt` |
| Handlebar actions on real Android APIs | Passed: scroll down/up moves a real view; focus next/previous moves input focus; SET clicks the newly focused control exactly once; BACK dismisses a dialog; media play/pause/next/previous reach a real media session; volume up/down changes the music stream | `verification-1.2/handlebar-runtime.txt` |
| Handlebar event handling | Passed: encrypted RCM frames deliver each rising edge in order; held buttons do not repeat; RSSI refresh does not replay actions; disabling mappings stops execution; disconnect cancels queued old-transport actions | `verification-1.2/handlebar-runtime.txt` |
| Missing permissions | Passed: screen actions report missing Accessibility access; media actions report missing notification access | `verification-1.2/handlebar-denied.txt` |
| Mapping and Included features UI | 8 checks passed; screenshots of defaults, saved mapping, Accessibility disclosure and Included features | `verification-1.2/handlebar-ui-checks.txt` |
| 1.1 regression suites re-run on 1.2 | 23 UI assertions, Gen 3 controls/language checks and notification bridge checks passed | `verification-1.2/regression/` |
| Languages | Seven matching 455-key dictionaries; 57 new keys present; six translated Accessibility descriptions | `verification-1.2/language-assets.json` |
| APK integrity and permissions | v2 signature verified; zipalign 16 KB check passed; no INTERNET or billing permission | `apk-signature.txt`, `zipalign.txt`, `permissions.txt` |

The first 1.2 runtime run found that SET could click the previously focused control after a focus move. The fix moves focus with `ACTION_FOCUS` only, clears the accessibility cache (API 33+) and re-checks that the target is still focused before clicking. The final run passes.

Limits: no physical motorcycle was connected. Handlebar mappings act on button events the bike reports to the phone; they send no commands to the motorcycle, and cruise control is not implemented. Screen actions need Android Accessibility access, and a side-loaded APK may need "Allow restricted settings" first. Android may block opening KTMnav from the background, so that action reports "Requested", not "Opened".

---

# KTMnav 1.1 verification — 20 October 2026

The delivered build is `app.ktmnav`, version 1.1/code 2, minimum Android 12L/API 32 and target API 36. It is debug-signed for direct installation and hardware evaluation, not a production store release.

## Executed verification

| Check | Result | Evidence |
|---|---|---|
| APK assembly | Passed | `verification-gen3/gradle-validation.txt` |
| JVM unit tests | 105 passed, 0 failed/errors/skipped across 23 suites | `verification-gen3/build-results.json` |
| Android lint | 0 errors, 28 warnings | `verification-gen3/lint-results.xml` |
| Android 16/API 36 native runtime | 1 composite test passed | `verification-gen3/android-test-results.xml`, `android-smoke.txt` |
| Native dependencies | Bundled maneuver model loads and runs; classifier initializes; Locus SDK entry points resolve | Android smoke report |
| Production persistence/lifecycle | Bike save/select/rename/forget, GEN1→GEN3 migration preserving keys, interrupted-pairing rollback, millisecond timeout values, passive-suspend intent preservation and explicit-stop cancellation passed | Android smoke report |
| Crypto/protocol | NIST AES-CBC vector, encrypted data framing, Gen 3 turn payload, Gen 2 JSON/framing and parser/icon vectors passed | Unit/runtime reports |
| Languages | 398 matching nonempty dictionary keys per language; seven assets load on Android | `verification-gen3/language-assets.json`, Android smoke report |
| APK integrity | Signature scheme v2 verified; zipalign 16 KB check passed; ARM64 ELF LOAD segments aligned to 16384 | `verification-gen3/apk-signature.txt`, `build-results.json` |
| Runtime permissions | No INTERNET or Play Billing permission in built APK | `verification-gen3/build-results.json` |
| UI and synthetic notification paths | 23 UI assertions, 7 Gen 3 checks and source-toggle/capture notification checks passed | `verification-gen3/ui-results.json`, `ui-gen3-results.json`, `notification-results.json` |

Native runtime testing caught a rollback bug that hid another saved motorcycle from Garage. The corrected implementation updates active/inactive identities and restore markers transactionally, preserving pairing metadata. The subsequent full native run passed. Copied test-side implementations were removed from the unit suite; persistence, rollback and timeout checks execute the actual Android code.

## UI scope

The ui-ux-pro-max review uses the native override in `design-system/ktmnav/pages/native-app.md`. Coverage includes compact 375dp width, 2× system text, disabled system animations, native Back, landscape, tablet, three themes, Gen 3 pairing help, actual-data-only empty telemetry, German/English selection and the local button lab. The final Ride view prioritizes received guidance over setup copy and uses a neutral graphic when no maneuver is available. Theme-token primary/secondary/accent text contrast is independently calculated in `verification-gen3/design-token-contrast.json`.

The notification fixture is explicitly synthetic and emulator-only. It exercises listener → Kurviger-format parser → UI, source disable/re-enable/removal, and private maneuver PNG capture. It is not a vendor app or recorded live traffic; its unfamiliar arrow deliberately produces no recognized maneuver. The fixture is excluded from the delivered application APK.

Earlier version 1.0 evidence is retained under `verification/`; version 1.1 evidence is under `verification-gen3/`.

## Limits and unsupported requests

No physical motorcycle was connected. BLE authentication, Classic pairing, actual TFT output, ignition-cycle recovery, firmware timing, offloaded-scan behavior and handlebar return remain unverified on hardware. Live CoMaps/Locus IPC, current vendor notification formats and physical-dashboard media rendering also need device validation. No full TalkBack audit or device-fleet certification was performed. Language assets have not received professional translator review.

The user identified the first Gen 3 Duke 390 launched in India in 2023. No supported phone-to-bike button-injection or cruise-control command was found in the supplied code. Settings → Gen 3 button lab is a **phone-only simulation**, with no transport calls or changes to real handlebar/telemetry state. Vehicle cruise control is not implemented. See [the feasibility investigation](DUKE390-CRUISE-FEASIBILITY.md), including manufacturer references.

All implemented KTMnav features are included without account, billing or entitlement gates. Supported navigation apps retain their own licenses. Nearby-device permission, navigation-source access, bike-side equipment and pairing are required setup steps.

Lint warnings are upgrade suggestions, redundant SDK guards, resource reflection, drawing allocations, locale/text handling, backup metadata and unused declarations. No lint baseline or global error suppression was introduced.

## Reproduce

Use JDK 17 and SDK 36, set `sdk.dir` in `local.properties` or `ANDROID_HOME`, then run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Connected tests uninstall their APKs afterward. Reinstall the built target APK before the UI scripts. With the task-owned emulator and built/installed `navfixture`:

```sh
python3 scripts/check_android_ui.py --serial emulator-5554
python3 scripts/check_gen3_ui.py
python3 scripts/check_notification_bridge.py
```

Do not install the synthetic fixture over a real Kurviger installation. Delivery checksums identify the exact APK and source archive.

## Execution provenance

Backend implementation and follow-up fixes used `gemini-3.8-flash-high` through `agy`, with the native account switched using VibeRouter as requested. Codex implemented/refined the UI, coordinated backend contracts, removed redundant test-side copies, applied small integration fixes and independently ran builds, tests and packaging. Worker command approval denials were handled by keeping command execution with the parent; worker permissions were not broadened.

REA established archive identity. Its Android provider lacked the headless JADX JAR; code analysis used the retained local JADX/Smali evidence. See `evidence/README.md` and `GEN3-PORT.md` for that boundary.
