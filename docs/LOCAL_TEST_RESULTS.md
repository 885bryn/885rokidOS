# Local device test — 2026-11-02

Branch: `forge/nav-voice-grokbot`, commit `044ca2a`.
Device: Nothing A059P, wireless ADB.

| Check | Expected | Actual | Result |
|---|---|---|---|
| Local build and tests | Both debug APKs; zero failures | 132 tests, zero failures; both APKs built; bundled HUD hash matches | Pass |
| Phone installation and speech model | New phone package with four model files | Installed; all four model hashes match the preserved originals | Pass |
| Google key restrictions | New phone package allowed | User confirmed the update | User confirmed |
| Glasses installation and navigation | New HUD connects and shows guidance | User reports navigation works after removing the old HUD | Pass, user report |
| Navigation audio routing | Guidance through glasses | User reports guidance seems to use the phone; Spotify still uses glasses | Debt; user deferred |

No audio routing fix is requested. The user does not use guidance audio while driving with the map.

Voice tests in `nav-review.md` section 8 are pending. No command transcript is available yet.
Permission check: precise location and notifications allowed; microphone denied; overlay grant not enabled;
new app absent from enabled notification listeners.

Follow-up permission check: overlay and microphone allowed. Notification access is still absent from Android enabled listeners. Voice check can proceed; notification card tests need this access.

| Debug six-second voice check | Transcribe recorded speech without a crash | Audio captured; speech engine throws while opening decoder.int8.onnx, system error 13 (permission denied) | Fail |

Filtered crash excerpt: `java.lang.Exception: Load model .../decoder.int8.onnx failed:system error number 13`, from `OnlineRecognizer.newFromFile` through `SherpaSpeech` and `VoiceSpike.transcribe`. Saved in `.tmp/device-crash-filtered.log`.

The device-local copy left model files owned by shell with mode 660. Two repair attempts: write replacement files with run-as (permission denied); change model modes to 664 (changed, but app-account read check still fails). Stopped at the two-attempt limit. No code changed. Hash matches alone did not prove app readability. The next session must resolve model access before more voice tests.

## Model access repair — passed

Reinstalled all four verified model files with the documented adb push method. Kept the previous device copies with the .before-repair suffix. No app source changed.

Debug VoiceReplayReceiver completed transcription of the saved 5.7-second recording. Completion log at 18:24:25 device time: model load 9993 ms, decode 12889 ms. Recognized speech is omitted from the saved diagnostic log. The app remained running (PID 12803). This verifies model loading and decoding; a fresh microphone check is still pending.

The earlier run-as external-storage read check did not establish actual app access. Use the in-app replay result as the validation. For model setup, retain the documented adb push procedure; a device-local shell copy with matching hashes did not prove access.

| Fresh six-second voice check after repair | Capture and transcribe speech without crashing | User reports good now; exact transcript not supplied | Pass, user report |

| Section 8, test 1 | Tap empty idle HUD; STARTING then LISTENING | User confirmed expected prompt | Pass, user report |
| Section 8, test 2a | Say open Grok Bot; show opening result and launch Grok Bot | User confirmed it opened | Pass, user report |

Exact recognized words for the command have not been supplied. Short form open Grok remains pending.

| Wake touch guard | Waking touch does not start a command; one tap works once awake | User confirmed extra tap is only needed when waking | Pass, user report |
| Section 8, test 2b | Say open Grok; launch Grok Bot | User confirmed it opened | Pass, user report |
| Section 8, test 3 | Say open maps; show heard-text failure and do not open Grok Bot | User confirmed expected behavior; exact displayed text not supplied | Pass, user report |

| Section 8, test 4 | Overlay permission off; background command shows permission message without a crash | User confirmed expected message and no crash | Pass, user report |
| Section 8, test 5 | Restore overlay permission; command while phone locked; Grok Bot visible after unlocking | User confirmed Grok Bot was on screen | Pass, user report |

Section 8 test 6 is blocked: user reports no notification cards. ADB confirms the new app is absent from enabled_notification_listeners. The phone app is running. Notification card behavior is not yet scored.

