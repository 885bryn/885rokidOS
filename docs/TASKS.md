# Milestones for Codex

Work top to bottom. Each milestone ends green on `./gradlew :protocol:test :phone:testDebugUnitTest
:glasses:testDebugUnitTest` and with its acceptance checks done. Mark `[x]` as you finish; add
findings (confirmed API names, measured limits) to `docs/ARCHITECTURE.md`.

Hardware needed from M3 on: Android phone + Rokid Glasses + Hi Rokid / Rokid AI App. M1–M2 and most
of M4–M8 logic can be done without it.

Latest verification (2026-10-17): `:protocol:test :phone:testDebugUnitTest :glasses:testDebugUnitTest
assembleDebug` passed on JDK 17 / Gradle 8.13. **52 tests, zero failures; both debug APKs built (v0.2.0).**
Android lint: zero errors (pinned-version/target warnings remain).
Live WzSabre exchange and full drive acceptance checks have not been run. Glasses launch,
HUD delivery, native orientation, and tap return events have been checked on hardware.

## M0 — Project boots
- [x] Generate Gradle wrapper (8.13), resolve dependencies; fix invalid bridge version and Maps duplicate classes.
- [ ] Download CxrlSample.zip (link in Rokid docs → Quick Start), unzip into `vendor/rokid-samples/`.
      From the bundled glasses sample (CXRSWithCXRLSample) read the real `cxr-service-bridge` version,
      `CXRServiceBridge` / `Caps` package names, and `StatusListener` signature. Pin the version.
- [x] Confirm package names/imports for `CxrSessionManager`, `SessionConfig`, `SessionType`,
      `AiInterceptMode`, `ISessionLifecycleCbk`, `ICustomCmdSessionCallback`, `RokidAppStatus` in the client-l AAR.
- [x] `local.properties` with `MAPS_API_KEY` (one Android-restricted key for Maps SDK, Navigation SDK, Places (New), Routes; 2026-10-18).
- **Accept:** `./gradlew assembleDebug` builds both APKs; protocol tests pass.
- Findings: legacy official sample downloaded, but it contains phone source only. Stable bridge
  1.7.3 pinned and signatures inspected directly; locating the matching glasses sample remains open.
  Local Android SDK configured. `MAPS_API_KEY` is still absent and required for actual Google guidance.

## M1 — Protocol solid
- [x] Round-trip tests for every `NavMessage` subtype (extend `ProtocolCodecTest`).
- [x] `PolylineCodec` test against Google's reference sample (`PolylineCodecTest`, scaffold).
- [x] Helper: `Geo.metersAlongPolyline(points, position)` (project onto nearest segment) + tests.
- **Accept:** `:protocol:test` green.

## M2 — HUD in demo mode (no phone, no glasses)
- [x] `tools/` JVM generator converts a Routes-shaped synthetic fixture into `glasses/src/main/assets/demo_trip.jsonl`.
- [x] `DemoPhoneLink` loops the asset with rebased timestamps and a monotonic sequence.
- [x] Renderers: maneuver glyphs for every `Maneuver`, `Format` (distance mi/ft & km/m, ETA, durations),
      banner, heading-up route map with interpolation, traffic overlay, status strip.
- [x] Measure real view size; send in `GlassesHello` (480×640 with native Activity orientation).
- Exact-size 480×398 phone preview implemented and captured in `docs/screens/`. The actual glasses
  display reported 640×480 with forced landscape, then 480×640 after removing that override.
  Visual quality and ≥30 fps on glasses still need
  verification; the earlier offscreen phone capture verified geometry/text, not on-screen performance.
  No AVD/system image is installed.
- Claude's mockups are the reference, with the user's thinner text/strokes and outlined panels.
  Trip info shows only current + one next turn because the protocol does not carry a third preview.
- **Accept:** on a 480×398 landscape AVD the demo trip looks like `docs/HUD_DESIGN.md` at ≥ 30 fps;
      screenshots committed to `docs/screens/`.

