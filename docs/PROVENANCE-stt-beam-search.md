# Provenance: STT beam search change

- Change: `phone/.../voice/SherpaSpeech.kt`, `decodingMethod` `"greedy_search"` → `"modified_beam_search"`.
- Written by DeepSeek V4.1 Flash Free via opencode, in a T3 Code thread.
- Directed by Bryan by voice through his Rokid glasses only; no keyboard.
- Date: 2026-11-03.
- Checked on the build box: `./gradlew test assembleDebug` passed (253 test cases, 0 failures). Not yet tested on the phone.
- Outcome (2026-11-04): on the 10 glasses recordings beam search gave the same words as greedy for every model tried, so
  greedy is back. The gain came from the 1040 ms model instead (docs/LOCAL_TEST_RESULTS.md).
