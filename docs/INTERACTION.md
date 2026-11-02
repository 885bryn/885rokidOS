# 885rokidOS interaction model (PROPOSAL, 2026-11-01)

Status: **proposal, not built.** The countdown on the notification card is built. Everything below needs
the user's decision first (see "Decisions"). Voice replies (section 4) are built: see `docs/VOICE.md`. The
gestures as built today, and the voice command, are in `docs/nav-review.md` (2026-11-02).

Mockups: `docs/hud-mockups/Home.dc.html`, `NotificationActions.dc.html`, `Notification.dc.html` (countdown).

## 1. What the touchpad can do (measured 2026-11-01)

| Input | What the HUD receives | Use |
|---|---|---|
| Tap | key 83, then ENTER | **enter / confirm** |
| Swipe forward / back | key 83, then DPAD_RIGHT (22) and DPAD_DOWN (20) / the left and up keys | **move focus** (next / previous) |
| Double tap | `DOUBLE_CLICK` broadcast and a Back key | **back**; at the app root the system sends the user to the Rokid menu |
| Touch and hold | Rokid AI | system, never used |
| Camera button | `BUTTON_DOWN`, `BUTTON_UP`, `CLICK` broadcasts | camera, never used |

The glasses deliver no two-finger tap. So the whole app uses four verbs: **move, enter, back, exit**.

## 2. Navigation model: focus and stack

- Every screen is a short list (at most 4 rows visible; the list scrolls). **One row has focus**: a thin
  outline and full brightness; other rows 60 %.
- **Swipe** moves the focus. **Tap** opens or fires the focused row. **Double tap** goes back one screen.
- Screens form a stack. **Home** is the root. Double tap on Home is not consumed, so the system takes it and
  the user lands in the Rokid menu, as today.
- Rows are 56 px tall with 30 px labels, so a glance reads them (the new screens use the larger type scale).
- Inactivity: after 8 s without input a menu returns to the idle screen (and the display sleeps as usual).

### Getting to Home

"Home" is a small menu of everything the OS does (notifications history, navigation, settings, later features).
The problem: on the idle screen with music, every gesture is already used (tap = pause, swipe = skip, double
tap = leave the app). So one gesture must open Home. Two options (option C, swipe, is dropped: it makes skipping slow):

| To do this | Option A (recommended) | Option B |
|---|---|---|
| Pause or play | **tap** | tap (open Home), tap (open Now playing), tap |
| Skip a song | **swipe** | tap, tap, then swipe |
| Open Home | **double tap** | **tap** |
| Leave to the Rokid menu | Home, last row "Exit", tap | double tap on Home |
| Nothing is playing | tap opens Home, double tap leaves | same as with music |

- **A** keeps music exactly as it is now. The price: while music plays, double tap opens Home instead of
  leaving the app, so leaving takes one more step (the Exit row).
- **B** makes Home the first screen for everything. Music moves one level down, so pause and skip cost more taps,
  but double tap always leaves the app, as the user described.

### Home rows (first version)

Now playing (only when something plays) · Notifications (the last 10, newest first) · Navigation (how to
start a trip, trip status) · Settings (volume of voice, units, demo mode) · Exit to Rokid menu.
Later features add rows; the framework does not change.

## 3. Notification countdown (built)

The card shows the seconds left in its top-right corner (bold, 26 px) and redraws once a second. The time is
6 s for a short message, longer for long text, 15 s at most. A tap used to hide the card; with actions (below)
a tap opens them instead and the countdown stops.

## 4. Voice replies to messages (proposal)

Goal: answer an Instagram, WhatsApp, Messages or Telegram message by speaking, from the glasses.

### What the user does

1. A card appears with a countdown. **Tap** it. The countdown stops.
2. The card shows rows: **Speak a reply** (focused), the app's own buttons (for example "Like"), **Dismiss**.
3. **Tap "Speak a reply".** The glasses show "Listening…" with a level bar and a 10 s limit. Speak.
   Tap again to stop early; silence for 1.5 s also stops it.
4. The glasses show the text in large type, with rows **Send** (focused), **Redo**, **Cancel**.
   **Tap Send.** The text goes to the chat. The card shows "Sent" for 1.5 s and closes.
5. **Double tap** at any step goes back one step. After Cancel nothing is sent.

Sending needs one deliberate tap, so a mis-heard phrase is never sent by itself.

### How it works

- **Sending:** the phone's notification listener holds the notification's own reply action for 2 minutes
  (until the notification is removed). It fills the app's `RemoteInput` with the text and fires the app's
  `PendingIntent`, which is what the "Reply" button in the notification shade does. No account access is
  needed. WhatsApp, Messages, Telegram and Signal offer this; Instagram shows a "Reply" button in the
  notification (VERIFY on this phone that it sends inline and does not open the app).
- **Microphone:** the SDK streams the glasses' microphone to the phone (`startAudioStream`,
  `onAudioReceived(byte[])`). It needs the MICROPHONE permission added to the Rokid authorization (one more
  approval in Hi Rokid). The audio format is not documented (VERIFY with a spike: log chunk size and rate).
  If the glasses' mic cannot be used, the fallback is the phone's microphone (the phone must be close).
- **Speech to text, in order of preference:**
  1. **On the phone, offline:** Android's on-device recognizer, fed with the glasses' audio. Private and free.
     Needs Android 13+ (this phone has it); VERIFY that the recognizer accepts a supplied audio stream.
  2. **Cloud:** Google Cloud Speech-to-Text. Better accuracy for names and noise, but it sends the audio
     to Google and needs an API key and billing. Only if option 1 is not good enough.
- **Protocol (additive):** `PhoneNotification` gets `canReply: Boolean` and `actions: List<NotifAction>`
  (default empty). New messages: `GlassesAction(NOTIFICATION_ACTION, targetId)`, a phone-to-glasses
  `VoiceState(listening | text | sent | failed, text, level)`. Old HUDs ignore them.
- **During a trip:** cards stay glance-only (tap and swipe already steer the trip HUD).

### First spike (before the full feature)

1. Add the MICROPHONE permission, call `startAudioStream`, log what arrives.
2. Run the on-device recognizer on that audio and compare with what was said.
3. Fire a reply on a real Instagram and WhatsApp notification.
Each step is small and shows whether the plan works on this hardware.

## 5. Decisions

1. Home: option **A** or B?
2. Voice replies: send only after a "Send" tap (recommended), or send automatically after a 3 s countdown?
3. Speech to text: try the offline on-device recognizer first (recommended)?
4. Build order: the voice-reply spike first, then Home and the focus framework?
