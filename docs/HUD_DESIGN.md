# Glasses HUD design

**Mockups:** `docs/hud-mockups/*.dc.html` (one screen per file, drawn at 480×398 with exact px values;
read them as markup specs, they need the design canvas runtime to render). Live canvas:
https://claude.ai/artifact/PKm7697RoMapZGAnxadHcg (owner-only unless shared).
Screens: Main (map mode), Approach (turn < 500 ft + lanes), Minimal (highway), Police, Detour,
Congestion, Menu, TripInfo, Arrived, Media (no trip), Notification (no trip). The "Visual spec" section below is what to implement.

## Display constraints
- Monochrome **green** micro-LED waveguide. **Black = transparent** (you see the road). Every lit
  pixel costs battery and blocks the view, so: thin outlines over filled areas, black backgrounds,
  brightness hierarchy via alpha (100% primary, ~60% secondary, ~30% context).
- ~480×398 px per eye (VERIFY). Lay out with fractions of the measured view size.
- Hardware check: the native upright Activity reports 480×640. Fit the 480×398 design uniformly,
  centered with 4% margin on each side; leave unused space black. Never stretch X and Y separately.
- Text: min ~22 px for anything read while driving; the distance number is the biggest thing on screen.
- Glanceable: each region answers one question in < 1 s.

## Bottom-corner layout (default, user refinement)

The user confirmed the upright, proportional layout fits, then requested a clear central field.
The native 480×640 viewport now uses a bottom-corner composition, uniformly fit at 92%:
- Upper roughly 62% is black/transparent during normal guidance.
- Bottom-left: 44-unit turn glyph, 36-unit distance + 20-unit suffix, 22-unit road name;
  approach lanes below it; 24-unit ETA and 20-unit remaining time/distance at the bottom.
- Bottom-right: 212×186-unit heading-up street map, thin 6/3.5-unit hollow route (8/4 near a turn),
  smaller position chevron. The miniature omits street labels and the traffic ribbon; the expanded
  map provides both. OSM attribution stays below the miniature.
- Alerts are a small chip in the map's top-left corner (compact) or top-right (expanded); trip stats
  always stay visible. Detour controls replace the maneuver block.
- Tap cycles **corner map → big map → instructions only** (map off). A pending detour retains tap/2× tap accept/keep priority.
  Alerts need no input. Double tap / Back exit to the Rokid home screen unless a detour offer or the
  arrival screen needs them. There is no HUD menu (removed 2026-10-20: touch and hold belongs to the Rokid
  AI assistant and the glasses deliver no two-finger tap); trip details and settings are on the phone.
  AI assistant input belongs to the system.
- Expanded uses Claude's proportionate 480×398 composition. No automatic expansion or attention-
  seeking animation occurs. Idle/arrival messages also stay low in the compact layout.

Real renderer captures: `docs/screens/hud-corners-480x640.png` and
`docs/screens/hud-expanded-480x640.png`. They were inspected on the phone at the native viewport;
corner placement still requires a waveguide fit check.

## Expanded map mode
```
┌──────────────────────────────────────────────┐  0%
│  ┏━┓                                          │
│  ┃↱┃   0.3 mi                    then ↰      │  Maneuver banner (0–30%)
│  ┗━┛   Main St                               │  arrow · distance · road · "then" chip
├──────────────────────────────────────────────┤  30%
│                    ·                          │
│              ━━━━━━┓   ← route line (thick)   │
│                    ┃   ┄┄ slow  ▬▬ jam        │  Route map, heading-up (30–85%)
│        thin dim streets (v2)                  │  next maneuver point marked
│                    ▲   ← you, fixed ~75% h    │
├──────────────────────────────────────────────┤  85%
│ 12:41  ·  18 min  ·  9.2 mi          +4 min ▲ │  Status strip (85–100%): ETA · left · dist · traffic delay
└──────────────────────────────────────────────┘ 100%
```

**Minimal mode** (2026-10-20): the corner layout's left-side instructions, lanes and ETA with the
map turned off. Third view in the tap cycle; no map frames are drawn, so it uses the least power.

**Overlays**
- **Alert (passive, 2026-10-18)** — a chip `[icon] POLICE 0.4 mi` (+N when more are queued) in the
  map corner. Filled for the first 6 s after it first appears, then outlined and dimmer. Never replaces
  trip status, never closes the menu, never needs a tap: the phone clears it 100 m after passing the
  pin (pins are approximate), on expiry, or when the report disappears; the glasses also drop any alert
  the phone has not refreshed for 2 minutes (link loss). Optional voice warning is phone-side.
- **Detour prompt** — card over the lower map: `Faster route · save 6 min · via I-280`,
  `tap = take · 2× tap = keep`, shrinking countdown bar (30 s). Expiry = keep current route.
- **Rerouting / GPS lost** — small text in the banner, map keeps last state.
- **Arrived** — big check + destination name for 10 s, then idle screen.

## Input map
Rokid temple touchpad. Broadcast names from the CXR docs; single-finger swipe delivery is unknown
(probably KeyEvents) — log raw events on hardware in M3 and adjust `GlassesKey.fromKeyCode`.