## M3 — Real link (hardware)
- [x] Implement `CxrGlassesLink` (env check, authorize + `onActivityResult`, CUSTOM_APP connect,
      lifecycle → `LinkState`, send/receive). `GlassesApkInstaller` copies bundled `assets/glasses.apk`
      to `getExternalFilesDir("rokid")`. Gradle task to build+copy the glasses APK into phone assets.
- [x] Implement `CxrPhoneLink`; `HudViewModel` picks real vs explicit demo/launcher mode.
- [x] Phone Setup screen with link status, incoming key/action metadata, and Send HUD test.
- [ ] Validate authorization, installation, launch-mode detection, and both message directions on hardware.
- [ ] Measure max reliable frame size and round-trip latency; update `MAX_FRAME_BYTES`.
- [ ] Log raw glasses key events (broadcasts + KeyEvents) for every gesture; finalize `GlassesKey` mapping.
- **Accept:** phone sends a `maneuver` test frame → glasses HUD shows it; glasses tap → phone log shows `input`.
      Pausing via the AI button and resuming triggers a resync.
- On-phone verification: Rokid authorization completed automatically. Hi Rokid shows the display
  glasses connected at 100% battery. The SDK install flow returns `GLASSES_APP_INSTALL_FAILED`;
  the wrapper now preserves this specific error instead of overwriting it with `CONNECT_FAILED`.
  Installing the matching 9.7 MB HUD APK through Hi Rokid Toolbox succeeded. Then **Connect glasses**
  on the phone opened the installed HUD: SDK session Ready and `GlassesHello` received with 640×480.
  **Send HUD test** was visible; two `Touchpad: TAP` events reached the phone. The user reported the
  entire HUD rotated 90° clockwise. Removed forced landscape from the glasses Activity (phone-only
  debug preview still requests landscape). Installed the update in Hi Rokid, updated the matching
  phone APK, reconnected successfully, and resent the route. `GlassesHello` now reports 480×640;
  user confirmed it is upright but cramped. Replaced nonuniform stretching with a centered 92%
  aspect-preserving fit and reserved status slots to prevent text/badge overlap. Captured the
  480×640 layout in `docs/screens/hud-native-480x640.png`. Rebuilt both APKs, updated the phone
  and glasses, reconnected (480×640 Hello), and resent the test route; user confirmed the upright
  full layout fits comfortably. The later v0.2.0 corner layout is captured on the phone but has not
  been installed on glasses: the phone disconnected before final deployment.
  Resolved 2026-10-18: automatic first install works; outdated HUDs are now replaced on connect (ARCHITECTURE.md).
  Remote launch, protocol handshake, visible rendering, and tap input work with the real SDK.

## M4 — Search & route picking
- [x] Places API (New) autocomplete, debounced/cancelled queries, current details and recent Place IDs.
- [x] `RoutesApiClient.computeRoutes`: traffic-aware tokens, alternatives, bounded cancellable REST, parser tests.
- [ ] Replace example/synthetic test data with an API-recorded fixture once keys are configured.
- [x] Route picker: Google MapView with alternatives, durations, distance, traffic delay and warnings.
- **Accepted 2026-10-18** (Nothing A059P): destination search, route choice and Go work with the live key.

## M5 — Guidance to glasses
- [x] `GoogleNavigationEngine`: navigator init (terms dialog), `setDestinations` with route token,
      start/stop, road-snapped location → `pos`, remaining time/distance → `progress`.
- [x] `NavUpdatesService` + `NavInfoMapper` (all published maneuvers, lanes) + SDK-builder mapper tests.
- [x] `NavForegroundService` while guiding, notification with End action.
- [x] Debug "simulate drive" toggle, Google simulator wiring.
- **Accepted 2026-10-18** (simulated drive): glasses show the same turn as the phone, distance counts down, ETA matches,
  route line and OSM streets render in the corner map; phone sent ~3 frames/s with zero failures.
  Still open: screen-off guidance and a real (non-simulated) drive.

## M6 — Traffic & detours
- [x] `TrafficMonitor`: 150-second active-trip poll, heading, stopped/final-2-km suppression, forced refresh;
      speed intervals projected onto matching active-route geometry. Initial route traffic seeded immediately.
