# Artifact evidence

Input: TurnIt-Navigation-for-KTM_1.0_apkcube.apks
SHA-256: efe29755f679ab99006883035998e89db214616fa55b0936456e130321e60bbb

REA archive inspection parent: ev_dacb4b466b337db5af5d06254bd837f36b9fde8a7bc30995c1a3e34d02df5c9c
Summary: ev_d845ec9231fac5b5cb25a66efc6b4862bf726327a2184836e5eb1690b4015434
Inventory: ev_bad26b932fe810d2bcf5c10436d52c56a1775daccdb52d04de8ef5f615eb107e
Observed: 29 archive members, zero integrity contradictions. These are static archive observations, not runtime or signature verification.

REA Android package inspection was unavailable: missing caller-supplied jadx-headless-mcp 0.7.1 JAR (REA_JADX_MCP_JAR). No MCP configuration changed. Code evidence instead comes from the previously retained JADX 1.5.6 output and Apktool 3.0.3 Smali under TurnIt-decompiled; consult its README for recovery limitations. Distinguish these local static observations from REA Evidence IDs.

Behavioral entry points: turtle/slap/turnit/MainActivity.java; bike/OpenDashBikeLink.java; gen2/Gen2Frames.java and Gen2ConnectionService.java; nav/*NavSource.java, NavTexts.java, ManeuverClassifier.java; display/IdleDisplayController.java; media/NowPlayingSource.java; settings/AppPrefs.java; com/opendash/app/ble/BccuCrypto.java, BccuProtocol.java and BccuConnectionService.java.

Design and UI are newly implemented. Billing/entitlement/license components of the original app are not imported. KTMnav has no payments or feature gates. Physical dashboard behavior cannot be established from decompilation or emulator tests.

The recovered maneuver model and label resource are bundled with the compatible local LiteRT 1.4 interpreter. Runtime dependency choice follows the [official Interpreter migration guidance](https://developers.google.com/edge/litert/migration); no Google Play Services model download is required. Model identity remains that of the supplied artifact.

Locus API 0.10.1 is bundled from the official [Asamm Locus API repository](https://github.com/asamm/locus-api), using its published Android artifact and transitive core/logger modules. This replaces an unavailable runtime-only reflective dependency; the adapter's expected SDK entry points are checked by on-device instrumentation. Live Locus IPC still requires the Locus app on a device.
