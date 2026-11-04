# Coding by voice into T3 Code, from the Rokid glasses

Research note, 2026-11-04. Scope: investigate only. No code changed, no commit, no push.
Every claim below is marked **verified** (seen in source, docs, or by running a command) or **unverified**.
The base is `885rokidOS` at `d0781f0` plus the local state of this PC.

## Result

**Use a small relay service on the PC, and keep speech to text on the phone.**
The phone sends the finished prompt over the LAN to the relay. The relay runs `t3 thread send` and polls
`t3 thread show`. The phone shows the agent's short reply on the HUD, and can speak it.
This is the only path that needs no new hardware, no Grok Bot, and no change to T3.
Build cost: one Node file on the PC, one new phone class, one new HUD card.
First test costs nothing: `t3 thread start --wait . "Reply with exactly: OK"` (verified command).

---

## 1. What already exists on this PC (verified)

| Fact | Evidence |
|---|---|
| T3 Code (Beta) v0.1.6 desktop is installed at `C:\Users\Bryan li\AppData\Local\Programs\t3code`. | Process list, `server-runtime.json` |
| Its server listens on `http://127.0.0.1:3773` only, PID 7088. | `Get-NetTCPConnection`, `server-runtime.json` |
| A `t3` CLI exists at `C:\Users\Bryan li\.t3\bin\t3.cmd`. It is **not** on `PATH`. | Ran it |
| The CLI has `thread list / start / send / show` plus `pair`, `up`, `status`, `serve`, `app`. | `t3 --help`, `t3 thread --help` |
| `t3 thread send <thread> <message>` puts a message into a chosen thread. `-` reads the message from stdin. | Help text; source `cli.thread.send` calls MCP tool `t3_thread_send` with `mode:"queue"` |
| `t3 thread start <project> <message>` makes a new thread. `.` means the project of the current folder. | Help text, T3 docs |
| `--wait` prints the agent's reply. **Exit 1** = run failed, **exit 2** = the agent waits for an answer. | Help text |
| `t3 thread show <thread> --json --messages N` returns `status`, `activeRunId`, `pendingRequestCount`, and `messages[].text`. | Ran it on a live thread |
| One CLI call costs about **0.9 s** (start-up of the bundled Electron binary as Node). | Measured: 0.85–0.99 s over 3 runs each |
| The server also exposes an MCP endpoint at `<origin>/mcp`. The CLI signs in as a short-lived MCP client and revokes it at the end. | Source: `withThreadTools` → `issueMcpClientSession`, comment "the same way an outside agent does" |
| The HTTP API needs a bearer token, a browser cookie, or a DPoP token. `GET /api/orchestration/shell` returns 401 without one. | Ran `GET /api/auth/session` and `/api/orchestration/shell` |
| The web UI is served at `/`, with `manifest.webmanifest` and icons, so it installs as a PWA on a phone. | `GET /` and manifest in the bundle |
| T3 has **no** speech-to-text of its own: 0 hits for `dictat`, `transcrib`, `speechRecogn`, `whisper` in the 14.7 MB server bundle. | Source scan |
| Tailscale is **not** installed on this PC. The LAN address is `192.168.68.67`. | `tailscale status`, `Get-NetIPAddress` |
| The PC has an RTX 5070 (12 GB), Python 3.12.10, `sherpa_onnx` 2.0.1 and numpy. No torch, no ffmpeg. | `nvidia-smi`, `pip list`, `ffmpeg -version` |
| Phone app already depends on OkHttp, so it can do plain HTTP today. | `phone/build.gradle.kts` |
| Glasses microphone audio is 16 kHz, 16-bit mono PCM, 640-byte chunks (32 KB/s). | `docs/VOICE.md`, `GlassesLink.startAudio/audio` |

T3 documents for remote access (verified from the T3 docs, `docs/user/remote-access.md` and
`docs/user/outside-agents.md`):
- `t3 up --lan` / `t3 serve --lan` accept LAN and tailnet connections. "A pairing code is all that keeps
  other devices on them out."
