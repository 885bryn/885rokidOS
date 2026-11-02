# Architecture

## Big picture

```
┌──────────────────────── Android phone ────────────────────────┐        ┌──────── Rokid Glasses ────────┐
│                                                                │        │                               │
│  Compose UI ── Search (Places) ── RoutePicker (Routes API)     │        │  glasses app (CXR-S)          │
│        │                                                       │        │                               │
│        ▼                                                       │        │  CxrPhoneLink                 │
│  GoogleNavigationEngine (Navigation SDK)                       │        │      │ NavMessage             │
│     ├─ TBT feed → NavUpdatesService → NavInfoMapper            │        │      ▼                        │
│     ├─ road-snapped location                                   │        │  HudStore ──► HudState        │
│     └─ ETA / remaining                                         │        │      ▲            │           │
│  TrafficMonitor (Routes API poll) → TrafficSegments, Detours   │        │  keys (temple     ▼           │
│  IncidentProvider(s) → Alerts                                  │        │  touchpad)     HudView        │
│        │                                                       │        │               (Canvas)        │
│        ▼                                                       │        │                               │
│  NavSessionCoordinator ──NavMessage──► CxrGlassesLink (CXR-L) ─┼─► Rokid AI App ─► BT ─► CXRServiceBridge│
│        ▲                                     │                 │        │                               │
│        └──────── GlassesAction ◄─────────────┘                 │        │                               │
└────────────────────────────────────────────────────────────────┘        └───────────────────────────────┘
```

**Key decision: the glasses draw the map themselves from vector data** (route polyline + position +
optional nearby streets). We do *not* stream Google map images. Reasons:
- Bandwidth: the CXR link is Bluetooth via the Rokid AI App; bitmaps at a useful frame rate are unrealistic.
- The display is monochrome green; Google's colored map styling would turn into mush. A purpose-made
  line rendering is clearer and lights fewer pixels (battery, see-through).
- Google Maps ToS restricts re-rendering/extracting map imagery.
- Smooth 30+ fps motion: glasses interpolate position locally between 1 Hz updates.

The phone shows a full Google MapView with selected/alternative routes and a Compose guidance panel.
Navigation SDK remains the guidance source; its SDK types stay within the three allowed adapters.

## Session type: CUSTOM_APP (not CUSTOM_VIEW)
CXR-L offers CUSTOM_VIEW (phone pushes a JSON layout of TextViews/ImageViews, no glasses APK) and
CUSTOM_APP (our own APK on the glasses, binary messages both ways). A live map, a menu and touchpad
input need CUSTOM_APP. The phone bundles the glasses APK and CXR-L installs/launches it on `connect()`.

*Fallback idea:* CUSTOM_VIEW could do a text-only "next turn + ETA" mode with almost no glasses work.
Not planned, but `GlassesLink` is the seam if it's ever wanted.

## Rokid SDK facts (from CXR-L 1.1.2 docs, read 2026-10-17)
- Phone SDK: `com.rokid.cxr:client-l:1.1.2`, Rokid Maven `https://maven.rokid.com/repository/maven-public/`.
  minSdk 28, targetSdk 34, Kotlin 1.9+, AGP 8.2+, Java 17.
- Chain: **our app → CXR-L → Rokid AI App (Hi Rokid overseas) → glasses.** We never touch Bluetooth.
  Rokid AI App must be installed (`checkRokidAppCompatibility`, threshold 1.11.0), glasses connected
  inside it, and it must stay alive in the background.
- Flow: `CxrSessionManager.getInstance(ctx)` → `requestAuthorization(activity, perms) { token }`
  (result also via `onActivityResult` → `parseAuthorizationResult`) → `create(SessionConfig)` →
  `addLifecycleCallback` + `addCustomCmdCallback` → `connect(token)` → `onSessionStarted()`.
- States: Idle, Starting, Started, Paused, Terminating. Paused reasons: `AI_ASSIST`, `BT_DISCONNECTED`
  (token stays valid). Closed reasons: `CHAIN_TORN`, `LINK_LOST` → re-authorize.
- CUSTOM_APP config: `glassesPackageName` (required, = glasses applicationId), `glassesActivityName`,
  `glassesApkPath` (phone-readable path, e.g. `getExternalFilesDir("rokid")`; SDK auto-installs).
  `aiInterceptMode`: `ALLOW_WITH_PAUSE` (default) or `BLOCK_AI`.