Notification access follow-up: user enabled the special access; ADB confirms com.rokidos885.phone is now in enabled_notification_listeners. Fresh notification delivery and test 6 remain pending.

| Section 8, test 6 card close | Single tap closes non-reply card without starting a command | User reports card closes; no separate prompt report | Partial: close confirmed; command guard not explicit |
| Section 8, test 7 card double tap | Double tap closes card and keeps HUD open | User answered yes and confirmed both single and double tap close the card | Pass, user report |
| Fresh notification delivery | New phone notification appears on glasses | User confirmed a card appeared after access was enabled | Pass, user report |

| Section 8, test 8 media controls | Swipe reveals player; tap pauses; visible-player swipe skips tracks | User confirmed all three behaviors | Pass, user report |
| Section 8, test 8 hidden-player command | Tap empty screen with music starts voice command | User reports Spotify player appears instead | Fail; wake path suspected |

Code finding: HudStore.onUserWake always sets mediaShown=true when no notification is present. HudViewModel.onForeground calls it. This can reveal the player on a wake before the tap command path runs. Proposed fix: preserve wake-touch suppression but do not reveal the player on wake; let swipes reveal it. No fix applied; awaiting user direction.

## Music wake fix — built and phone installed

Changed HudStore.onUserWake to record the wake guard without revealing the player. Swipes still reveal the player. Updated the media debug preview and existing media tests. Added a regression test for player timeout, wake touch suppression, the next tap starting voice, and swipe revealing the player after cancellation.

Documented full verification command passed: 133 tests, zero failures; both debug APKs built; bundled HUD hash matches. Phone update installed successfully. New bundled HUD is 0.2.0+74e33c34. No new glasses handshake observed yet; user must connect in the Glasses tab, then repeat the music wake test.

| Music wake fix, hardware retest | After Spotify player hides and waking touch is ignored, next tap starts voice command | User confirmed voice prompt appears after update | Pass, user report |

| Section 8, test 9 view | Idle tap/swipes do not change next trip corner view | User confirmed normal view; exact zoom not explicitly confirmed | Partial: view passed |
| Section 8, test 11 trip command | Swipe back shows command card; spoken command opens Grok Bot | User confirmed Grok Bot opens; card geometry not explicitly confirmed | Partial: launch passed |
| Trip start wake | Starting a drive wakes and displays HUD | User reports app sometimes needs one touchpad tap to wake at trip start | Intermittent issue; cause unconfirmed |

Explained current trip controls: swipe forward cycles default, far, close, default; swipe back starts voice. Separate directional zoom was intentionally replaced. Trip zoom cycle, view tap, exit double tap, and detour suppression remain pending hardware confirmation.

| Section 8, test 11 zoom cycle | Forward swipes step default, far, close, default once per swipe | User confirmed cycle works | Pass, user report |
| Section 8, test 11 tap view | Tap changes map view during trip | User confirmed | Pass, user report |

| Section 8, test 11 exit | Double tap exits to Rokid menu while trip continues on phone | User confirmed | Pass, user report |

Faster-route suppression remains untested on hardware: no documented action to force a live offer was found in the setup docs or phone debug UI. Unit coverage for swipe-back suppression passed. Optional missing-model test and standalone debug preview remain unrun.

Speech-to-text sample reported by user for spoken open Grok Bot: `opengrogwat (1495ms)`. Exact spacing needs confirmation. As one word, `opengrogwat` does not match VoiceCommands, which requires the separate word open followed by Grok or an accepted misspelling. This debug sample does not overturn the earlier successful command launches. No matcher change applied.

User clarified the recognized sample as `open grogwat` (1495 ms). Current matcher rejects grogwat. User requested broader speech quality work rather than only adding a spelling. No new matcher change applied.

Grok integration finding: current GrokBot.open launches the phone app only. It does not start a Bot voice chat, select a Bot, forward the CXR microphone to Grok Bot, or map glasses gestures to its UI. Official mobile docs describe selecting a Bot chat and Start voice chat with an empty composer; glasses audio input/output still needs a device test.

