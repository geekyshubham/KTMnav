# Gen 3 handlebar controls

## Version 1.3: gestures, modes and calls

Each button now recognises **tap**, **double tap** (second release within 350 ms) and **long press** (held at least 400 ms), decided when the button is released. A button with no double-tap action reacts immediately on release.

| Mode | UP | DOWN | SET | BACK |
|---|---|---|---|---|
| Custom (default) | your tap / double / long assignments | same | same | same |
| Music | tap volume up, double next track | tap volume down, double previous track | play / pause | none |
| Controller | focus previous | focus next | tap select, long press long-press the focused item | Android Back |
| Off | phone ignores all buttons | | | |

In every mode except Off, **long-press UP** changes mode (Custom → Music → Controller) and shows the new mode on the dash, and **long-press DOWN** opens the preferred navigation app. Off is chosen in the app.

While the phone is ringing, **SET answers** and **BACK silences the ringer**. This needs the optional phone permissions (Settings → Calls). Android lets only the default phone app reject calls, so BACK silences instead. The caller name from the phone app's notification is shown on the dash.

Version 1.3 ignores frames that the dashboard does not mark active (byte 16 ≠ 0xFF), matching the field-tested upstream release.


Version 1.2 adds real phone-side button mappings in Settings → Handlebar button mappings. These act on incoming Gen 3 SET/BACK/DOWN/UP presses. The separate button lab remains a local preview and does not trigger real actions.

## Setup

1. Pair and connect a Gen 3 dashboard in Garage. If the motorcycle exposes a Remote Control Mode menu, enable it there.
2. Open Handlebar button mappings, choose an action for each button, then enable custom mappings.
3. For Back, scrolling, focus or selection, tap Allow screen controls, review the disclosure and enable **KTMnav handlebar controls** in Android Accessibility settings. Downloaded APKs may require Android App info → More → Allow restricted settings before this access can be enabled.
4. For media playback actions, enable KTMnav Notification access. Volume actions use Android’s music stream.
5. Configure and test while parked. The last received button and action result appear in the mapping dialog and connection log.

Every button can be assigned No action, Open KTMnav, Android Back, Scroll up/down, Next/Previous focus, Select focused item, Play/pause, Next/Previous track, or Volume up/down. Defaults are UP=scroll up, DOWN=scroll down, SET=select focused item, BACK=Android Back. Assignments are kept while custom mappings are off. Restoring defaults changes only the four assignments, preserving the enable switch and Android permissions.

When mappings are off, the existing optional BACK-to-KTMnav shortcut remains available. When mappings are on, the selected BACK action takes precedence, including No action; it does not also reopen KTMnav.

## Behavior and limits

The implementation uses native Android accessibility and media APIs. It does not inject motorcycle commands, change switch wiring, enable cruise control, type messages, unlock the phone or record screen text. The accessibility service inspects available screen controls only to execute an assigned screen action. Permissions remain under Android’s control.

Scrolling and focus require a foreground app with accessible controls. A map canvas may not expose scrolling or a selectable item. Selection requires a focused control; the app does not guess coordinates or click the first button. Media actions require an active media session. Android may restrict opening an app from the background. Missing access, unavailable targets and rejected actions produce a status result instead of a claimed success.

Gen 2 does not expose the recovered Gen 3 RCM button events. Physical dashboard compatibility, event delivery and motorcycle firmware behavior remain unverified without the bike. Emulator checks exercise actual Android actions and synthetic received frames, not a connected handlebar.

## Recovered evidence

The supplied app defines button bits SET=0, BACK=1, DOWN=2, UP=3 and processes rising edges in `com/opendash/app/ble/BccuConnectionService.handleRcm`. Its `gamepad_enabled` preference is present, but `RemoteControlAccessibilityService` is an empty Java object with `isRunning=false`, confirmed in both JADX and Smali. KTMnav’s working mapping controller and Android accessibility service are new implementations informed by that input path.

The supplied Pro screen advertises navigation sources, low-power background sync and multiple bikes. It does not list a working cruise-control or remapping feature in this artifact. See the Pro coverage audit for exact resources and current coverage.
