# KTMnav 1.5 verification

Executed on 2026-10-26 against the debug build of 1.5.0 (code 7) on the project emulator `KTMnav_API36` (Android 16, API 36, headless, Google Maps installed, no Google account). The scripts take screenshots and accessibility dumps into the folders below.

| Check | Command | Result | Evidence |
|---|---|---|---|
| JVM unit tests (release and debug variants share the sources) | `./gradlew :app:testDebugUnitTest` | 428 tests, 0 failures | `app/build/test-results/` |
| Android lint | `./gradlew :app:lintDebug` | no errors (29 warnings, all pre-existing categories plus `SetTextI18n` on the new rows, same pattern as 1.4) | `app/build/reports/lint-results-debug.html` |
| Native UI smoke checks from 1.4 | `scripts/check_android_ui.py --out docs/verification-1.5/android-ui` | PASS, 23 assertions | `android-ui/`, `check_android_ui.log` |
| 1.4 redesign checks (themes, compact width, large text, landscape, launcher icon, no crash) | `scripts/check_redesign_ui.py --out docs/verification-1.5/ui` | PASS, 25 checks; the not-installed app assertion now expects the bundled icon instead of a monogram | `ui/`, `check_redesign_ui.log` |
| 1.5 features | `scripts/check_navigate_ui.py --out docs/verification-1.5/navigate-ui` | PASS, 13 checks | `navigate-ui/`, `navigate-ui.txt`, `check_navigate_ui.log` |

## What the 1.5 script exercises

1. Ride tab shows the Navigate card: destination field, app button carrying the installed Google Maps icon, Go.
2. Typing "Nandi Hills" and tapping Go brings Google Maps to the front (the `google.navigation:` intent resolved and started; Maps shows its first-run screen because the emulator has no account).
3. The destination is listed under Recent after the launch.
4. The app chooser lists the installed catalogue apps with "Turns forward to your dashboard".
5. "Save home or work" stores a Home favourite shown as a chip.
6. `am start -a VIEW -d "geo:0,0?q=Chikmagalur"` sent to KTMnav prefills the field (this found and fixed an opaque-URI parsing bug before release).
7. Garage shows a silhouette tile per saved bike, guessed from the names "Duke 390 test" (naked), "KTM 390 ADVENTURE" (adventure) and "Svartpilen 401" (scrambler).
8. Garage → bike → Motorcycle model lists styles and the catalogue; choosing "KTM 1290 Super Duke GT" updates the row summary.
9. Apps: ten bundled icons on one screen for apps that are not installed; Waze, HERE WeGo, Sygic and Mapy.cz listed; launch-only apps carry the "Launch only" chip instead of a forwarding switch.
10. Settings has the Navigation group (navigation app, two-wheeler routes, favourite places, clear recents).
11. A GPX file opened with KTMnav (`ACTION_VIEW`, `application/gpx+xml`, twelve `rtept` points) is imported and its route sheet opens with the trace, three readings and "Navigate this route".
12. "Navigate this route" brings Google Maps to the front with the directions link.
13. Rides lists the route under Planned routes and offers "Import GPX route".

## Not verified here

- Any Bluetooth behaviour: the emulator has no BLE radio. The reconnect ladder, in-place handshake restart and re-pair, no-response display writes, deferred probing and keepalive throttle are covered by the fake-dash JVM tests (`BccuLinkFakeDashTest`, `BccuHandshakeTest`) and need a physical Gen 3 motorcycle for confirmation.
- Real Waze notifications (parser covered by `WazeNavSourceTest` text vectors only).
- The deep links of apps that are not installed on the emulator (Waze, OsmAnd, HERE WeGo, Sygic, Kurviger, Locus Map): their URI forms are unit-tested; whether each app's current version accepts them must be checked with the app installed.
- Translations were written for the new strings in all six non-English dictionaries and parse on load, but have not been reviewed by native speakers.
