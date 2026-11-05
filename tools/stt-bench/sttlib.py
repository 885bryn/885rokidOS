"""Shared code for the speech tests: the app's streaming model with the settings of SherpaSpeech.kt (2 threads, pre-emphasis 0.8,
2.1 s pad), hotword files, and word error count. The work folder (argv[1]) holds data/, models/, downloads/ (see README.md)."""
import sys, time, pathlib, re
import numpy as np
import sherpa_onnx

ROOT = pathlib.Path(sys.argv[1])
MODEL = ROOT / "models" / "fc1040"
DATA = ROOT / "data"
OUT = ROOT / "out"
OUT.mkdir(exist_ok=True)

TOKENS = {}
for line in (MODEL / "tokens.txt").read_text(encoding="utf-8").splitlines():
    tok, idx = line.rsplit(" ", 1)
    TOKENS[tok] = int(idx)


def write_vocab():
    """bpe.vocab from tokens.txt, as SpeechVocabulary.bpeVocab does in the app."""
    lines = [f"{t}\t{0 if t.startswith('<') else -i}" for t, i in TOKENS.items()]
    (MODEL / "bpe.vocab").write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_hotwords(name, phrases, boost=None):
    lines = []
    for p in phrases:
        toks = p.lower()
        lines.append(toks + (f" :{boost}" if boost else ""))
    path = OUT / f"hotwords-{name}.txt"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return str(path)


def recognizer(method="greedy_search", hotwords="", score=1.5, paths=4):
    kw = dict(
        tokens=str(MODEL / "tokens.txt"), encoder=str(MODEL / "encoder.int8.onnx"),
        decoder=str(MODEL / "decoder.int8.onnx"), joiner=str(MODEL / "joiner.int8.onnx"),
        num_threads=2, sample_rate=16000, feature_dim=80, decoding_method=method,
        model_type="nemo_transducer", max_active_paths=paths,
    )
    if hotwords:
        if not (MODEL / "bpe.vocab").exists(): write_vocab()
        kw.update(hotwords_file=hotwords, hotwords_score=score, modeling_unit="bpe", bpe_vocab=str(MODEL / "bpe.vocab"))
    return sherpa_onnx.OnlineRecognizer.from_transducer(**kw)


def load(path):
    x = np.frombuffer(path.read_bytes(), dtype="<i2").astype(np.float32) / 32768
    y = x.copy(); y[1:] -= 0.8 * x[:-1]
    return y


def decode(rec, samples):
    s = rec.create_stream()
    s.accept_waveform(16000, samples)
    s.accept_waveform(16000, np.zeros(int(16000 * 2.1), dtype=np.float32))
    s.input_finished()
    while rec.is_ready(s):
        rec.decode_stream(s)
    return rec.get_result(s).strip().lower()


def wer(ref, hyp):
    # "I'm" = "I am" and "10" = "ten": Whisper writes digits.
    digits = {"1": "one", "2": "two", "3": "three", "4": "four", "5": "five", "6": "six", "7": "seven", "8": "eight", "9": "nine", "10": "ten"}
    norm = lambda t: [digits.get(w, w) for w in re.sub(r"\bi'm\b", "i am", t).split()]
    r, h = norm(ref.lower()), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)], len(r)
