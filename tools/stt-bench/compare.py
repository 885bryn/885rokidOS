"""Compares the app's streaming model with the models OpenWhispr uses (Whisper, Parakeet v3) on the phone's recordings.
Run: python -I compare.py <work folder>. Models must be unpacked in <work folder>/downloads/<name>/<name>/ (README.md)."""
import sys, time, re
sys.path.insert(0, __import__("os").path.dirname(__file__))
import numpy as np, sherpa_onnx
from sttlib import *
DL = ROOT / "downloads"
def w(name, size):
    d = DL / name / name
    return lambda: sherpa_onnx.OfflineRecognizer.from_whisper(encoder=str(d/f"{size}-encoder.int8.onnx"), decoder=str(d/f"{size}-decoder.int8.onnx"), tokens=str(d/f"{size}-tokens.txt"), num_threads=2, language="en", task="transcribe")
def pk():
    d = DL / "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8" / "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    return sherpa_onnx.OfflineRecognizer.from_transducer(encoder=str(d/"encoder.int8.onnx"), decoder=str(d/"decoder.int8.onnx"), joiner=str(d/"joiner.int8.onnx"), tokens=str(d/"tokens.txt"), num_threads=2, model_type="nemo_transducer")
def clean(t): return re.sub(r"[^a-z0-9' ]", " ", t.lower().replace("-", " ")).split()
def raw(f): return np.frombuffer(f.read_bytes(), dtype="<i2").astype(np.float32) / 32768
def emph(x): y = x.copy(); y[1:] -= 0.8 * x[:-1]; return y
def off(rec, x):
    s = rec.create_stream(); s.accept_waveform(16000, x); rec.decode_stream(s); return " ".join(clean(s.result.text))

phr = dict(l.split("\t") for l in (DATA/"accuracy"/"phrases.txt").read_text().splitlines())
# The 2026-11-03 set: 00 is a 0.3 s fragment and its words landed in 01. Delete these two lines for a new recording set.
refs = {k: v for k, v in phr.items() if k != "00"}; refs["01"] = "good to know on my way"
acc = {k: raw(DATA/"accuracy"/f"{k}.pcm") for k in refs}
reps = {f.stem[6:]: raw(f) for f in sorted((DATA/"replies").glob("*.pcm")) if f.stat().st_size > 60000}

engines = {"ours fc1040": None, "whisper base.en": w("sherpa-onnx-whisper-base.en", "base.en"),
           "whisper small.en": w("sherpa-onnx-whisper-small.en", "small.en"), "parakeet v3": pk}
fc = recognizer()
out = {}
for name, make in engines.items():
    for pre in (True, False):
        if name == "ours fc1040" and not pre: continue
        try:
            rec = make() if make else None
        except Exception as e:
            print(f"{name}: skipped ({e.__class__.__name__}; is it unpacked in downloads/?)"); break
        dec = (lambda x: " ".join(clean(decode(fc, emph(x) if pre else x)))) if rec is None else (lambda x: off(rec, emph(x) if pre else x))
        t = time.time(); errs = 0; bad = []
        for k, r in refs.items():
            h = dec(acc[k]); e, _ = wer(r, h); errs += e
            if e: bad.append(f"{k}:{h}")
        ta = time.time() - t
        t = time.time(); texts = {k: dec(x) for k, x in reps.items()}; tr = time.time() - t
        label = f"{name}{'' if pre else ' (no pre-emph)'}"
        out[label] = texts
        print(f"{label:32s} short replies wrong {errs:2d}/46  time {ta:4.1f}s + {tr:4.1f}s   {' | '.join(bad)}", flush=True)
print()
for k in reps:
    print(k)
    for label, texts in out.items(): print(f"   {label:32s} {texts[k]}")