- Tailscale HTTPS is supported: `t3 serve --tailscale-serve`, `t3 pair --tailscale`.
- T3 Connect is a cloudflared tunnel. Execution still stays on this PC.
- Outside agents must sign in over **https** or **localhost**. A plain `http://192.168.x.x:3773` address lets an
  agent find the server but not finish sign-in.
- On Windows there is **no** background service. Closing the last desktop window stops the server.

## 2. The options, ranked

| # | Option | Effort | Latency | Accuracy | Needs | Risks |
|---|---|---|---|---|---|---|
| **1** | **PC relay + phone STT (recommended)** | 2–3 days | Prompt sent ~2 s after you stop talking; reply when the agent finishes | Same as today: 6 wrong words of 46 on test phrases (`docs/VOICE.md`, verified) | PC relay, LAN or Tailscale | One more process to keep running; CLI call ~0.9 s; accuracy for code words is the weak point |
| **2** | Option 1 + re-decode the same audio on the PC | +1–2 days | +2–4 s before the prompt is sent | Likely better; **unverified** for identifiers | Option 1, model file on PC, ~640 MB RAM/VRAM | PC and phone may disagree; two texts to show |
| **3** | Phone talks to T3's own HTTP/MCP API with a pairing token | 3–5 days | ~0.2 s | n/a | `t3 --lan` on, pairing, token in the phone | Undocumented RPC surface; plain-HTTP LAN cannot finish MCP sign-in (verified docs); breaks on T3 updates; a token that can drive all threads on the PC |
| **4** | Pair the phone browser to T3 (LAN or T3 Connect), type with the phone keyboard's voice key | ~1 hour | Fast | Google keyboard, no code words | T3 network access on, one pairing | **Not the goal**: you speak into the phone, not the glasses. Good fallback when the glasses are off |
| **5** | Windows dictation (Win+H or Voice Access) with the glasses as a PC microphone | ~1 hour if it worked | Fast | Windows dictation, weak on identifiers | Glasses paired to the PC as a headset | **Unverified and unlikely**: the glasses pair to the phone, not the PC. No Rokid device is paired to this PC today (verified). Win+H also types into the focused window, not a chosen thread |
| **6** | Grok Bot | 0 | — | — | Grok Bot app | Excluded by the request |

Option 1 wins because every part of it exists: the microphone stream, the STT engine, the HUD card,
the confirmation screen, and a T3 command that already does exactly "send this text to that thread".

## 3. The recommended path

### 3.1 The flow

1. Glasses show a small **T3** card when the phone has a bridge address. It says "TAP to dictate".
2. Tap. The glasses microphone streams. The phone shows the words live, as it does for a notification reply.
3. You pause, or tap again. The phone stops and shows the **CHECK** screen: `SEND TO T3` / `REDO` / `CANCEL`.
4. Tap `SEND TO T3`. The phone POSTs the text to the relay. The relay calls
   `t3 thread send <thread> -` with the text on stdin.
5. The glasses show "T3 · working". The phone polls `GET /v1/reply`.
6. When the run ends, the relay returns the last assistant message. The glasses show the first 4 lines.
   A tap closes it. The phone may also speak it (see 3.6).

### 3.2 Step by step build plan

**Step 0 — smoke test, no code.** Run this in the repo on the PC (verified command form from the T3 docs):

```powershell
cd "C:\Users\Bryan li\Documents\Codex\Rokid Navigation"
& "C:\Users\Bryan li\.t3\bin\t3.cmd" thread start --wait . "Reply with exactly: OK"
```

It must print `OK` and exit 0. If the T3 desktop window is closed, it fails with "server not running".

**Step 1 — PC relay (new folder `tools/voice-bridge/`).**

| File | Content |
|---|---|
| `tools/voice-bridge/bridge.mjs` | Node http server, no npm packages. `POST /v1/prompt`, `GET /v1/reply`, `POST /v1/new`. Calls the CLI with `child_process.spawn`, message on stdin (`-`), never through a shell. |
| `tools/voice-bridge/config.json` | `{ "token": "...", "threadId": "...", "projectPath": "...", "port": 8791, "t3": "C:\\Users\\Bryan li\\.t3\\bin\\t3.cmd" }`. Git-ignored. |
| `tools/voice-bridge/README.md` | Start command, firewall rule, token rotation, how to find the thread id. |
| `.gitignore` | Add `tools/voice-bridge/config.json`. |