- Messaging: phone `sendCustomCmd(cmd, Caps, ByteArray)`; glasses `CXRServiceBridge.subscribe(cmd, MsgCallback)`;
  glasses → phone `bridge.sendMessage(key, Caps)`; phone `onCustomCmdResult(key, ByteArray?)` → `Caps.fromBytes`.
  We use only `Caps.write(String)` (it's on the SDK's keep list). Payload size limit is undocumented.
- Glasses SDK: `com.rokid.cxr:cxr-service-bridge:1.7.3`, pinned from the published stable Maven artifact.
  Remote launch, protocol handshake, visible HUD and tap return events were confirmed on hardware;
  remaining M3 gestures, payload limits and AI pause/resume still need validation.
- Glasses keys arrive as system broadcasts (`com.android.action.ACTION_SPRITE_BUTTON_CLICK`, `..._DOUBLE_CLICK`,
  `..._LONG_PRESS`, two-finger tap/swipe, `ACTION_AI_START`, ...) plus normal `onKeyDown` KeyEvents.
- Brightness/volume: `session.setGlassBrightness(0..100)`, `setGlassVolume`. 885rokidOS does not call
  them: the glasses keep the user's global Rokid brightness.

## Glasses hardware (from reviews, unverified)
Dual monochrome green micro-LED waveguides, ~480×398 per eye (one source says 640×480), 23° FOV,
1500 nits, Qualcomm AR1, 2 GB RAM, BT 5.3, Wi-Fi 6. Measure the real view size in M2 and log it in
`GlassesHello`.

## Decisions & open questions
| Topic | Decision | Status |
|---|---|---|
| AI assistant during nav | `ALLOW_WITH_PAUSE`: AI wake pauses our session; on resume, full resync. Try `BLOCK_AI` if pauses are annoying. | ASSUMPTION |
| Voice guidance | Navigation SDK speaks on the phone; audio routes to whatever BT audio is active (glasses speakers if Rokid AI App routes it). | VERIFY M5 |
| Position rate | 1 Hz from phone, interpolated on glasses | ASSUMPTION |
| Map context streets | OSM streets, intersections, selected labels; user promoted to normal HUD | implemented; drive validation pending |
| Police / speed cameras | User-selected WzSabre: SABRE Android plugin + ExCam camera database; see DATA_SOURCES.md | implemented; device validation pending |

## Build / SDK inspection findings (2026-10-17)

- Gradle wrapper 8.13 generated with the installed Gradle distribution; official distribution SHA-256
  pinned. JDK 17, AGP 8.5.2, compile/target SDK 34. The configured Android SDK was missing platform/build
  tools 34; Gradle installed them using the existing accepted licenses.
- `client-l:1.1.2` resolves. AAR SHA-256:
  `4e79963ff5ad84e018783c74a17c6bedbebd3c6076bad3c00bce15250a4daf25` (REA archive inspection).
  All these types are in **`com.rokid.cxr.session`**: `CxrSessionManager`, `SessionConfig`,
  `SessionType`, `AiInterceptMode`, `ISessionLifecycleCbk`, `ICustomCmdSessionCallback`, `RokidAppStatus`.
  Verified using `javap` on the published classes, not observed hardware execution.
- `ISessionLifecycleCbk`: `onSessionStarted()`, `onSessionPaused(PausedReason)`,
  `onSessionResumed()`, `onSessionTerminating(TerminatingReason, long)`,
  `onSessionClosed(CloseReason)`, `onConnectResult(boolean, SessionErrorCode)`.
  Custom command callback: `onCustomCmdResult(String, byte[])`.
- `cxr-service-bridge:1.0.0` does **not** exist in the public Maven repository. Release 1.7.3 was
  downloaded and inspected. `com.rokid.cxr.CXRServiceBridge` takes an Android `Context` constructor;
  `Caps` is also in `com.rokid.cxr`. `Caps.Value.getString()` is the real getter, replacing the
  scaffold's invalid `asString()` calls in both links. The phone SDK transitively pins bridge
  `1.0-20260715.121510-107`; it is left at its publisher's dependency rather than forced to 1.7.3.
- Bridge `StatusListener` signatures: `onConnected(String,String,int)`, `onDisconnected()`,
  `onConnecting(String,String,int)`, `onARTCStatus(float,boolean)`,
  `onRokidAccountChanged(String)`, `onAudioNoise(float)`. `MsgCallback.onReceive(String,Caps,byte[])`.
- Downloaded official legacy sample archive
  `https://rokid-ota.oss-cn-hangzhou.aliyuncs.com/toB/Document/CXR-L/v1.0.3/cxrlsampe.zip`
  into ignored `vendor/rokid-samples/`. It contains `renew_cxrl` phone source using client-l 1.0.3,
  but no bundled glasses APK/source. Matching the intended glasses sample remains open.