| Gesture | Driving views | Detour prompt showing |
|---|---|---|
| Tap | cycle corner map → big map → instructions only | **accept detour** |
| Double tap | **exit to Rokid home** (task kept, trip resumes on return) | **keep current route** |
| Touch and hold | Rokid AI assistant (not used by the HUD) | — |
| Swipe forward / back | zoom in / out (if the glasses deliver it) | — |
| AI button | system (never consumed) | |

## Beyond navigation (2026-10-31)

Rokid Nav is also the glasses' notification and music screen. These screens work with or without a trip
and use a **larger type scale** than the trip HUD: titles 26–34 px, body 22–26 px, labels 17–18 px,
nothing under 17. The trip HUD keeps its own sizes (user decision: leave it alone).
Mockups: `Media.dc.html`, `Notification.dc.html`. Expanded view coordinates; the corner (upright) view
uses the same blocks lower on the 480×640 view.

**Idle screen (no trip).** Black (transparent) unless one of these applies, in this order:
1. a phone notification card (replaces everything for its lifetime);
2. music playing or paused in a phone media session: the media screen;
3. the phone link is lost: "Phone not connected" (30 px).
The old "Rokid Nav / Ready / Start a trip" screen is gone. After a card expires the display shows only
what is left in that list, never the old screen.

**Media screen.** App name 18/700 caps @70% and a state icon (two bars playing, triangle paused) in the
top-right; track title 34/700, wrapped to two lines; artist 26/600; 6 px progress bar (@25% track, solid
fill); elapsed and total time 24/600. Corner view top 408, expanded top 110, width 444. The bar and times
redraw every 5 s (battery); a track change, play/pause or seek redraws at once.
Input: **tap** play/pause, **swipe forward** next, **swipe back** previous. They act on the phone's active
media session (Spotify, YouTube Music, ...). On a trip these gestures keep their trip meaning and the
media screen is not shown. The phone reads the session through Notification access.

