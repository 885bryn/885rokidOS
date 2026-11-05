# Voice replies: speech to text on the phone

Status (2026-11-01): the engine works on the phone with audio from the glasses' microphone. The reply flow is
being built on top of it (see `docs/INTERACTION.md`, section 4).

## What runs where

- **Glasses microphone** → Rokid SDK audio stream (`startAudioStream`, `onAudioReceived`) → phone. The stream is
  **16 kHz, 16-bit little-endian mono PCM in 640-byte chunks (20 ms)**. It needs the MICROPHONE permission in the Rokid
  authorization (requested by `CxrGlassesLink.authorize`).
- **Speech to text** → [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v2.0.1 (Apache-2.0), running offline on the phone's
  CPU (`SherpaSpeech`). Model: `sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-1040ms-int8` (NVIDIA NeMo
  streaming FastConformer transducer, English, 1040 ms chunks, about 100 MB; the 80ms-int8 model is the fallback when it is missing; check the model card's license before sharing the app).
  No audio leaves the phone.
- Android's own `SpeechRecognizer` does **not** work for this: it ignores audio handed to it (`EXTRA_AUDIO_SOURCE`), tested with
  the glasses recording and with a clean synthetic sentence, as a file and as a real-time pipe.

Measured on the phone (Nothing Phone, 2 threads): model load 1.8 s, 5.7 s of audio decoded in 1.4 s. The glasses
recording "hello this is the test" came back exactly.

## Setting up a new checkout (nothing below is committed)

1. **Native libraries.** Download `sherpa-onnx-v2.0.1-android.tar.bz2` (45.3 MB) from
   `https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/`. Copy `jniLibs/arm64-v8a/libonnxruntime.so` and
   `libsherpa-onnx-jni.so` to `phone/src/main/jniLibs/arm64-v8a/` (git-ignored). The other files are not needed.
2. **Kotlin interface.** Committed in `phone/src/main/java/com/k2fsa/sherpa/onnx/` (seven files from
   `sherpa-onnx/kotlin-api/` at tag v2.0.1, unchanged; the package name must stay `com.k2fsa.sherpa.onnx`
   because the native library looks classes up by that name).
3. **Model.** Download `sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-1040ms-int8.tar.bz2` from
   `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/`, unpack it, and copy `encoder.int8.onnx`,
   `decoder.int8.onnx`, `joiner.int8.onnx` and `tokens.txt` to the phone:
   `adb push <file> /sdcard/Android/data/com.rokidos885.phone/files/stt/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-1040ms-int8/`
   (on Windows Git Bash set `MSYS_NO_PATHCONV=1` first). Let the app make that folder: start one voice reply (or the
   replay below) first. A folder made with `adb shell mkdir` belongs to `shell`, and the app cannot read it; it then
   falls back to the 80 ms model. An in-app download is not built yet.

## Debug tools

- Glasses tab → "Voice test (speak for 6 s)" (debug builds): records, shows loudness and the text.
- `adb shell am broadcast -n com.rokidos885.phone/com.rokidos885.phone.debug.VoiceReplayReceiver` transcribes the saved
  recording again (`files/voice-spike.pcm` in the app's private folder). Debug builds only.

## The reply flow (built 2026-11-01)

1. A card from an app with a reply box (`PhoneNotification.canReply`, from `NotificationReplier.remember`) shows
   "TAP reply by voice". During a trip cards stay glances. WeChat has no reply box (log: `reply available=false`).
2. **Tap** starts it (`VOICE_START`). The phone opens the glasses microphone and `VoiceReplyController` feeds the audio to
   `SherpaSpeech`; the words so far go to the glasses (`VoiceState LISTENING`). It stops when you pause for 1.4 s,
   when you tap (`VOICE_STOP`), or after 15 s. T3 dictation (`T3_CARD_ID`) waits for a 5 s pause and stops after
   60 s, so a pause to think in the middle of a sentence does not end it (`ListenLimits`).
3. **Check screen** (`REVIEW`): the text in the user's style (`Dictation.format`: capital first letter and "I", no punctuation) and three rows. **Swipe** moves, **tap** acts:
   Send (`VOICE_SEND`: `NotificationReplier.reply` fills the app's reply box and fires it), Redo (listen again), Cancel.
   **Double tap** cancels at any step and is consumed by the HUD, so it does not leave to the Rokid menu.
4. `SENT` or `FAILED` shows for 2 s, then the card closes. Nothing is sent without the Send tap.
5. Replies work while the notification is still posted, for up to 2 minutes (`ReplyCache`).

Message text is never logged by the real flow.

## Grok Bot by tap (built 2026-11-02; the spoken command came first)

1. With no trip and nothing on the screen (no card, no player), **tap**. On a trip, **swipe back** (swipe forward steps the zoom).
   The glasses send `OPEN_GROK_BOT` (no speech step) and show a "GROK BOT" card.
2. `GrokBot.open` starts a Grok Bot voice chat on the phone (`ACTION_ASSIST`; the app's home screen if that is missing). The
   voice chat uses the glasses mic and speaker. On the lock screen, `GrokBotUnlockActivity` turns the screen on and removes the
   lock screen first; this works only when Smart Lock keeps the phone unlocked, else the glasses say "Unlock the phone for Grok Bot".
3. The glasses show "Opening Grok Bot on the phone" (`SENT`) or the reason it did not work (`FAILED`) for 2 s.
4. When the phone screen was off at the gesture, `ScreenLockService` locks the phone again once Grok Bot records (at most
   8 s), so the screen goes off in the pocket. A screen that was on stays on. The chat keeps running. Turn the service on in Settings, Accessibility, "885rokidOS
   screen lock" (Android 13+: first allow restricted settings in the 885rokidOS app info). Without it the screen stays on.
5. A second tap during a chat ends it (Grok Bot ends the chat on a second `ACTION_ASSIST`). The phone sees the chat by the
   audio mode (`GrokBot.running`: `MODE_IN_COMMUNICATION`) and the glasses say "Closing Grok Bot". A tap on the Grok Bot
   card (up for 2 s after each tap) does the same; a double tap only closes the card.
6. The touch that wakes a sleeping display counts as the tap (since 2026-11-03). It does not always reach the app, so while a
   chat runs the phone sends `GrokBotState` (it polls the audio mode each second) and the glasses keep the display on and show
   "GROK BOT · TAP to end". The tap that ends the chat then never meets a sleeping display.

The spoken form ("open Grok Bot" after `VOICE_START` with `VOICE_COMMAND_ID`, matched by `VoiceCommands.opensGrokBot`) was
removed on 2026-11-04 at the user's request: Grok Bot is started by the gesture only. `VoiceCommands` and the matching
path are deleted; `VOICE_COMMAND_ID` now only names the card that has no notification under it.

### Phone setup for Grok Bot from a pocket (tested on the Nothing A059P, 2026-11-03)

1. Install **Grok Bot** from Google Play (`ai.x.grok.bot`, not the Grok chat app `ai.x.grok`). Tested with 1.19.0.
2. Allow **Display over other apps** for 885rokidOS (Settings, Apps, 885rokidOS). Without it, Android 10+ does not let a
   background app open another app; the glasses then say so.
3. Make the glasses a **Smart Lock trusted device** (Google "Extend Unlock", Trusted devices, add the glasses, here
   `Glasses_4251`; the Settings path differs per phone). This is the workaround for the lock screen:
   - Grok Bot started behind the lock screen stays hidden and never listens (tested with both `KEYCODE_ASSIST` and
     `ACTION_ASSIST`). Grok Bot itself does not support the lock screen.
   - With the glasses connected, Extend Unlock keeps the phone "unlocked" (`dumpsys trust`: `deviceLocked=0`), but the lock
     screen still shows. So `GrokBotUnlockActivity` turns the screen on, calls `requestDismissKeyguard` (no PIN needed while
     trusted), then starts Grok Bot.
   - Without trust (glasses off, or Extend Unlock expired), the phone does not open the PIN screen in the pocket; the glasses
     say "Unlock the phone for Grok Bot" (not yet tested).
4. Turn on **885rokidOS screen lock** (Settings, Accessibility). On Android 13+, first allow **restricted settings** in the
   885rokidOS app info (⋮ menu), because the app is not from Google Play. Without it the phone screen stays on after a pocket
   start. A lock by this service (like the power button) keeps Smart Lock trust and the running chat.
5. Optional, set during testing: Grok Bot as the **digital assistant** (Settings, Default apps). The app starts Grok Bot by
   package, so this is probably not needed; not tested without it.

Grok Bot uses the glasses mic and speaker (Bluetooth call audio, SCO) only when that link is up at the start; once it chose the
phone speaker. Details and the earlier test list: `docs/nav-review.md`; device results: `docs/LOCAL_TEST_RESULTS.md`.

## Accuracy tuning (2026-11-01)

The glasses microphone sends almost only low sounds (96% of the energy under 1 kHz, centroid about 380 Hz), so consonants are
faint: "thanks" became "things", "good to know" became "curtail" or "got it know". Ten short replies were recorded from the
glasses (Glasses tab, "Accuracy test", debug builds) and scored on the PC against what was asked for (the NVIDIA 80 ms model):

| Audio fed to the model | Wrong words (of 46) |
|---|---|
| As recorded | 9 (19.6%) |
| Louder (scaled to 0.3 / 0.6 / 0.9 of full scale) | 8 / 10 / 9: **volume does not matter** |
| High-frequency shelf boost 6 / 12 / 18 dB | 8 / 8 / 10 |
| **Pre-emphasis y[n] = x[n] - c * x[n-1]**, c = 0.5 / 0.6 / 0.65 / 0.7 / 0.75 / 0.8 / 0.85 / 0.9 / 0.97 | 7 / 7 / 2 / **1** / 3 / **1** / 2 / 4 / 5 |

Built in: `SpeechAudio.preEmphasize` with c = 0.8 (middle of the good range). Clean recordings still decode the same.
Ten phrases from one speaker is a small sample: re-run the Accuracy test after changes, and add phrases that go wrong.
Other levers, not yet tried: a more careful model (NeMo streaming 1040 ms, 104 MB; Parakeet-TDT-CTC 110M, 104 MB), and a word list.

Lost words (2026-11-05, user report: "soy sauce" often missing in T3 dictation). Two causes in the app, fixed:
- The controller listened to `link.audio` only after `startAudio`, and that flow keeps nothing for a late listener, so the
  first chunks could be lost. It now listens first and queues every chunk; chunks still queued at a tap are decoded too.
- `finish` padded 0.8 s of silence, less than one 1040 ms chunk, so a word said just before a tap could stay undecoded.
  The pad is now 2.1 s (`SherpaSpeech.TAIL_PAD_MS`).
Not fixed by this: the glasses mic barely carries "s" sounds (see above), so "soy sauce" can still come out wrong. Next
step: record the phrases that fail (debug builds keep the last one as `files/voice-last.pcm`) and score them on the PC
against a word list (sherpa-onnx hotwords) and the larger models.

Word list for T3 dictation (2026-11-05). sherpa-onnx "hotwords" favour given words. Tested on the PC with the 1040 ms model, the
app's settings, the ten Accuracy test phrases and the last 20 voice replies from the phone (`files/voice-replies`):

| Settings | Ten short replies, wrong words (of 46) | 20 real dictations |
|---|---|---|
| Greedy (as before) | 6 | "deep sea" / "deep seak", "amoges" |
| Beam search, 4 paths, no list | 6 | same |
| Beam + the exact ten phrases, boost 1.5 / 3 | 6 / 6 | — |
| Beam + the exact ten phrases, boost 5 / 8 / 12 | 8–14 / 18–22 / 181–400: the model makes up phrases | — |
| **Beam, 8 paths + `SpeechVocabulary.T3_WORDS`, boost 2** | **6** | **"deepseek" (2 of 2)**, no new errors |

- The short replies go wrong on weak audio, not on rare words: even the exact phrase did not fix "love you too" ("let me too").
  So message replies keep greedy decoding with no list.
- Names gain: T3 dictation (`ListenLimits.DICTATION`) uses the list. With a boost of 3, "emojis" also came right, but
  "stretch" became "stread".
- Not fixed by any list: "pole latest" (pull latest), "japanese so i saw" (soy sauce), "stark a stretch" (start a new thread).
- sherpa-onnx needs a `bpe.vocab` to split the hotwords; the NeMo model has none. `SherpaSpeech` writes one from `tokens.txt`
  (`SpeechVocabulary.bpeVocab`) and `hotwords.txt` next to the model. No setup step. If the write fails, dictation runs without the list.
- Cost: beam search decoded about 1.8 times slower on the PC. Not yet measured on the phone.
- Pre-emphasis on the 1040 ms model: c = 0.7 gave 5 wrong words, 0.6 and 0.75–0.95 gave 6. One word, so 0.8 stays.
- The list was made from the same recordings it was tested on. Re-check it on new dictations.

## Second pass: the models OpenWhispr uses (2026-11-05, PC only)

OpenWhispr is a desktop dictation app (Windows, Mac, Linux; not Android). Its local engines are Whisper and NVIDIA Parakeet
(through sherpa-onnx, as here), so its models were scored against ours with `tools/stt-bench/compare.py`. Same PC, 2 threads.
"Known errors" are the nine wrong words of our model in the 20 dictations: "deep sea" / "deep seak", "pole" / "poor" /
"told" (pull), "amoges" / "amogis" (emojis), "so i saw" (soy sauce), "stark a stretch" (start a thread).

| Model | Size | Ten short replies, wrong (of 46) | Known errors fixed (of 9) | New errors | Time, 20 dictations |
|---|---|---|---|---|---|
| **Ours, FastConformer 1040 ms** (streaming) | 131 MB | 6 | 0 | — | 4–6 s |
| Whisper base.en | 433 MB | 3 | 6 | 2 | 7–13 s |
| Whisper small.en, no pre-emphasis | 1.3 GB (370 MB int8) | **1** | **7** | 1 | 21–40 s |
| Parakeet TDT 0.6B v3 int8 | 641 MB | 6 | 6 | 2–3 (no text for some 1–2 word clips) | 6 s |

- Whisper and Parakeet read the whole recording after the speaker stops: no words on the glasses while speaking, no end of
  speech. So they can only be a **second pass**: our model streams the words and finds the end, then the second model
  decodes the whole recording again and its text goes to the check screen.
- Candidate: **Parakeet v3 for T3 dictation only**, on the raw audio (no pre-emphasis), keeping our text when Parakeet gives
  none or much less. It was as fast as our model on the PC. Whisper small.en was the most accurate but 3–6 times slower, and
  Whisper can invent text on silence.
- No model heard "pull latest" in the two 4.6 s clips ("paul", "poll"): likely the audio.
- **Not yet measured on the phone:** load time, decode time and memory. `SttBenchReceiver` (debug builds) measures them; see
  `docs/TASKS.md`, "Open now". Decide on the second pass only after that.