Relay rules:
- Listen on `127.0.0.1` and the LAN address only, never `0.0.0.0`.
- Require `Authorization: Bearer <token>`, compare in constant time, 32 random bytes from `crypto.randomBytes(32)`.
- Body limit 4 KB, text limit 2,000 characters, one request at a time.
- `POST /v1/prompt` runs `t3 thread send <threadId> -` (no `--wait`), returns `{"ok":true}` in ~1 s.
- `GET /v1/reply` runs `t3 thread show <threadId> --json --messages 1`, maps `activeRunId == null` to `done`,
  else `running`, and returns `{"state","text","threadTitle"}`.
- Never log the prompt text, the token, or the reply text (same rule as `docs/VOICE.md`).

**Step 2 — phone sends a prompt.**
- New `phone/src/main/java/com/rokidos885/phone/code/T3Bridge.kt`: OkHttp client, suspend `send(text)`,
  `reply()`, plus a `connected()` health check. One class, no Android UI.
- New `phone/src/main/java/com/rokidos885/phone/code/T3PromptSender.kt`: implements the existing
  `ReplySender` interface (`voice/VoiceReplyController.kt`), so the whole listen/review/send state machine
  is reused without change.
- `phone/src/main/java/com/rokidos885/phone/settings/UserSettings.kt`: add `bridgeUrl` (default `""`),
  `bridgeToken` (default `""`), `codeMode` (default `false`).
- `phone/src/main/java/com/rokidos885/phone/session/NavSessionCoordinator.kt`: create a second
  `VoiceReplyController` with `T3PromptSender`, route the new actions to it, and push the `code` state to
  the glasses in the same way it pushes `VoiceState` today (line ~42).

**Step 3 — HUD card.**
- `protocol/src/main/kotlin/com/rokidos885/protocol/NavMessage.kt`: new `CodeState`, `CodePhase`, new actions.
- `protocol/src/test/kotlin/com/rokidos885/protocol/ProtocolCodecTest.kt`: round-trip test for each new message.
- `glasses/src/main/java/com/rokidos885/glasses/state/HudState.kt`: add `code: CodeState?`.
- `glasses/src/main/java/com/rokidos885/glasses/state/HudStore.kt`: apply `CodeState`; add `codeKey()`
  next to `voiceKey()`; branch on `s.code != null` **before** the "empty screen tap starts Grok Bot" branch
  (line 131).
- `glasses/src/main/java/com/rokidos885/glasses/ui/Renderers.kt`: draw the code card with the existing card
  layout (`cardLeft/cardTop/cardW`), header `T3`, up to 4 wrapped lines at 22 px.
- `glasses/src/test/java/com/rokidos885/glasses/state/HudStoreTest.kt`: tap on the card starts dictation;
  double tap cancels; the review rows work.
- Docs to update in the same change: `docs/PROTOCOL.md`, `docs/GESTURES.md`, `docs/VOICE.md`.

**Step 4 — reply on the HUD.** The phone polls `/v1/reply` every 3 s while a prompt is pending, then sends
one `code` message with `phase = REPLY`, `thread` = short title, `text` = the message cut to 200 characters
at a word break. The glasses show it for 15 s.

**Step 5 — accuracy (see 3.5).**

**Step 6 — approvals (see 3.6).**

### 3.3 New protocol messages

```kotlin
@Serializable @SerialName("code")
data class CodeState(
    override val seq: Long,
    val phase: CodePhase,
    val text: String = "",      // words so far, final words, reply, or reason
    val thread: String = "",    // short thread title for the card header
) : NavMessage

@Serializable enum class CodePhase { IDLE, LISTENING, REVIEW, SENDING, REPLY, FAILED }
```

New actions in `ActionType`: `CODE_START`, `CODE_STOP`, `CODE_SEND`, `CODE_REDO`, `CODE_CANCEL`,
and later `CODE_YES`, `CODE_NO`.
Rules from `AGENTS.md` apply: fields with defaults only, no renames, codec test in the same change.
`IDLE` clears the card, exactly like `VoicePhase.IDLE`.

