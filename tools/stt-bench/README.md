# Speech tests on the PC (`tools/stt-bench`)

Scores speech models on recordings from the glasses microphone, with the app's settings. Results so far: `docs/VOICE.md`
("Word list for T3 dictation", "Second pass").

The recordings are the user's own speech. Keep them, the models and the output in `.tmp/stt-bench/` (git-ignored). Never
commit them.

## Set up (once per machine)

1. Python 3.12 (tested; the venv keeps it apart from other Python installs): `py -3.12 -m venv .tmp/stt-bench/venv`, then
   `.tmp/stt-bench/venv/Scripts/python -m pip install "sherpa-onnx==2.0.1" numpy`.
2. Copy the recordings and the app's model from the phone (debug build, Nothing A059P over wireless ADB). In Git Bash:

   ```bash
   W=.tmp/stt-bench; mkdir -p $W/data/accuracy $W/data/replies $W/models/fc1040 $W/downloads
   for f in $(adb shell run-as com.rokidos885.phone ls files/accuracy | tr -d '\r'); do adb exec-out run-as com.rokidos885.phone cat files/accuracy/$f > $W/data/accuracy/$f; done
   for f in $(adb shell run-as com.rokidos885.phone ls files/voice-replies | tr -d '\r'); do adb exec-out run-as com.rokidos885.phone cat files/voice-replies/$f > $W/data/replies/$f; done
   M=/sdcard/Android/data/com.rokidos885.phone/files/stt/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-1040ms-int8
   for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do MSYS_NO_PATHCONV=1 adb pull $M/$f $W/models/fc1040/; done
   ```

3. For `compare.py`, download and unpack each model in its own folder:
   `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/<name>.tar.bz2` into
   `.tmp/stt-bench/downloads/<name>/` for `sherpa-onnx-whisper-base.en`, `sherpa-onnx-whisper-small.en` and
   `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` (199 + 607 + 465 MB). A model that is missing is skipped.

## Run

- `.tmp/stt-bench/venv/Scripts/python -I tools/stt-bench/compare.py .tmp/stt-bench`: our streaming model against Whisper
  and Parakeet v3, with and without pre-emphasis. Prints wrong words on the ten Accuracy test phrases, and every model's text
  for each recording in `voice-replies`. Do not paste those texts into commits or docs without the user's agreement.
- `.tmp/stt-bench/venv/Scripts/python -I tools/stt-bench/hotwords_test.py .tmp/stt-bench`: word lists and boosts on the ten
  phrases.

`data/replies` has no reference text. Compare the models by reading, or write the reference for each file first.
