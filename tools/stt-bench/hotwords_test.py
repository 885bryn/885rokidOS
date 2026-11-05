import sys, time
sys.path.insert(0, __import__("os").path.dirname(__file__))
from sttlib import *

phr = dict(l.split("\t") for l in (DATA/"accuracy"/"phrases.txt").read_text().splitlines())
refs = {k: v for k, v in phr.items() if k != "00"}
refs["01"] = "good to know on my way"
audio = {k: load(DATA/"accuracy"/f"{k}.pcm") for k in refs}

REPLIES = list(phr.values()) + ["sounds good", "see you soon", "thank you", "talk later", "be right there", "i love you"]
WORDS = ["thanks", "thank you", "you", "check", "love", "there", "later", "tomorrow", "minutes", "sounds good", "no problem"]
CODING = ["github", "commit", "kotlin", "gradle", "pull request", "branch", "merge", "claude", "grok", "rokid", "sherpa", "glasses", "deploy", "refactor"]

configs = [("greedy", dict())]
configs.append(("beam4", dict(method="modified_beam_search")))
for name, lst in [("replies", REPLIES), ("words", WORDS), ("coding", CODING)]:
    hw = write_hotwords(name, lst)
    for sc in (1.5, 3.0):
        configs.append((f"beam4+{name}@{sc}", dict(method="modified_beam_search", hotwords=hw, score=sc)))

results = {}
for name, cfg in configs:
    rec = recognizer(**cfg); t = time.time(); errs = tot = 0; hyps = {}
    for k, ref in refs.items():
        h = decode(rec, audio[k]); e, n = wer(ref, h); errs += e; tot += n; hyps[k] = h
    results[name] = hyps
    print(f"{name:22s} wrong {errs:2d}/{tot}  ({time.time()-t:.1f} s)")
print()
for k, ref in refs.items():
    print(f"{k} REF  {ref}")
    for name in results:
        h = results[name][k]
        if h != ref: print(f"   {name:22s} {h}")