Optional, later: `code_ask` with `{id, question}` for a yes/no approval (see 3.6).

### 3.4 Glasses UX: which gesture

**There is no free gesture today (verified).** `docs/GESTURES.md` and `HudStore.onKey`:
- Empty screen: tap = Grok Bot, swipe either way = music player, double tap = Rokid menu.
- On a trip: tap = view, swipe forward = zoom, swipe back = Grok Bot, double tap = menu.
- Touch and hold belongs to the Rokid assistant, and `docs/GESTURES.md` says the HUD does not use it.

So do **not** add a gesture. Use the pattern the HUD already has: **a card takes all keys while it is up**
(`s.notification != null` wins over the trip and idle branches). The T3 card is such a card:

| State | Tap | Swipe | Double tap |
|---|---|---|---|
| T3 card, idle | start dictation | close the card | close the card |
| LISTENING | stop listening | — | cancel |
| REVIEW | fire the focused row | move the focus | cancel |
| SENDING / REPLY | close | close | close |

This changes nothing else. Grok Bot keeps the empty-screen tap when code mode is off. When code mode is on,
the T3 card is on screen, so it wins. On a trip the card still wins, like a notification card does.

Confirmation before send is the existing CHECK screen: `Dictation.format`, three rows, nothing sent until
the `SEND TO T3` tap (`docs/VOICE.md`, section "The reply flow").

### 3.5 Where speech to text runs

Keep the phone's sherpa-onnx model as the only live decoder for now. Reasons (verified numbers,
`docs/VOICE.md` and `docs/LOCAL_TEST_RESULTS.md`):
- It already runs offline on the phone, 6 wrong words of 46, and shows words about 1 s after you say them.
- The glasses microphone is the limit, not the model: 96% of its energy is under 1 kHz, and consonants are
  faint. A bigger model helps less than a word list does.

Two accuracy levers, in this order:

1. **Word list and normalisation (cheap, testable now).** Build the list from `git ls-files` (166 files) plus
   the Kotlin class names, and store it in `tools/voice-bridge/vocabulary.txt`. On the phone, before REVIEW:
   - join split identifiers: "hud store" → `HudStore`, "voice reply controller" → `VoiceReplyController`;
   - map spoken forms: "dot k t" → `.kt`, "underscore" → `_`, "camel case" → camelCase;
   - replace a token only when the edit distance is 2 or less and the match is unique.
   Score it on the 10 saved recordings in `.tmp/accuracy` plus 15 new coding phrases.

2. **PC re-decode (only if the list is not enough).** Send the same PCM to the relay as a WAV
   (`POST /v1/transcribe`), decode on the RTX 5070. On this PC, sherpa-onnx 2.0.1 already decodes Parakeet
   TDT 0.6B v2 (631 MB) at 6–7 wrong words of 46 in 3.8 s for 9 clips (verified measurement in
   `docs/LOCAL_TEST_RESULTS.md`). Whisper large-v3-turbo is **unverified** on this PC and needs a new
   install (no torch, no ffmpeg today). Whisper's `initial_prompt` could carry the identifier list, but only
   if the chosen build supports it — **unverified**.

Recommendation: build lever 1 first. Add lever 2 only if coding prompts still fail after scoring.

### 3.6 The reply path, and approvals

- **Short text on the HUD, first.** One card, 4 lines, 22 px, about 200 characters. That fits the existing
  card layout (`docs/HUD_DESIGN.md`: 452 px wide, 4–6 lines). The full text stays in T3.
