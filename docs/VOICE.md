# Voice replies: speech to text on the phone

Status (2026-11-01): the engine works on the phone with audio from the glasses' microphone. The reply flow is
being built on top of it (see `docs/INTERACTION.md`, section 4).

## What runs where

- **Glasses microphone** → Rokid SDK audio stream (`startAudioStream`, `onAudioReceived`) → phone. The stream is
  **16 kHz, 16-bit little-endian mono PCM in 640-byte chunks (20 ms)**. It needs the MICROPHONE permission in the Rokid
  authorization (requested by `CxrGlassesLink.authorize`).
- **Speech to text** → [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v2.0.1 (Apache-2.0), running offline on the phone's
  CPU (`SherpaSpeech`). Model: `sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8` (NVIDIA NeMo
  streaming FastConformer transducer, English, 102.8 MB; check the model card's license before sharing the app).
  No audio leaves the phone.
- Android's own `SpeechRecognizer` does **not** work for this: it ignores audio handed to it (`EXTRA_AUDIO_SOURCE`), tested with
  the glasses recording and with a clean synthetic sentence, as a file and as a real-time pipe.

Measured on the phone (Nothing Phone, 2 threads): model load 1.8 s, 5.7 s of audio decoded in 1.4 s. The glasses
recording "hello this is the test" came back exactly.

## Setting up a new checkout (nothing below is committed)

1. **Native libraries.** Download `sherpa-onnx-v2.0.1-android.tar.bz2` (45.3 MB) from
   `https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/`. Copy `jniLibs/arm64-v8a/libonnxruntime.so` and
   `libsherpa-onnx-jni.so` to `phone/src/main/jniLibs/arm64-v8a/` (git-ignored). The other files are not needed.
2. **Kotlin interface.** Committed in `phone/src/main/java/com/k2fsa/sherpa/onnx/` (five files from
   `sherpa-onnx/kotlin-api/` at tag v2.0.1, unchanged; the package name must stay `com.k2fsa.sherpa.onnx`
   because the native library looks classes up by that name).
3. **Model.** Download `sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8.tar.bz2` from
   `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/`, unpack it, and copy `encoder.int8.onnx`,
   `decoder.int8.onnx`, `joiner.int8.onnx` and `tokens.txt` to the phone:
   `adb push <file> /sdcard/Android/data/com.rokidnav.phone/files/stt/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8/`
   (on Windows Git Bash set `MSYS_NO_PATHCONV=1` first). An in-app download is not built yet.

## Debug tools

- Glasses tab → "Voice test (speak for 6 s)" (debug builds): records, shows loudness and the text.
- `adb shell am broadcast -n com.rokidnav.phone/com.rokidnav.phone.debug.VoiceReplayReceiver` transcribes the saved
  recording again (`files/voice-spike.pcm` in the app's private folder). Debug builds only.

## The reply flow (built 2026-11-01)

1. A card from an app with a reply box (`PhoneNotification.canReply`, from `NotificationReplier.remember`) shows
   "TAP reply by voice". During a trip cards stay glances. WeChat has no reply box (log: `reply available=false`).
2. **Tap** starts it (`VOICE_START`). The phone opens the glasses microphone and `VoiceReplyController` feeds the audio to
   `SherpaSpeech`; the words so far go to the glasses (`VoiceState LISTENING`). It stops when you pause for 1.4 s,
   when you tap (`VOICE_STOP`), or after 15 s.
3. **Check screen** (`REVIEW`): the text in the user's style (`Dictation.format`: capital first letter and "I", no punctuation) and three rows. **Swipe** moves, **tap** acts:
   Send (`VOICE_SEND`: `NotificationReplier.reply` fills the app's reply box and fires it), Redo (listen again), Cancel.
   **Double tap** cancels at any step and is consumed by the HUD, so it does not leave to the Rokid menu.
4. `SENT` or `FAILED` shows for 2 s, then the card closes. Nothing is sent without the Send tap.
5. Replies work while the notification is still posted, for up to 2 minutes (`ReplyCache`).

Message text is never logged by the real flow.

## Voice command "open Grok Bot" (built 2026-11-02)

1. With no trip and nothing on the screen (no card, no player), **tap**. On a trip, **swipe back** (swipe forward steps the zoom).
   The glasses show "VOICE COMMAND" ("COMMAND" on the narrow trip card).
2. Say "open Grok Bot". The same pipeline listens (`VoiceReplyController`, id `VOICE_COMMAND_ID`) and stops on a pause or a tap.
3. There is no check step. `VoiceCommands.opensGrokBot` matches "open" right before "Grok" or a likely misspelling of it (one
   letter away, or rock / brock / crock; "Bot" may follow or be dropped), and `GrokBot.open` starts the app on the phone. The glasses show "Opening Grok Bot on the phone" (`SENT`) or the reason
   it did not work (`FAILED`) for 2 s.

Phone setup: allow **Display over other apps** for 885rokidOS (Settings, Apps, 885rokidOS). Without it, Android 10+ does not let
a background app open another app; the glasses then say so. The package is `GrokBot.PACKAGE` (`ai.x.grok.bot`, the "Grok Bot"
app, not the Grok chat app `ai.x.grok`). Details and the test list: `docs/nav-review.md`.

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