User requires Grok Bot with the phone locked and in a pocket, including starting the session from glasses. Read-only package inspection of installed Grok Bot 1.19.0 found enabled Android VoiceInteractionService, VoiceInteractionSessionService and an ACTION_ASSIST trampoline. Current default assistant and voice interaction service are Google. This is a candidate integration path, not proof of locked-screen voice support or glasses audio routing. Next: user checks/selects Grok Bot in default digital assistant settings, then validate locked-screen invocation before changing launch code.

## Grok Bot as digital assistant, phone locked — 2026-11-02 23:44 device time

User set Grok Bot 1.19.0 as the digital assistant. ADB confirms `voice_interaction_service` is `ai.x.grok.bot/expo.modules.nativehelpers.GrokBotVoiceInteractionService`, enabled and bound. Grok Bot also has `AssistTrampolineActivity` for `android.intent.action.ASSIST`. Glasses were connected (A2DP and SCO); phone locked.

| Check | Expected | Actual | Result |
|---|---|---|---|
| Test A: `input keyevent KEYCODE_ASSIST`, phone locked | Grok Bot voice chat starts; glasses mic and speaker | System opened a Grok Bot session; it finished after about 55 ms. No recording. User saw and heard nothing | Fail |
| Test B: `am start -a android.intent.action.ASSIST -p ai.x.grok.bot`, phone locked | Same | Trampoline activity started but stayed hidden behind the keyguard (`isVisible=false`, `isSleeping=true`), then finished. No recording. User saw and heard nothing | Fail |

Glasses A2DP dropped during test B; SCO stayed connected. Cause unconfirmed.
Earlier Grok Bot recording at 23:41 (before these tests, phone state unknown) used the `VOICE_COMMUNICATION` source, which normally routes to a Bluetooth headset microphone.
Conclusion so far: Grok Bot 1.19.0 does not start voice over the keyguard from either entry point. No launcher code changed.

| Test B again, phone unlocked (23:46:32) | Grok Bot voice chat starts; glasses mic and speaker | Voice chat started. Grok Bot called `setCommunicationDevice(bt_sco)`; SCO audio connected. Recording source `VOICE_COMMUNICATION`, input `AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET`, echo cancel and noise suppression active. User hears Grok in the glasses | Pass |

At 23:41 Grok Bot chose `speaker` instead; the glasses SCO link only became available at 23:44:00. Grok Bot appears to use the glasses only when SCO is up when the chat starts. Glasses A2DP stayed disconnected after test B; cause unconfirmed.
Result: `ACTION_ASSIST` sent to `ai.x.grok.bot` starts a voice chat with glasses audio, but only when the phone is unlocked. Current `GrokBot.open` uses the launch intent and opens only the home screen.

## GrokBot.open uses ACTION_ASSIST — built and phone installed

`GrokBot.open` now sends `ACTION_ASSIST` with package `ai.x.grok.bot`; the launch intent stays as a fallback. Full verify command passed (BUILD SUCCESSFUL, both debug APKs). Phone app installed.

| Glasses command, phone unlocked | Tap, say "open Grok Bot"; voice chat starts in the glasses | User confirmed it works | Pass, user report |

| Smart Lock (Extend Unlock), phone on lock screen, glasses command | Grok Bot voice chat starts | Glasses showed "Opening Grok Bot on the phone"; phone stayed on the lock screen; no voice chat | Fail, user report |

ADB at 23:52: `dumpsys trust` shows trustState=TRUSTED, deviceLocked=0, GoogleTrustAgent "Kept unlocked by Extend Unlock". So the device is not locked, but the keyguard still covers the Grok Bot activity. Nothing dismisses the keyguard.

## Lock screen dismiss before Grok Bot — built and phone installed

`GrokBot.open` now starts `GrokBotUnlockActivity` when the keyguard shows but the device is not locked (Smart Lock). The activity turns the screen on, calls `requestDismissKeyguard`, then starts Grok Bot with `ACTION_ASSIST`. A really locked device returns "Unlock the phone for Grok Bot". Full verify command passed; phone app installed.

