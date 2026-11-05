# Glasses gestures (as built, 2026-11-05)

The 885rokidOS HUD uses the right temple touchpad. Code: `glasses/.../state/HudStore.kt` (`onKey`, `voiceKey`).

## The four gestures

| Gesture | Meaning everywhere |
|---|---|
| **Tap** | Do the main action of the screen. |
| **Swipe forward / back** | Move: to another page, or to the next row or track. A swipe never starts an action. |
| **Double tap** | Back one level: close the card, cancel, go from a page to home. At home, leave to the Rokid menu. |
| **Touch and hold** | Rokid AI assistant (system). The HUD does not use it (decided 2026-11-05). |

**Wake rule.** The touch that wakes a sleeping display only wakes it, on every screen. The next touch acts. One
gesture counts once, even when the glasses report it two or three times (600 ms guard).

**Awake and sleep.** The HUD stays awake for the system's screen timeout (at least 10 s) after the last touch, or after
a trip, card, voice step or Grok Bot chat lets go of the display. News from T3 starts the time again. The last 4 s
count down (4, 3, 2, 1) in the top-right corner. Then the HUD lets the display sleep. On a trip the display stays on.
The hold is a screen wake lock that does not restart the system timeout when released, so the display sleeps at once.

## No trip

Home is in the middle: T3 page ← home → music player.

| Screen | Tap | Swipe forward | Swipe back | Double tap |
|---|---|---|---|---|
| Home, awake ("ACTIVE · TAP for Grok Bot") | Start or end Grok Bot | Show the music player (if music plays) | Show the T3 page | Leave to the Rokid menu |
| Music player (hides after 10 s) | Play or pause | Next track | Previous track | Home |
| T3 page | Start dictation to T3 | Next reply part; home from the last part (or with no reply) | Previous reply part | Home |
| Notification card with a reply box | Start a voice reply | Close the card | Close the card | Close the card |
| Other notification card | Close the card | Close the card | Close the card | Close the card |

While a notification card is up, every gesture goes to the card: one swipe closes it instead of revealing the player
or skipping a track.

The T3 page stays the page while the display sleeps, always at full brightness; its footer says "TAP to speak" or
"TAP to wake". Dictation to T3 uses the voice reply steps below, CHECK included. The page shows the words sent and the run
state. When the answer comes (a reply, T3 waiting on the PC, or a failure), the T3 page takes the screen and the
display wakes, unless a trip or a voice step is on. The phone reads the reply aloud in parts of a few sentences. The
page shows the part being read in big text ("READING 2/5") and keeps the display on until the reading ends. A swipe
picks a part; then the reading no longer moves the page. The phone also says when T3 waits for an answer on the PC
or the run failed. A new dictation or Grok Bot stops the reading. Each message to T3 ends with a
short note: it was dictated, and the reply is read aloud, so T3 starts with a plain 1–3 sentence summary.

## On a trip

The map is home on a trip.

| Screen | Tap | Swipe forward | Swipe back | Double tap |
|---|---|---|---|---|
| Map | Start or end Grok Bot | Change the view (corner, full map, minimal) | — | Leave to the Rokid menu (the trip goes on, on the phone) |
| Detour offer | Take the detour | Change the view | — | Keep the current route |
| Notification card | Close the card (no replies on a trip) | Close the card | Close the card | Close the card |
| Arrival screen | As on the map | As on the map | As on the map | Close it |

Zoom is not on the glasses: the map frames the next turns itself, and the phone has a "Map zoom" button.

## Grok Bot

| Screen | Tap | Swipe | Double tap |
|---|---|---|---|
| "GROK BOT" card (2 s after each tap) | Start or end Grok Bot again | — | Close the card |
| "GROK BOT · TAP to end" (no trip, chat runs) | End the chat | — | Leave to the Rokid menu |

While a chat runs, the display stays on, so the tap that ends it always arrives.

## Voice reply

| Step | Tap | Swipe forward / back | Double tap |
|---|---|---|---|
| STARTING | — | — | Cancel |
| LISTENING | Stop listening | — | Cancel |
| CHECK (Send / Redo / Cancel) | Do the selected row | Select the next / previous row | Cancel |
| DONE or failed (2 s) | Close | — | Close |
