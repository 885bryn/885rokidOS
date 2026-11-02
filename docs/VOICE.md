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