| Glasses command, phone locked in pocket, Smart Lock active | Grok Bot voice chat starts in the glasses | User confirmed it works | Pass, user report |

Not tested: the "Unlock the phone for Grok Bot" message with Smart Lock off.

## Grok Bot by tap — built and installed

Glasses send `OPEN_GROK_BOT` on an idle tap or a trip swipe back; no speech step. Full verify command passed: 134 tests, zero failures. Phone app (with bundled HUD) installed.

| Tap trigger | One tap starts a Grok Bot voice chat in the glasses | User confirmed | Pass, user report |
| Second tap during a chat | Not designed | Ends the Grok Bot chat (user report). Likely Grok Bot treats a second `ACTION_ASSIST` as a toggle; not verified in logs | Observed, kept |
| Screen after start | Not built yet | Phone screen stays on | Expected; no screen-off code exists |
| Power button during a Grok Bot chat | Chat keeps running with the screen off | User confirmed | Pass, user report |

## Screen lock after Grok Bot start — built and installed

`ScreenLockService` (accessibility service, `GLOBAL_ACTION_LOCK_SCREEN`) locks the phone after `GrokBotUnlockActivity` starts Grok Bot from the lock screen: once any recording is active plus 1 s, or after 8 s. Verify command passed; phone app installed. User enabled restricted settings and the service.

| Tap from pocket, phone locked, Smart Lock and service on | Grok Bot talks in the glasses; screen turns off | User confirmed | Pass, user report |

## Grok Bot close tap and screen rule — 2026-11-03

Changes: the waking touch counts as a tap; a tap on the 2 s Grok Bot card goes to the phone again; the phone says "Closing Grok Bot" while a chat runs (audio mode `MODE_IN_COMMUNICATION`); the phone polls the chat each second after a gesture and sends `GrokBotState`, and the glasses hold the display with "GROK BOT · TAP to end" while it runs; `ScreenLockService` runs only when the phone screen was off at the gesture. Verify command passed: 137 tests, zero failures. Phone app (bundled HUD) installed.

| One tap closes a running chat, also after the glasses display would sleep | One tap, every time | User confirmed | Pass, user report |
| Phone screen off at the tap | Phone locks again after Grok Bot starts | User confirmed | Pass, user report |
| Phone screen on at the tap | Screen stays on | User confirmed | Pass, user report |

Not measured: battery cost of the held display during a chat.

## Spoken command removed — 2026-11-04

At the user's request the spoken "open Grok Bot" command is gone: `VoiceCommands`, its test and the matching path in
`VoiceReplyController` are deleted, and the debug `previewScene command` is gone with it. Grok Bot is started and closed
by a tap (or swipe back on a trip) only, as in `docs/GESTURES.md`. Verify command re-run: 131 tests, zero failures, both
debug APKs built. Hardware retest of the gesture path was not repeated; it is unchanged since 2026-11-03.

## "GROK BOT · TAP to end" stuck with no chat — 2026-11-03

Seen on the Nothing A059P: the glasses showed "GROK BOT · TAP to end" with no Grok Bot chat. `dumpsys audio` showed the
mode owner was WhatsApp (pid 14880, `MODE_IN_COMMUNICATION` since 19:52), not Grok Bot. The phone took that mode for a
chat, so the tap said "Closing Grok Bot" and the watcher kept the glasses in the chat state. Fix: a mode that is already
on when the tap arrives, with no chat of ours running, is another app's call; the phone opens Grok Bot but does not follow
the mode. A resync now always sends `GrokBotState`, so a reconnect clears a stuck HUD. New test
`anotherAppsCallIsNotTakenForAGrokBotChat`. Verify: 135 tests, zero failures.