- Places brings in `play-services-maps:17.0.0`, duplicating classes already in Navigation 6.1.0.
  Excluding this dependency from Places fixes debug APK packaging, following
  [Google's setup guidance](https://developers.google.com/maps/documentation/navigation/android-sdk/android-studio-setup).
- This Windows execution environment has an overly long temporary path for Java Unix-domain
  sockets. Build commands succeeded with `TEMP`/`TMP` set to the workspace `.tmp` directory and
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir="<absolute workspace>/.tmp"`.
  This is an environment workaround, not a required project-wide Gradle setting.
- M3 wiring uses verified `com.rokid.cxr.session` types. `onConnectResult`'s `SessionErrorCode`
  argument is **nullable** in Kotlin. Authorization requests `DEVICE_MANAGE` for app install/launch
  (885rokidOS leaves device brightness alone); the microphone permission is requested too (2026-11-01, voice replies); camera and media are not.
- Only the link wrapper parses auth results and retains the token. Callbacks are attached before
  `connect()`. Connection callbacks are guarded by an attempt generation so an old session cannot
  overwrite a newer session's state. Capability calls require Ready.
- Debug phone builds depend on `:glasses:assembleDebug`, copy the matching APK to generated assets,
  then copy it atomically into `getExternalFilesDir("rokid")` for the SDK install flow.
- Bridge 1.7.3 has no public unsubscribe/destructor API. One application-lifetime `CxrPhoneLink`
  subscribes once; ViewModel collectors are cancelled normally. Its native lifetime is not broadened
  to disconnect the user's entire Rokid Bluetooth chain on an Activity close.
- (Superseded 2026-10-18: every launch uses the real link; demo needs the explicit `demo` extra, because
  reopening from the Rokid home screen played the demo trip mid-drive.) Launcher intents selected demo; remote explicit launches select real mode, with an explicit `demo`
  extra for previews. This intent distinction needs confirmation on the real glasses launcher.
- `:tools:run` generates the demo recording from a synthetic Routes-shaped fixture. HudStore owns
  messages/keys/timeouts. Renderers cache fonts, glyphs, paths, geometry, and labels outside drawing;
  the active view requests ~30 fps and scales Claude's reference coordinates to its measured size.
- Nothing A059P / Android 16: setup app installed and launched successfully. Hi Rokid and WzSabre
  are installed. The phone was locked, so authorization and on-screen/glasses checks require the user.
- Later on the unlocked phone, authorization succeeded without another consent prompt. After the
  user connected the glasses, Hi Rokid Home showed their battery at 100% and 17.3 GB free in app
  management. Its **+ Device** button opens Add device even when already connected; that screen
  alone is not evidence of missing pairing. The SDK reports `GLASSES_APP_INSTALL_FAILED`.
  The wrapper preserves `onConnectResult`'s error across the following `CONNECT_FAILED` close callback.
  Only token-free lifecycle metadata is logged under `RokidNavLink`.
- `client-l:1.1.2` opens the APK itself with `ParcelFileDescriptor.open(..., MODE_READ_ONLY)` and
  calls Hi Rokid's `uploadAndInstallApk`; it does not ask Hi Rokid to open the private path directly.
  Confirmed in `ExternalAppClient.appUploadAndInstall` bytecode. Changing to broad storage access
  is therefore not a justified fix for the current install failure.
- Hi Rokid Toolbox → Glasses app management installed the same HUD APK successfully. Returning
  to the phone's Connect glasses command then produced `onConnectResult(true, null)` and
  `onSessionStarted`, followed by `GlassesHello` reporting **640×480**. A synthetic navigation test
  was visible, and two `Touchpad: TAP` events reached the phone. This confirms remote launch selects
  the real bridge and both message directions work. The user reported the image rotated 90° clockwise;
  the next build removes Activity-level forced landscape to let the glasses compositor choose its
  native orientation. A separate `phonePreview` debug extra preserves phone preview orientation.
  Both updated APKs built, phone/glasses unit tests passed (30 tests), and the bundled glasses APK
  hash matches the separate APK. Hi Rokid installed the update; remote launch and test resend
  succeeded, with `GlassesHello` now reporting **480×640**. The user confirmed upright orientation
  but cramped content. The renderer had stretched the 398-high reference to 640 pixels independently
  of its width. It now fits uniformly at 92% with centered black margins; the trip status reserves
  separate slots for ETA, duration, distance, and traffic. The exact-size phone capture is
  `docs/screens/hud-native-480x640.png`. Both APKs rebuilt successfully, the matching phone bundle
  was checked by SHA-256, and both apps were updated. Reconnect returned the native dimensions
  and the test was resent; glasses fit confirmation is pending.
  Why the SDK's first automatic install failed is still unknown.
- 2026-10-18, Nothing A059P, Hi Rokid G1.14.20: with the HUD removed in Hi Rokid, **Connect glasses**
  installed and opened it automatically (`onQueryAppResult=false` → `uploadAndInstall` → install
  result true after ~12 s → open → Started). The earlier `GLASSES_APP_INSTALL_FAILED` did not recur;
  it is Hi Rokid's own pass/fail answer (no SDK timeout: the 30 s connect timer is cancelled when the
  install starts), so treat it as transient and retry.
- The real cause of manual installs: the SDK installs only when the HUD is **absent**. If any version
  is installed it is opened as is, so new HUD builds never reached the glasses. Also, a HUD already
  running is only brought to the front on connect and never re-sent `hello`. Fix: HUD versions carry a
  hash of all glasses APK inputs; the phone sends `hello_request` on session start and, when the reply
  differs from the bundled version or is missing for 8 s, uninstalls through the session's SDK client
  (`ExternalAppClient.appUninstall`, reached by field type since v1.1 has no public API) and reconnects
  so the SDK installs the bundled HUD. Verified: 0.2.0 → 0.2.0+16c82db8 replaced in ~18 s with no
  user action; reconnecting to a current HUD skips the reinstall (hello in 0.1 s). See PROTOCOL.md.
- **Install failures explained (2026-10-18):** Hi Rokid transfers the HUD APK over a Wi-Fi hotspot the
  glasses join. With Hi Rokid's Location permission at "only while using the app", the install runs while
  Hi Rokid is in the background, Android rejects its fine-location access (`checkNearbyDevicesPermission
  ... does not have Fine Location permission`), Hi Rokid logs 加入热点失败 (failed to join hotspot) and
  answers install=false → `GLASSES_APP_INSTALL_FAILED`. Fix: Hi Rokid Location = "Allow all the time".
  This is very likely GPT's original unexplained first-install failure too. The phone's error now says so.
- HUD leaving the foreground ends the session: the SDK's `onGlassAppResume(false)` terminates with
  `TerminatingReason.GLASSES_APP_EXIT` (seen in `CxrSessionImpl` bytecode; reproduced when double-click
  exited to Rokid home mid-trip and the user came back to a frozen HUD). The glasses cannot restart a
  session and the phone gets no signal when the HUD returns, so auto-reconnect would pop the HUD back up
  after a deliberate exit. Instead the link reports `Closed(GLASSES_APP_EXIT)`, the phone shows
  **Show on glasses** (nav screen and the ongoing notification), which reconnects, reopens the HUD and
  resyncs; the HUD replaces guidance with "Phone not sending · 885rokidOS → Show on glasses" after 12 s
  without phone messages mid-trip.
- Google remains the source for planned search/routes/navigation/phone map. OSM is currently the
  street-geometry source for the custom HUD. A Google renderer on the glasses is an alternative to
  investigate once connected; Maps SDK needs Google Play services, whose availability on these
  glasses has not been established. See Google's Maps Android setup documentation.

## Glasses battery (2026-10-19)
- The HUD redraws on state changes plus one capped frame loop while the map glides between ~1 Hz
  fixes: 10 fps big map, 5 fps corner map (lowered 2026-10-20; the phone's Demo mode setting runs the
  visible map at 30 fps all the time instead), nothing once the glide reaches the latest fix. Exactly one
  frame is pending at a time (`HudView.nextFrame`); stacked `postInvalidateDelayed` loops had pushed it
  to ~48 fps.
- Only the route from 600 m behind to 4 km ahead is stroked (`HudRenderer.routeWindow`).
- `HudStore.tick` does not re-emit an unchanged state, so an idle HUD draws nothing.
- `FLAG_KEEP_SCREEN_ON` only while guiding or just arrived; with no trip the glasses sleep normally.
- Brightness stays with the global Rokid setting.
- Overnight drain (reported 2026-10-19): the phone held its CXR session open indefinitely after a trip,
  which keeps the HUD app running on the glasses, and older HUDs held the screen on permanently. The HUD
  holds the screen only while guiding; otherwise the display sleeps normally.
- Always-on session (2026-10-31): 885rokidOS is growing past navigation (notifications first), so the
  phone no longer closes the session after 3 min without a trip. It opens the session right after
  authorization, when the phone app comes to the front and when a trip starts. `GlassesLinkService`
  (foreground, type connectedDevice) keeps the phone process alive meanwhile. The HUD finishes itself
  only after 5 min without the phone. Glasses battery cost of the open idle session: VERIFY on hardware.
- Measured with the demo trip on the phone (proxy for the glasses): corner view 47% → 13% of a core,
  expanded 62% → 22%; frames per 30 s 1436 → 303 and 1610 → 424; idle 0 frames.

## Phone UX
1. **Setup** (first run / when link not Ready): Rokid AI App check → "Authorize glasses" → "Connect".
2. **Search**: Places Autocomplete, recents, home/work shortcuts.
3. **Route picker**: Google map with 1–3 alternatives from Routes API; each card shows ETA, distance,
   traffic delay ("+8 min traffic"). Tap Go.
4. **Active nav**: Navigation SDK `NavigationView` full screen; small chip with glasses link state;
   End button. Detour offers appear here too (accept from phone or glasses).
5. **Debug** (hidden, long-press title): link state, live protocol log, send test frames, simulate drive.

## v0.2.0 implementation and validation boundary

- User confirmed upright 92%-fit full HUD is comfortable. New default uses lower corners on the native
  480×640 viewport; upper roughly 62% remains black. Tap expands/collapses the proportionate full
  map. Compact omits labels/ribbon; expanded retains them. Both exact-size phone renderer captures
  are in docs/screens. New corner placement has not yet been checked through the waveguide.
- Search uses Places 4.1.0's programmatic New API with a debounced, cancelled prediction flow and
  session tokens. Recent Place IDs persist; names/coordinates are fetched afresh. MapView follows
  lifecycle events and displays Google attribution. Phone UI is Compose search/routes/guidance/settings.
- Navigation 6.1.0 public signatures were inspected from its pinned AAR. `NavigationApi.getNavigator`,
  `CustomRoutesOptions`, `RoadSnappedLocationProvider`, route/arrival/reroute/progress listeners,
  Messenger TBT feed and simulator are implemented. No unconfirmed scaffold SDK calls remain.
  Mapper tests cover every published maneuver plus real SDK builders and nullable fields.
- Foreground location service owns a low-importance ongoing notification and End action. Location
  is requested from the Activity, terms remain Google's user-facing dialog. Road-snapped updates are
  limited to 1 Hz moving and 5 seconds stopped. HUD interpolation requests 30 fps only while moving.
- Routes REST requests use traffic-aware route tokens, bounded responses, cancellation, separate
  optional ROUTES_API_KEY and package/SHA-1 headers. Routing errors never include raw responses or
  credentials. Initial traffic is seeded from the chosen option; polls run at 150 seconds while active,
  skip stationary/final-2-km updates, and allow explicit refresh. Traffic geometry is projected only
  when sampled points match the active route within 45 m.
- Detours have a 30-second expiry and phone/glasses controls, reject stale IDs, and resolve accepted
  only after engine success. Tests cover expiry, successful/failed switches and monotonic resync.
- Settings use app-private SharedPreferences (no extra persistence dependency): voice, units, zoom
  and WzSabre source toggles. Disabled source flows stop polling; police timeout status is
  visible on the phone. Brightness is left to the global Rokid setting (no `setGlassBrightness`).
- v0.2.0 phone build embeds an identical glasses APK. Added Save glasses installer via Android's
  document picker as an explicit fallback for the observed first SDK install failure. HUD version is
  visible with the handshake so an outdated glasses install can be identified.
- Full build and 52 local tests pass. Android lint reports zero errors; remaining warnings concern
  intentionally pinned tool/dependency versions and target SDK 34. When the bridge disconnects, the
  HUD hides stale guidance and shows a bottom-corner reconnect message. MAPS_API_KEY and ROUTES_API_KEY are absent; all live Google
  authorization/routing/background behavior remains unverified. The phone disconnected before the
  v0.2.0 phone installation, so final APKs are ready locally. See GOOGLE_SETUP.md for the manual handoff.

SDK references: [Google route tokens](https://developers.google.com/maps/documentation/navigation/android-sdk/customize-route),
[TBT feed](https://developers.google.com/maps/documentation/navigation/android-sdk/tbt-feed),
[Routes REST](https://developers.google.com/maps/documentation/routes/reference/rest/v2/TopLevel/computeRoutes),
[mobile REST key headers](https://developers.google.com/maps/api-security-best-practices#secure-direct-mobile-web-service-calls).
