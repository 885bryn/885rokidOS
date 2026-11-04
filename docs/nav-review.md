# Glasses navigation review and the "open Grok Bot" voice command (2026-11-02)

> **Historical record.** The spoken command in section 4 was removed on 2026-11-04 at the user's request:
> Grok Bot is started by the gesture only (idle tap, or swipe back on a trip). `VoiceCommands`, its test and the
> `VOICE_START` + `VOICE_COMMAND_ID` matching path are deleted; sections 4 and 8 below describe what was built
> and tested with speech, not what the code does now. The gesture map, the rest of the review and `docs/VOICE.md`
> are current.

Result first:
- The gesture scheme is mostly sound. It has one clear model: **tap acts, swipe moves or skips, double tap goes back**.
  Four places broke that model or did things the user could not see. Three of them are fixed.
- A new **voice command** opens the Grok Bot app on the phone. **Tap the empty idle screen** (on a trip: **swipe back**), say
  "open Grok Bot". It reuses the voice-reply pipeline (glasses microphone → offline sherpa-onnx on the phone). On the phone, it needs one grant: **Display over other apps**.
- Nothing here ran on real glasses or a real phone. See "What to test on the hardware".

## 1. How a gesture reaches the HUD

| Touchpad | What the app receives | Code |
|---|---|---|
| Tap | key 83 then `ENTER` → `TAP`; the view's touch listener also reports a single tap | `input/Keys.kt:48`, `MainActivity.kt:48`, `MainActivity.kt:86` |
| Swipe forward / back | DPAD right+down / left+up → `SWIPE_FORWARD` / `SWIPE_BACK`; also a fling on the view | `input/Keys.kt:45`, `MainActivity.kt:50` |
| Double tap | `DOUBLE_CLICK` broadcast and a Back key → `DOUBLE_TAP` / `BACK`, treated as one "back" gesture (700 ms) | `input/Keys.kt:37`, `MainActivity.kt:45`, `MainActivity.kt:97` |
| Touch and hold | Rokid AI. Not used (`LONG_PRESS` → null) | `input/Keys.kt:41` |
| Camera button | `CLICK` broadcast → null (ignored) | `input/Keys.kt:36` |
| Two-finger tap | Not delivered by the glasses (measured, `docs/INTERACTION.md`). Two-finger swipes map to swipes. | `input/Keys.kt:38` |

One gesture can arrive by up to three paths. `HudStore` drops a repeat of the same key inside 600 ms (`MEDIA_KEY_GAP_MS`).
A back gesture that the HUD does not use goes to the system: `exitToHome()` sends the user to the Rokid menu, and the trip
continues (`MainActivity.kt:133`).

All decisions are in `HudStore.onKey` (`state/HudStore.kt:107`). The first matching row wins.

## 2. Gesture map before this change (master, 14b35b7)

Line numbers are for `state/HudStore.kt` at 14b35b7.

| Screen / state | Tap | Swipe | Double tap |
|---|---|---|---|
| Detour offer (trip) | take the new route (`:108`) | zoom map (`:130`) | keep the current route (`:108`) |
| Voice reply: starting | nothing | nothing | cancel (`:168`) |
| Voice reply: listening | stop listening (`:170`) | nothing | cancel (`:171`) |
| Voice reply: check | do the focused row: Send / Redo / Cancel (`:177`) | move the focus (`:175`) | cancel (`:182`) |
| Voice reply: sent / failed | close (`:184`) | nothing | close (`:184`) |
| Card, app with a reply box (no trip) | start a voice reply (`:115`) | **zoom map, unseen** (`:130`) | **leave the app** (`:133`) |
| Card, other (or any card on a trip) | close the card (`:120`) | zoom map (`:130`) | **leave the app** (`:133`) |
| No trip, player hidden, music | show the player (`:124`) | show the player (`:124`) | leave the app (`:133`) |
| No trip, player on screen | play / pause (`:128`) | next / previous track (`:128`) | leave the app (`:133`) |
| No trip, nothing on screen | **change the hidden trip view** (`:129`) | **change zoom, saved on the phone** (`:130`) | leave the app (`:133`) |
| Trip | view: corner map → big map → no map (`:129`) | zoom in / out (`:130`) | leave the app; trip continues (`:133`) |
| Arrived | change the view (`:129`) | zoom (`:130`) | close the arrival screen (`:132`) |

