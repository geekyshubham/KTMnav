# Gen 3 Duke 390: phone controls and cruise-control feasibility

Reviewed 20 October 2026. The user identified the first Gen 3 Duke 390 launched in India in 2023. This is not being treated as the older 373 cc Duke merely because of the year. Instrument/ECU firmware and installed cruise-control hardware remain unconfirmed. No motorcycle hardware is connected to this workspace.

KTM India currently advertises cruise control for its Duke 390. KTM’s general guide describes speed selection using left-handlebar switches and engine-management electronics. That establishes an equipment feature for the marketed motorcycle, not a Bluetooth control API or compatibility for an earlier motorcycle. See [KTM India’s Duke 390 page](https://www.ktmindia.com/ktm-bikes/naked-bike/ktm-390-duke) and [KTM’s cruise-control guide](https://www.ktm.com/en-be/tech-guide/cruise-control.html).

## Recovered protocol evidence

In the supplied artifact, `BccuConnectionService.handleIncoming` routes RCM characteristic data to `handleRcm`. `handleRcm` decrypts received values, decodes SET/BACK/DOWN/UP rising edges and emits phone-side events. Authentication subscribes to indications for that characteristic. Neither the recovered handler nor KTMnav contains a supported command that injects a handlebar press into the motorcycle or operates cruise control. PRPC telemetry decoding and dashboard-navigation writes do not establish a throttle/control interface.

This is a directional distinction: receiving a handlebar press on the phone is not evidence that the phone can transmit a press to the motorcycle. No cruise-control packet format, required controller hardware, safety-interlock contract or supported phone API was established. Accordingly, remote activation, speed adjustment, firmware unlocking and raw ECU/CAN command injection are not implemented.

## Implemented hardware-free capability

Settings → **Gen 3 button lab** provides UP, DOWN, SET and BACK controls for a local menu preview. A visible **PHONE ONLY · SIMULATION** label remains present. DOWN/UP change the preview selection, SET confirms it, and BACK returns the preview to its starting selection. The lab has no connection to `Bridge`, BLE writes, the received RCM-event stream or vehicle-control state. Simulated events never appear as real motorcycle telemetry or handlebar readings.

The lab demonstrates phone UI interaction while hardware is unavailable. It is not cruise-control support and cannot retrofit motorcycle hardware. Real cruise-control integration would require an explicitly supported manufacturer interface and vehicle-specific hardware validation; the supplied app does not provide that interface.
