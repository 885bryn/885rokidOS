Task: 885rokidOS, branch forge/t3-dictation-pause (PR #11). Speech-to-text accuracy for T3 dictation. Done on a PC without
install rights for the phone: T3 dictation waits 5 s for a pause and listens up to 60 s; first and last words are kept; T3
dictation uses a word list (SpeechVocabulary.T3_WORDS, beam search); SttBenchReceiver (debug) times speech engines on the phone.
PC results: docs/VOICE.md ("Word list for T3 dictation", "Second pass"). The word list fixed names only; Whisper small.en and
Parakeet v3 (the models OpenWhispr uses) fixed 6–7 of 9 known errors in real dictations, but they are not streaming.
Next: on the build machine, follow docs/TASKS.md, "Open now: speech tests on the phone" (install, PR #11 checks, measure
Parakeet v3 on the phone, decide on a second pass with the user).
Not done: nothing is installed or tested on the phone. Phone speed of beam search and of Parakeet is unknown.
Previous note (Grok Bot from a pocket, forge/nav-voice-grokbot) is in git history.