Retest during the WhatsApp call: no "TAP to end", and the tap that ended the chat said "Opening Grok Bot". `dumpsys audio`
showed that each Grok Bot chat adds its own call recording (source VOICE_COMMUNICATION, silenced) from its start to its
end. Second fix: during another app's call, the phone counts call recordings at the tap (`GrokBot.callRecordings`), and
a chat is one more than that. Test `duringAnotherAppsCallTheChatIsTheExtraCallRecording`. Verify: 135 tests, zero failures.
Hardware retest (user, Nothing A059P, WhatsApp call on): tap shows "GROK BOT · TAP to end"; tap again closes Grok Bot and
says "Closing Grok Bot". Pass, user report. Android does show other apps' call recordings to 885rokidOS.

## Notifications only with the phone screen off — 2026-11-04

User choice: the glasses show a phone notification only while the phone screen is off; with the screen on the user sees
it on the phone. `PhoneNotificationListener` drops a notification when `PowerManager.isInteractive` is true (lock screen
on counts as on). An update to the same notification after the screen goes off is still shown. Not yet tested on the phone.

## Speech to text: model and decoder on the glasses recordings — 2026-11-04

10 recordings from the glasses mic (`.tmp/accuracy`, the reply phrases). Scored on the PC with sherpa-onnx 2.0.1, the app's
settings (2 threads, pre-emphasis 0.8, the endpoint rules, 0.8 s pad). Recording 00 is a 0.3 s fragment; its words landed
in 01, so 00 is skipped and 01 is "good to know on my way". "I'm" = "I am". 46 words.

| Model | Wrong words | Decode, 9 files (PC) |
|---|---|---|
| FastConformer 80 ms (before) | 9 | 6.8 s |
| FastConformer 480 ms | 9 | 2.9 s |
| **FastConformer 1040 ms (chosen)** | **6** | **1.9 s** |
| Nemotron 0.6B streaming 1120 ms (~600 MB) | 8 | 6.0 s |
| Parakeet TDT 0.6B v2, not streaming (631 MB) | 6–7 | 3.8 s |

- `modified_beam_search` gave the same words as greedy for every model (also with 4, 8, 16 paths on 80 ms): greedy is kept.
- Pre-emphasis off, cutting the leading silence, and gain to a fixed peak each made the 80 ms model worse (11–16 wrong).
  Pre-emphasis off also made the 1040 ms model worse on 04.
- Left wrong with 1040 ms: "sounds good see there" (you), "let me share I can" (check and), "let me too" (love you).
  Parakeet and Nemotron also miss 04 and 08, so the audio itself is likely unclear there.
- Cost: words show about 1 s later while speaking; the end of speech is found up to ~0.5 s later.
- Small sample: 10 phrases from one speaker.
- On the Nothing A059P (replay of 05.pcm through `VoiceReplayReceiver`): 1040 ms heard "no problem thanks", load 1.9 s,
  decode 247 ms; 80 ms heard "no problem thinks", load 2.0 s, decode 1462 ms. Not yet tested live through the glasses.

## Second-pass speech model on the phone — 2026-11-05

Branch `forge/t3-dictation-pause`, commit `714d6c3`, debug build. Nothing A059P (11.1 GB RAM), wireless ADB. `SttBenchReceiver`
over the 30 saved recordings (186 s of audio: 10 short replies in `files/accuracy`, 20 T3 dictations in `files/voice-replies`).
Both engines use 2 threads. The texts stay in the app's private `files/stt-bench.tsv`; only counts are here.

| Engine | Load | Decode, all 30 | Slowest (a 12.4 s dictation) | Memory after (RSS / peak) |
|---|---|---|---|---|
| Ours, FastConformer 1040 ms, T3 word list (streaming, fed at full speed) | 2.3 s | 21.4 s | 1.46 s | 352 / 398 MB |
| Parakeet TDT 0.6B v3 int8 (whole recording) | 4.3 s | 17.1 s | 1.19 s | 1034 / 1034 MB |

- The app was not killed. Parakeet adds about 530 MB while loaded.
- 20 T3 dictations: our model (with the word list) still has 5 of the known wrong words ("pole", "amogis", "so i saw" and
  others); Parakeet has none. Our model gave 2 empty texts, Parakeet 2.