- [x] Detour offer → glasses prompt → accept/keep → `switchRoute`; `detour_resolved` both ways; 30 s expiry; phone UI shows the offer too.
- Local checks: injected offer tests cover accept, dismiss, expiry, stale offer IDs and failed switches.
- **Accept pending:** recorded real Google alternatives and device prompt/route switch.

## M7 — Alerts
- [x] Coordinator: incidents → distance-ahead along route → `alert` within 2 km, `alert_clear` once passed.
- [x] `WzSabreCameraProvider`: ExCam metadata, XZ parsing, last-good disk cache, route filtering.
- [x] `WzSabrePoliceProvider`: documented SABRE handshake/fetch, batch assembly, expiry, cancellation.
- [ ] Optional later: other incident categories. No TomTom key is needed for the selected WzSabre police/camera scope.
- [ ] Validate WzSabre plugin exchange on a phone with `com.rokidos885.phone`; missing-plugin / timeout UX and settings toggle in M8.
- [x] Police/camera/jam/closure glyphs and generic hazard fallback in both map sizes.
- [x] User selected https://wzsabre.rocks/ for police and speed cameras on 2026-10-17; replaces OSM camera plan.
- **Accept:** mock provider police alert counts down and clears in demo + real drive.
- 2026-10-18: alerts made passive and voice added (see DATA_SOURCES.md "Voice warnings", HUD_DESIGN.md
  "Alert chip"): report-accuracy setting (60/150/300 m), 100 m past-pin margin, 30 s refresh with 2 min
  HUD stale drop, no menu takeover, chip instead of status bar, once-per-report TTS with duplicate
  suppression and camera switch. Unit-tested and rendered from the demo trip on the phone.
- [ ] On a real drive: WzSabre exchange returns reports, voice plays through the glasses, chip and
  marker positions look right, no false alerts from parallel roads at "Normal" accuracy.

## M8 — Menu & settings
- [x] Menu pages, auto-close, alert pre-emption, restore previous compact/expanded mode.
- [x] Voice, units, zoom and source toggles persisted on the phone with app-private
      SharedPreferences, pushed via `settings`. Brightness follows the global Rokid setting (the app
      never calls `setGlassBrightness`).
- **Accept:** every menu item works from the touchpad alone.
- Compact bottom-corner layout is default; tap expands/collapses. Native-size compact/expanded
  renderer captures saved in docs/screens. Settings and menu logic compile and have local coverage;
  actual waveguide placement and all gestures need the reconnected device.

## Street-map context (promoted from v2 by user request)
- [x] Overpass street provider: 2 km query, daily bounded disk cache, 600 m movement refresh,
      minimum 30-second interval, five-meter simplification, one ≤6 KB context frame.
- [x] Thin street outlines and upright non-overlapping road labels; OSM attribution on HUD/phone.
- [x] Parse and transport-budget tests; verified live GET response for the public demo area (173 ways).
- [ ] Verify street detail, label placement, payload coverage, and update latency on glasses during a drive.

## M9 — Robustness
- [ ] Reconnect strategy: Paused → wait; Closed(needsReauth) → prompt; Rokid AI App killed → notification hint.
- [x] Stale/duplicate sequence guard; fresh sequence numbers on full resync; five-second chunk timeout → resync.
- [x] Position rate 1 Hz moving / 5 seconds stopped; 30-fps draw requests only while moving, 1-Hz clock tick.
- [ ] Automatic stationary dimming and measured FPS/power on glasses.
- [ ] 1-hour real drive soak test; note issues here.

## Current handoff

The user approved the upright full-size fit and requested bottom-corner content with manual map
expansion. v0.2.0 is built with this layout and the previously scaffolded Google/traffic/settings
implementation. **Neither MAPS_API_KEY nor ROUTES_API_KEY is configured**. See GOOGLE_SETUP.md for
the exact Cloud APIs, package/signing restrictions, local fields and live validation steps.
The Nothing phone disconnected before v0.2.0 phone installation; matching APKs remain built locally.
No live Google route, real police exchange, new corner waveguide fit, AI pause/resume or drive test is claimed.