Screens close on their own: card after 6–15 s, player after 10 s, arrival after 10 s, voice result after 2 s (`:201`–`:204`).

## 3. Assessment

What works well:
- **One meaning per gesture.** Tap acts, swipe moves or skips, double tap goes back. The voice reply follows this exactly.
- **Hints say what a tap does** where it matters: "TAP reply by voice", "TAP done · 2× TAP cancel", "2× TAP keep",
  "2× TAP to close now" (`ui/Renderers.kt:776`, `:798`, `:745`, `:826`).
- **No conflict with Rokid system gestures.** Touch and hold stays with Rokid AI, and the camera button stays with the camera.
- **Driver safety.** On a trip, cards are glances only (`HudState.replyAvailable`).
- **Duplicate input is handled.** The 600 ms guard and the 700 ms back guard stop one gesture from acting twice.

Problems found (bold rows in the table above):
1. **Double tap on a card left the app.** Everywhere else, double tap goes back one step (detour, voice reply, arrival).
   On a card it skipped the step and sent the user to the Rokid menu. A reply card also had no way to close it without
   leaving the app or waiting. **Fixed.**
2. **Hidden actions without a trip.** Tap changed the trip view, and swipe changed the zoom. The zoom was also saved on the phone
   (`ZOOM_IN` / `ZOOM_OUT`). Nothing on the screen changed, so the next trip started in a view or zoom the user did not
   pick. A swipe on a card did the same. **Fixed:** without a trip, tap and swipe never change the view or zoom.
3. **Two styles of hint.** The corner detour said "Tap: take · double tap: keep". All other hints say "TAP … · 2× TAP …".
   **Fixed.**
4. **Where am I? / How do I get back?** The idle screen is black (transparent) by design, so nothing says what a tap does.
   Each overlay (card, player, voice) closes itself, and double tap always closes it or goes back. That is a good
   escape rule. **Left as is** (rule 9: nothing to read on the HUD).
5. **The player hides after 10 s, and the first touch only brings it back.** This adds one extra gesture to pause music. It is
   a deliberate choice (commit cff57b3) and stays. The voice command changes which first touch shows it (see 4.2).
6. **Arrived: tap and swipe change the view and zoom,** which the arrival screen does not use. This is harmless, and it closes
   itself in 10 s. **Left as is.**

## 4. The voice command

> Removed 2026-11-04 (user request). Kept as the design record; the current behaviour is in `docs/VOICE.md`.

### 4.1 Which gesture owns it

- **No trip: tap on the empty screen** (no card, no player, phone connected): `state/HudStore.kt:129`.
- **On a trip: swipe back** (phone connected, no detour offer on screen): `state/HudStore.kt:146`.

Why tap without a trip:
- Long press belongs to Rokid AI, the camera button is ignored, and the glasses deliver no two-finger tap.
- Double tap is "back / leave", and the Home decision (option A or B, `docs/INTERACTION.md`) is still open.
- Swipe means "move". A tap that starts something is the same as "TAP reply by voice" on a card.
- On the empty screen, tap had only a hidden effect (problem 2). It was free.

The cost: with music, the **first tap on the black screen is now the command**, not "show the player". **Swipe** shows the
player (then tap pauses, as before). While the player is on screen, tap is still play/pause, so the command is available again
10 s after the last touch. Bryan kept this (open question 1).

