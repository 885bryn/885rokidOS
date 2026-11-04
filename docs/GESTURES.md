# Glasses gestures (as built, 2026-11-03)

The 885rokidOS HUD uses the right temple touchpad. Code: `glasses/.../state/HudStore.kt` (`onKey`, `voiceKey`).

## The four gestures

| Gesture | General meaning |
|---|---|
| **Tap** | Do the main action of the screen. |
| **Swipe forward / back** | Step or skip. |
| **Double tap** | Back one step. With nothing to go back from, leave to the Rokid menu. |
| **Touch and hold** | Rokid AI assistant (system). The HUD does not use it. |

The touch that wakes a sleeping display counts as a tap. One gesture counts once, even when the glasses report it
two or three times (600 ms guard).

## No trip

| Screen | Tap | Swipe forward | Swipe back | Double tap |
|---|---|---|---|---|
| Empty screen | Start or end Grok Bot | Show the music player (if music plays) | Show the music player (if music plays) | Leave to the Rokid menu |
| Music player (hides after 10 s) | Play or pause | Next track | Previous track | Leave to the Rokid menu |
| Notification card with a reply box | Start a voice reply | Close the card | Close the card | Close the card |
| Other notification card | Close the card | Close the card | Close the card | Close the card |

While a notification card is up, every gesture goes to the card: one swipe closes it instead of revealing the player
or skipping a track. The same gesture reported twice (see the 600 ms guard below) still counts once.

## On a trip

| Screen | Tap | Swipe forward | Swipe back | Double tap |
|---|---|---|---|---|
| Map | Change the view (corner, full map, minimal) | Change the zoom (default, far, close) | Start or end Grok Bot | Leave to the Rokid menu (the trip goes on, on the phone) |
| Detour offer | Take the detour | Change the zoom | — | Keep the current route |
| Notification card | Close the card (no replies on a trip) | Close the card | Close the card | Close the card |
| Arrival screen | As on the map | As on the map | As on the map | Close it |

A notification card hides the trip's own swipe actions while it is up: a swipe closes the card, it does not step the
zoom or start Grok Bot. Once the card is gone the map's gestures are back.

## Grok Bot

| Screen | Tap | Swipe back | Double tap |
|---|---|---|---|
| "GROK BOT" card (2 s after each gesture) | Start or end Grok Bot again | Start or end Grok Bot again | Close the card |
| "GROK BOT · TAP to end" (no trip, chat runs) | End the chat | — | Leave to the Rokid menu |

While a chat runs, the display stays on, so the tap that ends it always arrives.

## Voice reply

| Step | Tap | Swipe forward / back | Double tap |
|---|---|---|---|
| STARTING | — | — | Cancel |
| LISTENING | Stop listening | — | Cancel |
| CHECK (Send / Redo / Cancel) | Do the selected row | Select the next / previous row | Cancel |
| DONE or failed (2 s) | Close | — | Close |