- **Speech, second.** The phone already has `alerts/AndroidAlertSpeaker.kt`, which speaks with
  `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, the same route Google's turn voice uses (glasses speakers when the
  Rokid chain routes Bluetooth audio there). Reuse it with a short prefix, for example "T3 says: …".
  Whether the audio lands on the glasses is `VERIFY M5` in `docs/ARCHITECTURE.md` (**unverified**).
  Speaking TTS from the glasses app itself is **unverified**; the glasses speaker is only confirmed for
  SCO call audio.
- **Approvals.** T3's CLI has no approve command (verified: no such subcommand). `--wait` exit code 2 means
  "waits for an answer", so the relay can detect a question. The MCP tool `t3_pending_request` can answer
  questions but explicitly **cannot** approve a permission request (verified from the tool description).
  Plan:
  1. Run the glasses thread with **full access**, so permission prompts are rare.
  2. For agent questions, show `code_ask` and send the answer as a normal message
     (`t3 thread send <thread> "yes"`), which the agent reads on its next turn.
  3. Do not promise gesture approval of permission dialogs until one is tested. **Unverified.**

## 4. Transport and security

| Path | How it works | Security |
|---|---|---|
| **LAN HTTP to the relay (recommended first)** | Phone → `http://192.168.68.67:8791` | Shared bearer token. Plain HTTP, so anyone on the Wi-Fi who can sniff or spoof can read or replay it. Acceptable at home only. Add one Windows Firewall inbound rule for TCP 8791. |
| **Tailscale (recommended for use outside the house)** | Phone and PC on one tailnet; relay listens on the `100.x` address only | WireGuard encryption, no open port, device-level identity. Needs the Tailscale app on the phone and PC. Not installed today (verified). |
| **T3 Connect / `t3 up --connect`** | cloudflared tunnel to `:3773` | T3 login, plus pairing. Exposes the whole T3 server to the account, not just one prompt endpoint. |
| **`t3 up --lan` and the T3 API directly** | Phone holds a T3 pairing token | A pairing token with no `--scope` gets every permission, including access management (verified docs). Use scoped tokens if you ever do this. |
| **ADB reverse** | `adb reverse tcp:8791 tcp:8791` | No network exposure, but the phone must stay on USB. Not practical while walking. |

Rules for the relay, in short:
- Keep the T3 server on loopback. Do not enable `--lan` for this project.
- The relay is the only exposed surface, and it can only send a prompt to one thread. That is a much
  smaller blast radius than a T3 token.
- Pass the prompt on **stdin** to `t3 thread send <thread> -`. Never build a command string, so prompt text
  can never become a command.
- Rotate the token by editing `config.json` and restarting the relay. The phone stores it in app-private
  settings, never in a log and never in the repo.

## 5. First tests, in order

1. **T3 CLI:** the Step 0 command prints `OK`.
2. **Relay alone:** start the relay, then
   `curl -H "Authorization: Bearer <token>" -d "{\"text\":\"Reply with OK\"}" http://127.0.0.1:8791/v1/prompt`,
   then `GET /v1/reply` after about 10 s. Reply text must match what T3 shows in that thread.
3. **Phone → relay:** a debug button that sends a fixed string, with the glasses not involved.
4. **Speech:** the existing `Voice test (speak for 6 s)` debug path (`docs/VOICE.md`) plus `T3PromptSender`.
5. **HUD:** emulator at 480×398 in demo mode, then the real glasses.
6. **Accuracy:** score 10 saved phrases and 15 coding phrases. Record the result in `docs/LOCAL_TEST_RESULTS.md`.

## 6. Risks and limits

- **The T3 desktop window must stay open.** Windows has no T3 background service (verified docs). Closing the
  last window stops the server, and every relay call then fails.
- **A coding turn takes minutes.** Do not block on it. Send the prompt, show "working", then poll.
- **~0.9 s per CLI call.** Fine for a relay, too slow to poll faster than every 2–3 s.
- **The HUD is a glance, not a screen.** 4 lines maximum. The real answer is on the PC.
- **Speech accuracy for identifiers is the main unknown.** The glasses microphone loses consonants. The word
  list is the cheapest fix. A different model is the expensive one.
- **Gesture budget is full.** Any new gesture needs a change to `docs/GESTURES.md` and a user decision.
- **No verified path to approve a T3 permission dialog from the glasses.** Use full access, or answer in the app.

## 7. What I tried that failed, or that I did not run

- `https://t3.codes/docs` returns 404 (one attempt). I used the T3 docs in the GitHub repo instead.
- Tailscale is not installed, so the tailnet path is documented but not tested here.
- I did not run `t3 thread start --wait` myself: it would start an agent turn and spend tokens. It is the
  first test for Bryan. Two attempts were not needed at any check.