Why swipe back on a trip (Bryan asked for the command on a trip too, open question 4). Every trip gesture had a job:
- **Tap** cycles the three views. It is the trip's main gesture, so it stays.
- **Double tap** leaves the app (the trip continues). Taking it would leave no way out of the app during a trip.
- **Long press** is Rokid AI and must not be taken.
- **Swipe forward / back** zoomed in and out over three levels. This was the cheapest to give up. The map already zooms
  itself to frame the next turns, and the phone has a "Map zoom" button that steps through the levels in a loop. So
  **swipe forward now steps the zoom the same way** (default → far → close → default, `CYCLE_ZOOM`, `:145`), and
  **swipe back is free for the command**. The cost: to zoom out from "close" takes two swipes instead of one.
- Driver safety: a swipe is a deliberate gesture, and an accidental start costs little. The card waits for speech, ends on
  silence ("Heard nothing"), and sends nothing. The card takes the narrow right corner in the corner view, so the turn stays
  visible on the left. A detour offer keeps swipe back for itself (nothing happens), so the offer is never covered.

Guards against accidental starts (`state/HudStore.kt:129`, `:146`, `:160`): a repeat of the gesture by another input path
(600 ms), including the tap that closed a card or a voice result; the touch that woke the glasses (600 ms after `onUserWake`);
and no phone link (nothing would answer). Swipe forward on a trip uses the same repeat guard, so one swipe steps the zoom once.

### 4.2 Gesture map after this change

Changed rows only (line numbers: `state/HudStore.kt` now):

| Screen / state | Tap | Swipe | Double tap |
|---|---|---|---|
| Any card | reply (if offered) or close (`:119`, `:121`) | as the screen under it (trip: zoom / command); nothing without a trip | **close the card** (`:121`) |
| No trip, player hidden, music | **voice command** (`:129`) | show the player (`:135`) | leave the app |
| No trip, nothing on screen | **voice command** (`:129`) | nothing (`:140`) | leave the app (`:148`) |
| Trip | view: corner map → big map → no map (`:141`) | **forward: step the zoom** (`:145`); **back: voice command** (`:146`) | leave the app; trip continues (`:148`) |
| Trip, detour offer | take the new route | forward: step the zoom; back: nothing | keep the current route |
| Voice command: starting / listening / result | as voice reply: tap stops, result closes (`:197`–`:213`) | nothing | cancel or close |

### 4.3 How it runs

```
glasses tap (trip: swipe back) ─► GlassesAction(VOICE_START, targetId = VOICE_COMMAND_ID) ─► phone
phone: VoiceReplyController.start ─► glasses microphone + sherpa-onnx (as for replies)
       ─► VoiceState LISTENING (words so far) ─► glasses card "VOICE COMMAND" (narrow trip card: "COMMAND")
       pause / tap (VOICE_STOP) ─► VoiceCommands.opensGrokBot(words) ─► GrokBot.open(context)
       ─► VoiceState SENT "Opening Grok Bot on the phone"  or  FAILED "<reason>" ─► card closes after 2 s
```

- **Protocol:** one new constant, `VOICE_COMMAND_ID = "voice-command"` (`NavMessage.kt`). The existing `VOICE_*` actions and
  `VoiceState` carry it, so nothing changes on the wire (`docs/PROTOCOL.md`).
- **No check step.** Opening an app sends nothing to anyone, and one gesture on the phone undoes it. A reply still needs Send.
- **Matching** (`phone/.../voice/VoiceCommands.kt`): "open" directly followed by "grok" or a likely misspelling of it,
  anywhere in what was heard. The offline model does not know the word "Grok". A misspelling is any word one letter away
  from "grok" (grock, groc, grog, groke, grow, grak), or one of a short list (rock, brock, crock). "bot" may follow or be
  joined ("grokbot", "rockbot"). So "open Grok Bot" and "open Grok" both count (speech to text may drop the last word).
  Without "open" right before it nothing counts: "rock music", "grok bot", "open the grok bot" open nothing, and neither
  do "open maps", "open group", "open book".
