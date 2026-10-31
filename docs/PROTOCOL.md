# Phone ↔ Glasses protocol (v1)

Source of truth: `protocol/src/main/kotlin/com/rokidnav/protocol/NavMessage.kt`. Keep this doc in sync.

## Transport
| Direction | Phone (CXR-L) | Glasses (CXR-S) | Channel key |
|---|---|---|---|
| phone → glasses | `session.sendCustomCmd(key, caps, ByteArray(0))` | `bridge.subscribe(key, cb)` | `rknav_down` |
| glasses → phone | `onCustomCmdResult(key, bytes)` | `bridge.sendMessage(key, caps)` | `rknav_up` |

Every frame is a Caps of exactly two strings: `["rknav/1", <json>]`. JSON uses a `"t"` discriminator
(e.g. `{"t":"maneuver","seq":12,...}`). Unknown fields are ignored, unknown `t` values are dropped.
Frames should stay under 8 KB (`MAX_FRAME_BYTES`, to be measured on hardware in M3).

Units: meters, seconds, epoch-ms, degrees (0 = north, clockwise). Formatting happens on glasses.

Active alerts are re-sent at least every 30 s even when unchanged; the HUD drops an alert it has not
heard about for 2 min. `alert_clear` is still the normal way an alert ends.

`alert.reportedAtMs` is optional (default `0`, unknown), epoch milliseconds, for the mockup's report-age
label. Old senders remain valid; camera database entries use `0` and never invent a report age.
WzSabre uses the `alert` / `alert_clear` wire messages. `source`
is `WzSabre`, and IDs are namespaced separately for downloaded cameras and SABRE reports. The phone
updates `distanceAheadMeters` and clears passed/removed/expired alerts. Clears remain part of the
stateful resync so a reconnect cannot retain an alert cleared while disconnected. Trip end clears
the glasses alert list. Police/camera messages have codec and HUD state regression coverage.

Shared geometry helpers `Geo.projectOntoPolyline` and `Geo.metersAlongPolyline` return the nearest
segment projection / accumulated meters for phone-side alert selection. They add no wire fields.

`road_context` now carries actual selected OSM streets during guidance (same schema). The sender
limits the entire JSON to 6 KB. Each message replaces the context for its route; the HUD rejects
context for a different route and clears context on a route change. Road names are used only for
selected upright map labels, and `major` remains available for styling.

## HUD version and automatic updates
The SDK installs the HUD only when none is on the glasses; an installed HUD is opened as is, even if
older. On every session start the phone link sends `hello_request` (seq 0, handled outside sequence
ordering); the HUD answers `hello`, whose `appVersion` is `0.2.0+<hash of all glasses APK inputs>`
(root `build.gradle.kts`). If it differs from the bundled HUD, or no hello arrives within 8 s (HUDs
older than `hello_request` stay silent), the phone uninstalls the HUD through the session's SDK client
and opens a new session, which installs and opens the bundled APK. Tried once per bundled version.

## Phone → glasses
| `t` | Rate | Purpose |
|---|---|---|
| `hello_request` | each session start | ask the HUD for `hello` (version check) |
| `session_start` | once per trip + resync | units, 24h clock, destination name |
| `route_chunk` | per route / reroute / resync | encoded polyline split into chunks; glasses reassemble with `RouteAssembler` |
| `road_context` | after 600 m of movement (≤ every 30 s) | nearby streets; one ~30 KB snapshot split into ≤6 KB parts (`contextId`, `part`, `partCount`, defaults 0/1 = single frame); glasses swap streets only when all parts arrived |
| `pos` | ~1 Hz | lat/lng, bearing, speed, metersAlongRoute |
| `maneuver` | on change + ~1 Hz distance updates | current step, distance/time to it, next step |
| `progress` | ~every 5 s | ETA, remaining m/s, traffic delay s |
| `traffic` | every traffic poll | congestion segments by distance along route |
| `alert` / `alert_clear` | on change | police/camera/crash/etc. with distance ahead |
| `detour_offer` / `detour_resolved` | rare | faster route offer; expires at `expiresAtMs` |
| `nav_state` | on change | IDLE/ROUTING/ACTIVE/REROUTING/GPS_LOST/ARRIVED/ENDED |
| `settings` | on change | voice muted, units, zoom, brightnessPercent (default 35, ignored), demoMode (default false: 30 fps map) |
| `media` | track, play state or seek changes; resync | active (false = nothing playing), app, title, artist, playing, positionMs (at send time) and durationMs (0 = unknown); the glasses ignore both since the HUD has no bar, and a message that changes only the position does not redraw. Not part of the `lastSent` cache: resync sends it with the position moved forward. |
| `notification` | phone notification posted (only while the link is Ready) | id, app, title, text, postedAtMs. Shown as a short card; never resent on resync. |

## Glasses → phone
| `t` | When |
|---|---|
| `hello` | glasses app start; carries display size. Phone replies with a full resync. |
| `resync` | glasses missed something (e.g. incomplete route chunks after 5 s) |
| `action` | ACCEPT_DETOUR / DISMISS_DETOUR (with offerId), END_NAVIGATION, TOGGLE_VOICE, ZOOM_IN/OUT, SHOW_ALTERNATIVES, TOGGLE_UNITS, CYCLE_BRIGHTNESS, CYCLE_ZOOM, MEDIA_PLAY_PAUSE / MEDIA_NEXT / MEDIA_PREVIOUS (control the phone's active media session) |
| `input` | raw key name, for logs/calibration only |

## Resync rule
Whenever the link becomes Ready (connect, resume after Paused, `hello`, `resync`), the phone sends:
session_start → route chunks → cached position, maneuver, progress, traffic, settings and alert state,
then any unexpired detour. See `NavSessionCoordinator.resync`.

## Ordering
Each sender uses monotonically increasing seq values. Cached messages are resequenced on resync.
The glasses reject stale/duplicate frames globally; session_start begins a new receive sequence and
clears trip snapshots before the full snapshot arrives. A phone restart may begin with a lower seq.

`brightnessPercent` and CYCLE_BRIGHTNESS are deprecated and ignored: the glasses keep the global
Rokid brightness. The fields stay for wire compatibility.
CYCLE_ZOOM cycles 0/1/2. Both actions persist phone preferences and echo settings to glasses.
Compact/expanded map presentation is local to the glasses and never resets when settings update.

Resync messages use fresh increasing sequence numbers: session_start (resets receive sequence and
trip snapshots), route chunks, then cached position/maneuver/progress/settings/traffic/alerts.
The glasses reject stale/duplicate sequence numbers after session_start. An incomplete route
requests a resync after five seconds. A phone restart can begin again with a lower session_start seq.