- 10 short replies (`phrases.txt`, 01 scored as "good to know on my way", `tools/stt-bench` scoring): ours 6 of 46 words
  wrong, Parakeet 6. Same as the PC.
- Against the TASKS.md rule (not killed, a 12 s dictation in about 3 s or less): **passes**. Decision on building the second
  pass is with the user.

Whisper small.en int8 (`small.en-*.int8.onnx`, 2 threads), added to `SttBenchReceiver` the same day, because the user finds
words wrong in message replies too. Same recordings (a later run: 178 s of audio).

| Engine | Load | Short replies wrong (of 46) | Decode of a 5 s reply (median / slowest) | 12 s dictation | Known wrong words in T3 dictations | Memory (RSS / peak) |
|---|---|---|---|---|---|---|
| Ours (word list) | 1.9 s | 6 | 0.54 / 0.61 s (while speaking) | 1.3 s | 5 | 370 / 390 MB |
| Parakeet v3 | 4.6 s | 6 | 0.43 / 0.44 s | 1.1 s | 0 | 1061 MB |
| Whisper small.en | 3.0 s | **1** | **6.8 / 10.8 s** | 6.3 s | 1 | 1047 / 1193 MB |

- Whisper is the only model that is better on short replies, but with 2 threads each reply waited about 7 s.

Threads and Whisper base.en (`--es engines … --ei threads …`, same recordings). Times vary between runs; small.en with 4
threads ran twice.

| Engine | Threads | Short replies wrong (of 46) | 5 s reply, median / slowest | 12 s dictation | Known wrong words in T3 dictations | RSS |
|---|---|---|---|---|---|---|
| Whisper small.en | 2 | 1 | 6.8 / 10.8 s | 6.3 s | 1 | 1047 MB |
| Whisper small.en | 4 | **1** | **1.6 / 2.3 s** (repeat: 1.6 / 1.9 s) | 5.7–11.4 s | 1 | 1153 MB |
| Whisper base.en | 2 | 3 | 0.6 / 1.5 s | 1.9 s | 0 | 666 MB |
| Whisper base.en | 4 | 3 | 0.5 / 0.6 s | 3.8 s | 0 | 657 MB |

- Short replies: Whisper small.en with 4 threads, about 1.6 s after the speaker stops, 1 wrong word of 46 (ours 6).
- Long T3 dictations: Whisper small.en is too slow (6–11 s); Parakeet (1.1 s, 0 known wrong words) stays the candidate.
- Whisper base.en is a cheaper middle: 3 of 46, under 1 s for a reply, 0 known wrong words in the dictations.

## Speech tests with the glasses: replies and T3 instructions — 2026-11-05

Debug buttons on the Glasses tab (`VoiceSpike.REPLY_SET`, `T3_SET`): the user read each phrase into the glasses microphone,
then the phone scored the three models on the same audio. Waits are after the end of speech (second models load while the
user speaks).

- First run: 12 of 18 recordings were digital silence (peak 1) from about 30 s on: the glasses display slept (nothing held it),
  and a sleeping display sends only zeros from the microphone. With each phrase shown on the glasses as a card (display held),
  all 18 recordings of the second run had speech. Real voice steps hold the display, so they are not affected.
- Second run, replies (10 phrases, 71 words): ours 4 wrong, Parakeet 7, Whisper small.en 12. Waits: 0.06 / 0.5 / 1.9 s.
  Whisper misheard short phrases ("Hope you did help anytime", "Ready, lay, start without me").
- Second run, T3 (8 phrases, 59 words; the user left out "the" in the soy sauce line, scored without it): ours 9 wrong,
  Parakeet 6 (2 of them "Deep Seek"), Whisper 1. Waits: 0.14 / 0.8 / 2.6 s (slowest 3.1 s).
- Ours: "poll latest branch and rhonotists", "amojies", "poll request", "cutland". Parakeet fixed pull, run the tests, emojis.
- Replies over both reply sets (117 words): ours 10, Parakeet 13, Whisper 13. No second model is better for replies.
