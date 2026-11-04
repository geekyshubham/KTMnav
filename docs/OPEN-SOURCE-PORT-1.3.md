# KTMnav 1.3: open-source app study and port

KTMnav 1.3 ports features and field-tested protocol behaviour from two public projects the user named. Both were studied read-only. No third-party code, build script or APK was executed.

| Project | Source | License | How KTMnav uses it |
|---|---|---|---|
| KTM-Nav-GEN3 ("OpenDash", later "Navigator Gen3") | github.com/Pavanayi1/KTM-Nav-GEN3, `main` plus static decompile of release v0.3.0-beta (SHA-256 matches GitHub's published digest) | MIT | The upstream of TurnIt's embedded `com.opendash.app` code. Protocol behaviour is reimplemented in Java. Translated logic keeps the OpenDash MIT attribution (`assets/licenses/opendash_MIT.txt`). |
| ktm-Connect-App | github.com/sushem/ktm-Connect-App (branch `claude/ktm-ci-and-message-screen-dhyatl`) | No license (all rights reserved) | Protocol facts only (ELM327 UART UUIDs, init commands, PID formulas, field notes). All OBD code is a new implementation. |

A lookalike repository, `Satsumaassortativemating71/KTM-Nav-GEN3`, contains an opaque `v1.5.zip` and an external download page. It was treated as a suspected malware lure and not used.

## Critical correction

All earlier KTMnav builds (1.0 to 1.2) read the dashboard's authentication command from decrypted byte `[0]`. TurnIt, OpenDash `main`, the v0.3.0 release and ktm-Connect-App all read byte `[2]`. With the old code the Gen 3 handshake most likely stalls after the first exchange. Version 1.3 reads `[2]`. The fake-dash suite fails 14 tests if the old behaviour is reintroduced.

## Gen 3 conformance with the field-tested upstream release

Upstream v0.3.0 reports silent reconnects on a 2025 390 Adventure. KTMnav 1.3 was compared operation by operation against it (V) and TurnIt (T).

| Item | Upstream v0.3.0 | TurnIt | KTMnav 1.3 |
|---|---|---|---|
| connectGatt | autoConnect=false, TRANSPORT_LE | same | same |
| MTU | 517 after service discovery | same | same, plus a 2.5 s fallback if no MTU callback |
| Connection priority | HIGH after MTU | same | now requested (was missing) |
| Handshake timer | armed at link-up | armed at m1 | armed at link-up |
| CCCD order | 0701 indication; after auth 0103 indication, then 070b notify | same | same |
| Write type | with response, serial queue, display writes coalesced | same | same, plus a 4 s operation timeout |
| Auth command byte | `[2]` | `[2]` | `[2]` (fixed from `[0]`) |
| HELLO reply | 1 only if bike known, key pool stored and no stall recovery; else 0 | 1 if known | matches v0.3.0 |
| Stall handling | 25 s known / 75 s first pairing; 15 s then 45 s cooldown; pairing kept; no cap | 18 s, clears pairing, max 2 | matches v0.3.0 |
| Reconnect | address-filtered scan, 10 s dash-boot settle, then connect | direct connect | matches; a Classic ACL or background detection hit uses the settle only |
| Bluetooth adapter off/on | forced disconnect / resume | none | added |
| Post-auth order | RCM, 070b, nav state (1,1,255), RSSI every 3 s, greeting, probes, PRPC | same | same; TimeSync and read-only value polling added |
| Navigation state | sent once | toggled off on clear | sent once |
| Greeting | START icon, road text, distance off, cleared after 6 s | 3 s, brand text in ETA | matches v0.3.0 |
| Guidance | distance, road, ETA (bare HH:MM), remaining, all FULL; icon separate | similar | matches; non-HH:MM ETA is sent OFF |
| Guidance clear | 4 s debounce; icon UNDEFINED and all labels "" OFF | " " FULL | matches v0.3.0 |
| Banner | WAYPOINT icon; clear = OFF + last icon; 16 chars, 5 s, 320 ms marquee ×2 | INFORMATION icon | matches; adds a priority arbiter; text never logged |
| Dash text | printable ASCII only | none | NFD fold, non-ASCII to space, collapsed whitespace |
| Handlebar frames | mask counted only when byte 16 = 0xFF | flag ignored | matches v0.3.0 |
| Bonding / GATT cache refresh | none | none | none |
| Dash without service 0700 | profile label and GATT dump | error | clear "does not offer the KTM navigation service" status and dump; no blind retries |

## Features added in 1.3

| Feature | Origin | Notes |
|---|---|---|
| Tap / double-tap / long-press gestures (400 ms hold, 350 ms double) | NavGen3 v0.3.0 behaviour | Fires on release; buttons without a double binding react immediately |
| Button modes Custom / Music / Controller / Off; long-press UP changes mode; long-press DOWN opens the navigation app | NavGen3 v0.3.0 | Mode name shown on the dash; no draw-over-apps permission |
| Long-press focused item (accessibility action, gesture fallback) and system-screen guard | NavGen3 v0.3.0 | Never acts on permission dialogs or system UI |
| Incoming call: SET answers, BACK silences, caller name banner | NavGen3 | Opt-in phone permissions; a true reject is impossible for a non-dialer app |
| Message mirroring (WhatsApp, Google/Samsung Messages, Telegram, Signal) | NavGen3 | Off by default; allowlist, group filter, hide content, quick mute; content never logged |
| Track-change banner | NavGen3 v0.3.0 | "Title - Artist" once per track |
| Text-first Google Maps maneuver parsing | NavGen3 | Phrase rules, then user calibration, then the bundled classifier |
| Turn-icon calibration and dashboard symbol test | NavGen3 | Lets the rider confirm which icons render on their dash |
| Stereo turn-approach beeper | NavGen3 | Off by default |
| GPS overspeed alert | NavGen3 | Off by default; location permission |
| GPX ride recording, ride list, stats and track view | NavGen3 | App-private storage; shared only on request |
| Saved waypoint to Google Maps | NavGen3 | Accepts lat,lon or Maps links |
| Dash profile detection | NavGen3 v0.3.0 | Shown in Dashboard data |
| PRPC experiments: TimeSync, 16-bit sample rate, error parsing, read-only odometer, fuel, service and cruise status | KTM signals contract in both apps | Only datapoint 200 (TimeSync) is ever written; effect needs the bike |
| Read-only OBD-II through ELM327 (BLE or paired Classic) | ktm-Connect-App facts | Mode 01/03/07 only; a whitelist rejects every write mode |
| Estimated gear with self-learning ratios | ktm-Connect-App idea, new learner | The published table looked about 30% high; KTMnav learns this bike's ratios from steady riding |

## Not ported

| Item | Reason |
|---|---|
| Gemini notification summaries | Sends notification content to a cloud service with an API key in the URL; KTMnav has no network permission |
| ktm-Connect over-the-air JavaScript updates | Unsigned code download; conflicts with code-integrity requirements |
| MY RIDE SPP fallback | Upstream field note: accepted a socket but did nothing on a 390 Adventure |
| Engine-vibration detection | Phone-dependent accelerometer heuristic; not needed by the ported features |
| Any vehicle or cruise-control command | None exists in any studied source; cruise datapoints 311/312 are read-only telemetry |

## Hardware-dependent unknowns

The emulator and fake dash verify byte sequences, timing and Android behaviour. They cannot prove that a specific dashboard accepts them. Ride tests should confirm: first pairing and silent reconnect across ignition cycles; which turn and banner icons render (use Settings → Dashboard symbol test); whether the India 2023 Gen 3 390 Duke needs KTM connectivity activation for turn-by-turn; whether PRPC telemetry starts; and OBD-II pin compatibility of the 6-pin diagnostic connector.
