# Google setup — next manual step

The apps build without keys. Search, Google maps, route calculation, live guidance and Google
simulation require an enabled Google Cloud project with billing. Current local keys are absent.
The SDK terms dialog and Android location permissions also need your input on the phone.

## Configure the project

1. In [Google Cloud Console](https://console.cloud.google.com/google/maps-apis/overview), select/create
   the project you want to use and attach its billing account.
2. Enable **Maps SDK for Android**, **Navigation SDK**, **Places API (New)**, and **Routes API**.
   Use the same project for route tokens and Navigation SDK.
3. Create an Android SDK API key, restricted to the first three APIs. Add this Android app restriction:
   - Package: `com.rokidos885.phone`
   - Debug signing SHA-1 on this computer: `0C:89:60:B2:A4:1D:E2:22:83:B2:EA:36:4E:74:EC:0C:4A:79:45:7E`
4. Create a separate **Routes API** key. The client includes `X-Android-Package` and the signing
   certificate's SHA-1 in `X-Android-Cert`. Apply Android restrictions and verify the endpoint rejects
   incorrect identifiers before live use. If your project/endpoint does not support these restrictions,
   route REST calls through an authenticated server proxy. See
   [Google's direct mobile web-service guidance](https://developers.google.com/maps/api-security-best-practices#secure-direct-mobile-web-service-calls).
5. Put both values into the existing, git-ignored `local.properties` on this computer:

   ```properties
   MAPS_API_KEY=your_android_sdk_key
   ROUTES_API_KEY=your_routes_key
   ```

Keep the existing `sdk.dir` line. Do not paste keys into chat. `local.properties.example` documents
these fields; its placeholder values are not credentials. The Routes key currently falls back to
MAPS_API_KEY if omitted, for local configuration compatibility, but separate keys are preferred.

## Rebuild and connect

Run `./gradlew.bat :protocol:test :phone:testDebugUnitTest :glasses:testDebugUnitTest assembleDebug`.
If this Windows environment reports Java Unix-domain socket path errors, use the temp-directory
workaround in ARCHITECTURE.md.

The matching APKs are:
- `phone/build/outputs/apk/debug/phone-debug.apk`
- `glasses/build/outputs/apk/debug/glasses-debug.apk`

The phone bundles the matching glasses APK. Install/update only the phone: **Connect glasses** installs
the HUD when it is missing and replaces it when its version differs from the bundled one (verified
2026-10-18, see ARCHITECTURE.md). **Glasses → Save glasses installer** remains a manual fallback for
Hi Rokid → Toolbox → Glasses app management if Hi Rokid ever reports an install failure.

## Validate with the phone

1. Keep Hi Rokid paired to the glasses. In 885rokidOS → Glasses, authorize and connect.
2. Navigate → search destination → grant precise location → choose an alternative on the Google map.
3. Enable **Simulate this drive** for the first test, then Go. Respond to Google's terms dialog and
   Android's notification request yourself. Simulation needs Google's API access too.
4. Check matching turns, lanes, arrival, voice, background/screen-off guidance and map context.
5. On glasses, tap to expand/collapse; long press for menu. Check the bottom-corner placement in
   the actual waveguide. Only this new corner layout needs a fresh fit check; the upright full layout
   was previously confirmed comfortable.
6. Install WzSabre and exempt it from battery optimization; it has no settings of its own. Settings
   shows its status during a trip. Live WzSabre IPC was confirmed on the phone on 2026-10-19.

Until a real API response is available, route parser fixtures are explicitly synthetic/examples;
there is no claim that live routing, route-token authorization, background performance, billing
restrictions, AI pause/resume, all gestures, or an on-road soak test have passed.

Implementation references: [Routes REST](https://developers.google.com/maps/documentation/routes/compute_route_directions),
[route tokens](https://developers.google.com/maps/documentation/navigation/android-sdk/customize-route),
[turn-by-turn feed](https://developers.google.com/maps/documentation/navigation/android-sdk/tbt-feed).