**Notification card.** App name 17/700 caps, title 26/700 (one line), text wrapped (22 px, up to 6 lines;
20 px, 4 lines beside the turn arrow on a trip). The phone sends up to 60 characters of title and 300 of
text (the notification's full "big text", line breaks joined). Shown 6 s, plus 60 ms per character up to
15 s; a tap hides it. The card wakes a sleeping display. A detour offer hides the card.

## Menu (removed 2026-10-20)
The HUD has no menu. Touch and hold starts the Rokid AI assistant and the glasses never delivered the
two-finger tap, so no gesture was left to open it. Trip info, traffic, voice, units, zoom and End
navigation are on the phone.

## Visual spec (from the mockups; px at 480×398, scale by viewWidth / 480)

### User refinements (2026-10-17)

Keep Claude's layouts and visual hierarchy, with a lower-lit-area treatment requested by the user:
Barlow Medium 500 for normal text and SemiBold 600 for emphasis; primary distance 52 px + 22 px unit;
road name 24 px; turn strokes 6.5 px in the 84 px box; route tube 10/6 px (14/8 when close);
chevron 24×30; context roads 1.8 px at 22% alpha. Alerts and selected menu rows use outlines and
a small selection accent instead of broad green fills. Small gesture pills and urgent delay badges
retain their inverted treatment. These replace the heavier sizes/fills below. Battery savings have
not been measured on glasses; the goal is fewer illuminated pixels while retaining readable text.

Hardware fit refinement: the user confirmed native orientation is upright but the stretched layout
was cramped. Uniform 92% fit replaces the separate X/Y scale (which stretched vertical dimensions
by 61%). The status strip uses reserved slots: ETA 26 px, duration/distance 22 px, with bounded
labels so the traffic badge cannot overlap them. Other reference sizes and thin strokes stay intact.

Side streets and intersections are now part of the normal map, per the user's request. Live context
comes from OpenStreetMap; selected road names stay upright, at least 15 px, and avoid overlapping.
Demo streets are explicitly synthetic. Keep surrounding roads dimmer than the active route.

**Palette:** one hue `#3DFF8A` (tweakable; the panel renders green regardless) on black. Hierarchy is
by alpha only: 100% primary, 85% secondary text, 60–75% labels, 45% partial fills, 22% context
streets. Inverted (filled green, black text) is reserved for things that need action now: the alert
bar, the highlighted menu row, the primary gesture hint, and a delay badge once delay ≥ 5 min.

**Type:** Barlow Semi Condensed (bundle the TTF in glasses assets; OFL), tabular numerals.
Distance 58/700 + unit 24/600 @75%. Road name 26/600, single line, ellipsized. Status ETA 30/700,
other status values 24/600 @85%. Small caps labels 15–16/700, letter-spacing 0.12em, @60%. Nothing
under 15 px.

**Strokes:** maneuver glyphs are open strokes (9 px in an 84 px box, round caps/joins), never filled
shapes. Small glyphs 2.6 px in a 24 box.

**Regions:** banner 0–118 px (padding 16/18, 84 px glyph, 16 px gap); map 118–338; status strip
338–398 (padding 0/18, gap 12, items separated by `·` @40%).

**Route line:** solid from 2026-10-18; back to a hollow tube with a black middle on 2026-10-20 (see "Map styling"). Originally a hollow
tube where traffic filled it. This reads at a
glance without color and keeps lit pixels low. Zoom 0 widens the tube to 20/9.
Chevron: filled arrow 32×36 at (50%, ~85% of the map region). Context streets 3 px @22%.
Next maneuver point: black dot with 3 px ring (zoom 0 only). Street labels (2026-10-18, enlarged for the waveguide): turn street 20 px bold, major/"after" 18, other
17; compact corner at 0.85×. Road label beside the
turn.

**Traffic ribbon:** right edge of the map, 8×196 px rounded outline @80% = the whole remaining route
(destination at top, you at the bottom dot). SLOW segments fill @45%, JAM segments fill solid.

**Approach state** (distance < 500 ft or 150 m): auto-zoom 0, 4 px progress bar along the bottom of
the banner (filled part = distance left), lane pill centered under the banner (lanes 30×36, 2 px
dividers @30%, recommended lanes 100% / others @30%).

**Minimal mode:** banner + lane row + status strip; the middle stays black. Exit numbers go in an
outlined tag (`EXIT 24B`, 18/700). The status strip shows "No delays" @60% when there's no delay.

**Alert chip:** expanded 28 px tall at the map's top-right (below the lane box when lanes show), icon
18 px, kind 16/700 caps, distance 18/700; compact 22 px at the map box's top-left, icon 15, text 13/14.
Radius 8. Fresh (first 6 s): filled green, black ink. Then black fill, 2 px outline @75%, ink @80%.
The incident is also marked on the route: black disc r16 with a 3 px ring and the kind glyph.

**Detour card:** 320 px wide, top-left of the map region, 2 px border, radius 14, black fill.
"FASTER ROUTE" label, "Save 6 min" 44/700, "via I-280 · +0.8 mi" 20/600, then a 4 px countdown bar
and hints: `TAP` filled pill "take it", `2× TAP` outlined pill "keep" @75%. The alternative route is
drawn on the map as a dashed 4 px line (8/7) next to the current tube.

**Congestion callout:** when a JAM segment is within view, a 2 px leader line from the tube to a
right-aligned label left of the route: "Heavy traffic" 20/700 + "in 0.6 mi · 1.2 mi long" 17/600.

**Menu:** header row (label + "Home · 12:41" @60%), rows 54 px, label 28 px, right-side value @75%
plus chevron, highlighted row filled. Gesture legend at the bottom 15/600 @55%.
**Trip info:** "Arrive" + time 64/700, three outlined stat tiles (left / to go / traffic), then
"NEXT TURNS" with 3 rows fading 100 → 75 → 55%.
**Arrived:** centered 96 px check-in-circle, "You've arrived" 44/700, destination + side 26/600,
trip summary, auto-close hint.

## Zoom (2026-10-18: continuous, `MapZoom`)
The map shows a distance ahead computed every frame and eased (0.6 s time constant), never fixed steps:
- **Cruise view from speed:** 200 m standing/slow, 350 m at 50 km/h, 700 m at 80, 1.2 km at 110, 1.6 km
  at 130+ (linear between).
- **Turn framing:** a real maneuver (not straight / name change) pulls the view to `distance × 1.4 + 60 m`
  as soon as that is tighter than the cruise view, so the turn sits ~70% up the map and the zoom tightens
  progressively down to 150 m at the corner. After the turn it eases back out.
- **User setting** scales the result: Close 0.7×, Default 1×, Far 1.5×.

## Lane guidance (2026-10-18)
Shown whenever Google's turn data includes lanes for the next maneuver (Google only sends them where
lanes matter) and it is within 2 km; previously only inside 150 m. Expanded: lane box under the banner.
Compact: a row under the street name (replaces the turn cue while visible), up to 8 lanes, 30 px arrows
with thin dividers. Your lanes are bright, the others at 25%; in a lane allowing several directions only
the arrow on the maneuver's side is bright. The demo trip's first turn carries synthetic lanes.

## Map styling (2026-10-18)
- **Route ahead (2026-10-20):** hollow green tube 12 px (compact 8 px) with a black middle 45% of its
  width and a 3 px black edge each side. The tube is wider than any street, so it stays the strongest mark.
- **Street names (2026-10-20):** only named major streets (OSM tertiary and above, e.g. Kingsway,
  Granville) plus the turn street. One label per street, at a fixed point that moves only when it falls
  behind or out of reach. Names already shown keep their place; at most one new name per 2 s. At most
  4 names on the big map, 2 on the corner map.
- **Big map zoom (2026-10-20):** shows 2.5× more road than the corner map, for an overview of the route.
- **Traffic (2026-10-20):** fills the tube's black middle: slow traffic dashed, a jam solid, so a
  jammed stretch reads as one thick bright line. The big map's side ribbon and the delay badge stay.
- **Turn arrow:** filled arrowhead with a black edge 25 m past the turn, pointing along the route, shown
  once the turn is within the visible distance.
- **Streets:** residential 2.5 px at 45%, major 4 px at 65% (compact 2 / 3 px).