- **Not understood:** FAILED shows `Heard "<text>". Say "open Grok Bot"`, so the user sees what the model heard.

### 4.4 Opening Grok Bot on the phone

- **Package:** `GrokBot.PACKAGE = "ai.x.grok.bot"` (`phone/.../voice/GrokBot.kt`): "Grok Bot" on Google Play
  (play.google.com/store/apps/details?id=ai.x.grok.bot). It is a separate app from the Grok chat app (`ai.x.grok`).
  The `<queries>` entry in `AndroidManifest.xml` must stay equal to it.
- **Intent:** `PackageManager.getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK`. This opens the app's normal start screen.
  No deep link is documented for it, so none is used.
- **Package visibility (Android 11+):** without the `<queries>` entry, the launch intent is always null. It is added.
- **Background activity starts (Android 10+):** the phone app is normally in the background (phone in a pocket). Android ignores
  `startActivity` from a background app. A foreground service is **not** an exemption. The usable exemption is the user's
  **Display over other apps** grant (`SYSTEM_ALERT_WINDOW`). The manifest now declares it, so the switch shows in Settings.
  `GrokBot.open` checks `Settings.canDrawOverlays` (or "our app is on screen") first. Without it, the glasses say
  "Allow 885rokidOS to display over other apps" instead of failing silently.
  Other exemptions were considered and not used: a `CompanionDeviceManager` association (heavy pairing flow, and Hi Rokid owns
  the glasses' Bluetooth), and a notification with a full-screen intent (more code, and the user still has to tap it).
- **Locked phone:** Android starts Grok Bot behind the lock screen. The user sees it after unlocking. Nothing in this app can change
  that for another app.

### 4.5 Crash fix in the shared pipeline

`SherpaTranscriber.begin()` runs in `async` under `appScope` (SupervisorJob, no handler). Without the native libraries it throws
`UnsatisfiedLinkError`, which ended the job and crashed the app. It now counts as "no model" and reports
`FAILED "Speech engine failed: UnsatisfiedLinkError"` (`VoiceReplyController.kt:90`). This also protects voice replies.

## 5. What was changed, in short

| Change | Files |
|---|---|
| Double tap closes a card; no hidden view/zoom changes without a trip | `glasses/.../state/HudStore.kt`, `HudStoreTest.kt`, `MainActivity.kt` (debug `previewExpanded` now sets the view directly) |
| Speech engine load errors report FAILED instead of crashing | `phone/.../voice/VoiceReplyController.kt`, `VoiceReplyControllerTest.kt` |
| Voice command "open Grok Bot" | `protocol/.../NavMessage.kt`, `docs/PROTOCOL.md`; glasses `HudStore.kt`, `Renderers.kt` (card without a notification, "VOICE COMMAND" header, player hidden under it), `MainActivity.kt` (screen kept on while voice runs; debug scene `command`); phone `VoiceCommands.kt`, `GrokBot.kt`, `VoiceReplyController.kt`, `NavSessionCoordinator.kt`, `RokidOs885App.kt`, `AndroidManifest.xml`; tests `VoiceCommandsTest.kt`, `VoiceReplyControllerTest.kt`, `HudStoreTest.kt` |
| Misspellings of "Grok" right after "open" count | `phone/.../voice/VoiceCommands.kt`, `VoiceCommandsTest.kt` |
| Command on a trip: swipe back; swipe forward steps the zoom | `glasses/.../state/HudStore.kt`, `Renderers.kt` (short header and hint on the narrow trip card), `HudStoreTest.kt` |
| One hint style | `Renderers.kt` (corner detour) |
| Docs | this file, `docs/VOICE.md`, `docs/INTERACTION.md` (status line), `README.md` (the touchpad menu it described was removed on 2026-10-20) |

Tests: `:protocol:test :phone:testDebugUnitTest :glasses:testDebugUnitTest :tools:test assembleDebug` pass, 132 tests
(119 before), zero failures, both debug APKs built.

## 6. Left out on purpose

- No app launcher framework, no list of apps, no second command, no settings screen. One matcher and one constant.
- No "open Grok Bot and start talking" (no documented deep link or intent for it).
- No general fuzzy matching of whole phrases: one edit away from "grok", a three-word list, and "open" right before it.
- No zoom-out swipe on a trip (swipe forward steps the zoom in a loop; swipe back is the command).
- No Home screen or focus framework (`docs/INTERACTION.md` decisions 1 and 4 are still Bryan's).
- No phone button for the overlay grant. The glasses message says what to do, and it is a one-time switch.
- No timeout for a voice card stuck in "Starting…" when the phone never answers. "2× TAP cancel" shows on it.
- No change to the arrival screen's tap and swipe (harmless, closes itself).
- No wake-word or always-on listening (battery, privacy, and the mic stream needs a gesture to start).

## 7. Questions for Bryan (answered 2026-11-02)

1. **Tap on the black screen with music:** command or show the player? **Answer: the command** (no change).
2. **Misspellings of "Grok":** strict or loose? **Answer: loose**, but "open" must come right before it (section 4.3).
   If the model still writes it another way, the glasses show what was heard: add that word to `VoiceCommands`.
3. **Confirm step** ("Open Grok Bot?") before the app opens? **Answer: no** (no change).
4. **Command during a trip?** **Answer: yes.** Built as swipe back on the trip screen (section 4.1).

## 8. What to test on the real glasses and phone

Setup:
1. Install both debug APKs (the phone installs the HUD on connect).
2. Put the sherpa-onnx native libraries and the model on the phone (`docs/VOICE.md`).
3. Install Grok Bot from Google Play. Check it: `adb shell pm list packages ai.x.grok.bot`.
4. On the phone, open Settings → Apps → 885rokidOS → **Display over other apps** → allow.

Tests:
1. No trip, no music, screen empty. **Tap.** Expect "VOICE COMMAND · STARTING", then "LISTENING" with `Say "open Grok Bot"`.
2. Say "open Grok Bot". Expect "Opening Grok Bot on the phone", and Grok Bot opens on the unlocked phone with the phone app in
   the background. Repeat with "open Grok".
3. Say something else. Expect `Heard "…". Say "open Grok Bot"`. If "open Grok Bot" itself fails, write down what the model
   wrote and add the word to `VoiceCommands` (question 2).
4. Turn off the overlay grant. Repeat test 2 with the phone app in the background. Expect "Allow 885rokidOS to display over
   other apps", and no crash.
5. Repeat test 2 with the phone locked. Expect Grok Bot to be on screen after unlocking.
6. Tap a card to close it. Expect **no** voice command to start from the same tap. Wake the glasses with a touch: expect
   no command from the waking touch.
7. **Double tap** on a card. Expect the card to close and the HUD to stay (no Rokid menu). Double tap again: Rokid menu.
8. Music playing, player hidden. **Swipe** shows the player; tap pauses. **Tap** on the black screen starts the command.
9. Without a trip, tap and swipe on the black screen. Then start a trip. Expect the corner view and the zoom set on the phone.
10. Without the native libraries (or with the model removed), start a voice reply and a command. Expect FAILED with a reason,
    and no crash of the phone app.
11. On a trip (corner view): **swipe back**. Expect a narrow "COMMAND" card in the right corner, the turn still visible on the
    left. Say "open Grok Bot". Then **swipe forward** a few times: the zoom steps default → far → close → default, one step
    per swipe. Tap still changes the view; double tap still leaves the app. With a faster-route offer on screen, swipe back
    does nothing.
12. Debug preview without a phone: `adb shell am start -n com.rokidos885.glasses/.MainActivity --es previewScene command`.
