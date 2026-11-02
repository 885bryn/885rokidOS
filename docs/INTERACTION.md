# 885rokidOS interaction model (PROPOSAL, 2026-11-01)

Status: **proposal, not built.** The countdown on the notification card is built. Everything below needs
the user's decision first (see "Decisions").

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

The idle screen already uses tap, swipes and double tap (music). Three options:

| | How Home opens | Music controls | Exit to Rokid menu |
|---|---|---|---|
| **A (recommended)** | **Double tap** on the idle screen when music plays; **tap** when nothing plays | unchanged: tap = play/pause, swipe = skip | Home row "Exit to Rokid menu"; double tap on idle with no music |
| B | Tap always opens Home | moves into a Music screen (one more tap) | double tap on Home |
| C | Swipe forward opens Home | skip becomes tap-then-swipe | double tap on idle |

A keeps today's music behavior. The cost: with music playing, double tap no longer leaves the app by itself;
Home has an Exit row for that.

### Home rows (first version)

Now playing (only when something plays) · Notifications (the last 10, newest first) · Navigation (how to
start a trip, trip status) · Settings (volume of voice, units, demo mode) · Exit to Rokid menu.
Later features add rows; the framework does not change.

## 3. Notification countdown (built)

The card shows the seconds left in its top-right corner (bold, 26 px) and redraws once a second. The time is
6 s for a short message, longer for long text, 15 s at most. A tap used to hide the card; with actions (below)
a tap opens them instead and the countdown stops.

## 4. Responding to a notification (proposal)

While a card shows:

- **Tap** opens its **actions**: the card becomes a focus list and the countdown is replaced by "hold".
  Swipe moves, tap fires, double tap closes the card.
- **Rows** (up to 4 visible, in this order):
  1. **Quick replies**, only when the notification has a reply action (WhatsApp, Messages, Telegram,
     Signal and others do). Three phrases, edited on the phone; defaults "OK", "On my way", "Can't talk now".
  2. The app's **own action buttons** without text input (for example "Mark as read", "Archive", "Like").
  3. **Dismiss** (clears the notification on the phone).
- After a row fires: "Sent" or "Done" for 1.5 s, then the card closes.
- **How it works on the phone:** the notification listener keeps the notification's actions for 2 minutes. A
  reply fills the app's own `RemoteInput` with the chosen text and sends the app's `PendingIntent`, which is
  what the notification shade does. Dismiss calls `cancelNotification`. No account access is needed.
- **Protocol (additive):** `PhoneNotification` gets `actions: List<NotifAction(id, label, kind)>` (default
  empty); the glasses answer with `GlassesAction(NOTIFICATION_ACTION, targetId = "<notification id>|<action id>")`.
  Old HUDs ignore both.
- **While navigating:** cards stay glance-only (no actions), because tap and swipe already steer the trip
  HUD. This is the safe default; the user can change it.

### Later: dictated replies

The phone's microphone and speech recognizer can take a spoken reply: row "Dictate" shows "Listening…",
the phone transcribes, the glasses show the text with Send / Cancel rows. This needs the phone nearby and
is not part of the first version. Using the glasses' microphone is unconfirmed (VERIFY).

## 5. Decisions

1. Home entry: option **A**, B or C?
2. Quick replies: keep the three defaults and edit them on the phone, or other phrases?
3. Allow actions on cards during a trip? (Recommended: no.)
4. Build order: notification actions first (smaller), then Home and the focus framework?
