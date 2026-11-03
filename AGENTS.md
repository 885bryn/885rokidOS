# AGENTS.md — instructions for coding agents (Codex)

Read this first, then `docs/TASKS.md` to pick up the next milestone. Specs live in `docs/`.

## What this is
Personal Google-Maps-style navigation HUD for **Rokid Glasses**. The user picks a destination/route on
an **Android phone**; the phone runs Google guidance and streams compact navigation data to a
**glasses-side Android app** that draws its own HUD. See `docs/ARCHITECTURE.md`.
The product is called **885rokidOS** (2026-11-01); package ids stay `com.rokidnav.*`. The repository is
`github.com/885bryn/885rokidOS` (renamed from `rokid-navigation`, 2026-11-02).
Scope is growing (2026-10-31): the app becomes the user's only glasses app, replacing Rokid's built-in
features (notifications first, then music, calls). These features work whenever the phone app is
connected, not only while navigating.

## Modules
| Module | What | Talks to |
|---|---|---|
| `protocol/` | Pure Kotlin/JVM. Message types (`NavMessage`), codec, route chunking, polyline + geo math. **The contract.** | nothing |
| `phone/` | Android app. Search, route picker, Google Navigation SDK guidance, traffic/detours, alerts, CXR-L link. | Google APIs, Rokid AI App (via CXR-L) |
| `glasses/` | Android app installed on the glasses. CXR-S bridge, key input, HUD state store, Canvas HUD. | phone (via CXR-S) |

## Hard rules
1. **Protocol changes** touch `protocol/` + `docs/PROTOCOL.md` + both apps in the same change. Add
   fields only with defaults; never rename/reorder `@SerialName`s. Add a codec round-trip test.
2. **Only `CxrGlassesLink` (phone) and `CxrPhoneLink` (glasses) import Rokid SDK classes.** Everything
   else uses the `GlassesLink` / `PhoneLink` interfaces so it runs without hardware.
3. **Only `GoogleNavigationEngine`, `NavUpdatesService`, `NavInfoMapper` import Navigation SDK classes.**
4. **HUD state flows one way:** messages/keys → `HudStore` → `HudState` → `HudView.render`. Renderers
   never mutate state and never allocate per frame.
5. **Never log** the CXR auth token or the Maps API key. API key comes from `local.properties`
   (`MAPS_API_KEY`), never from source.
6. Capability calls on the CXR session only in `LinkState.Ready`. Register callbacks **before** `connect()`.
7. Comments marked `VERIFY` are unconfirmed API guesses; `ASSUMPTION` are design guesses. When you
   confirm/refute one, fix the code and delete the marker. Don't silently build on top of them.
8. Glasses display: monochrome green, black = transparent, ~480×398 per eye (unconfirmed). Use
   fractions of the view size, never hard-coded pixels. Follow `docs/HUD_DESIGN.md`.
9. Driver safety beats features: no text walls, no animations that pull focus, nothing on the HUD the
   user must read for > 1 s.

## Build & test
- JDK 17. Android SDK 34. Gradle wrapper 8.13 is included; open the root in Android Studio or use
  `./gradlew` (`./gradlew.bat` in PowerShell). See docs/ARCHITECTURE.md for the Windows temp-path workaround.
- `./gradlew :protocol:test` — fast, no Android. Run after any protocol change.
- `./gradlew :phone:testDebugUnitTest :glasses:testDebugUnitTest`
- `./gradlew :glasses:installDebug` on an emulator AVD sized 480×398 (landscape) → HUD demo mode.
- Real hardware loop (M3+): phone with Hi Rokid / Rokid AI App connected to glasses; install `:phone`.

## Reference material
- CXR-L docs (phone SDK, v1.1.2): https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/us/3a61bc60dd3b4093b0f1b7f19b5af339.html
  (JS-rendered; key facts are copied into `docs/ARCHITECTURE.md` → "Rokid SDK facts").
- Rokid samples: CxrlSample.zip (phone) contains the CXRSWithCXRLSample glasses APK. Put unpacked
  samples in `vendor/rokid-samples/` (git-ignored) and read them; don't copy them wholesale.
- Navigation SDK TBT feed: https://developers.google.com/maps/documentation/navigation/android-sdk/tbt-feed
- Routes API computeRoutes: https://developers.google.com/maps/documentation/routes/compute_route_directions
