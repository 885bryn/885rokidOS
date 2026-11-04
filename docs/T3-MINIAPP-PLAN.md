# T3 miniapp: code by voice from the glasses (plan, 2026-11-04)

Background research: `docs/VOICE-CODING-T3.md`. Where it disagrees with this plan, this plan wins.

## Goal

You speak on the glasses. Your words go to one T3 Code thread on the PC. The glasses show the thread, the model, your
words, and a short reply. Grok Bot is not used.

## Decisions (made by the user)

- **Carousel, no trip only.** Home is in the middle. Swipe forward: the music player (as now). Swipe back: the T3 page.
  From either side page, the swipe toward home goes back home. On the music page, the other swipe stays next track.
  On a trip, gestures do not change.
- **Wake.** A tap wakes the glasses on the last page. On the T3 page the waking tap only wakes: the page lights up
  bright ("READY · TAP to speak") so you know the next tap starts dictation.
- **T3 page shows:** thread title, model, your words after you finish speaking, then the state and the reply.
- **Grok Bot** keeps the tap on the empty home page.
- **Connection: Tailscale.** The phone reaches T3 on the PC over Tailscale, at home and away. Nothing opens to the
  internet. T3 Connect is not used by 885rokidOS.

## Verified facts (2026-11-04, this PC, T3 server 0.1.6)

- T3 has OAuth for outside clients: `/.well-known/oauth-authorization-server`, `authorization_code` + PKCE S256,
  `/oauth/mcp/register` (public client, `token_endpoint_auth_method: none`), `/oauth/mcp/authorize` → T3 page
  `/connect-agent` (the user approves), `/oauth/mcp/token`. Scopes: `orchestration:read`, `orchestration:operate`.
- The protected resource is `<origin>/mcp` (bearer header). The `t3` CLI uses this endpoint for `thread send/show`.
- Register and authorize both answer over the LAN address too (HTTP 201, then 302 to `/connect-agent`).
- `t3 pair --tailscale` and `t3 serve --tailscale-serve` exist (Tailscale Serve HTTPS).
- Not verified: the token step, the token lifetime (no refresh grant is listed), the `/mcp` tool names, and sign-in
  over Tailscale HTTPS.

## Build, in this order

**Step 1 — spike, no app code.** A script in `.tmp/` (not committed): register, authorize (the user clicks Approve
in T3), exchange the code, then over `/mcp`: `tools/list`, list threads, read one thread (title, model, last
messages). First on `127.0.0.1`, then on the Tailscale HTTPS address. Record the tool names, the token lifetime and
the redirect URIs that T3 accepts. **Send nothing to an agent in the spike.**

**Step 2 — phone: T3 client.** `phone/.../code/T3Client.kt`: sign-in (PKCE, browser + redirect back to the app),
token in app-private storage, `listThreads()`, `readThread(id)`, `send(id, text)`. All network calls off the main
thread. Never log the token, prompt or reply text. Phone Settings: "T3: sign in" and "T3 thread" (pick from
`listThreads()`). One thread at a time; no thread switching on the glasses.

**Step 3 — phone: dictation to T3.** Reuse `VoiceReplyController` with a new card id `T3_CARD_ID`. Its send step
must call `T3Client.send` off the main thread (today `ReplySender.reply` is synchronous and runs on the action
path). The REVIEW step (Send / Redo / Cancel) stays mandatory. After a send, poll `readThread` every 3 s until the
run ends, at most 10 min.

**Step 4 — protocol + glasses.** One new message `T3Status(seq, thread, model, state, text)` (`state`: READY,
SENT, WORKING, REPLY, FAILED, NEEDS_PC). Dictation uses the existing `VoiceState` and `VOICE_*` actions with
`T3_CARD_ID`. Glasses: the carousel page state in `HudStore` (kept across sleep), the T3 page in `Renderers`
(reply cut to 4 lines), the wake rule. Codec test, `HudStoreTest` cases for the carousel and the wake rule.
Update `docs/PROTOCOL.md` and `docs/GESTURES.md`.

**Out of scope:** TTS, approving T3 permission prompts from the glasses (show NEEDS_PC instead), several threads,
a PC relay, T3 Connect.

## Stop rules

Stop, write `HANDOFF.md` (3 lines: task, failing output, what was tried), and report, if any of these happens:
- the spike cannot get a token, or `/mcp` has no tool to send to or read a thread;
- the token lasts less than a day and cannot be renewed without the user at the PC;
- T3 rejects a redirect the phone can receive (custom scheme or loopback);
- sign-in does not work over Tailscale HTTPS;
- any step needs a change to T3 itself;
- the verify command fails after 2 fix attempts.

## User steps

1. Install Tailscale on the PC and on the Nothing phone, sign in with the same account.
2. Click Approve in T3 when the spike and the phone sign-in ask for it.
3. After it works over Tailscale: turn T3's local network access off.

Verify command: `./gradlew :protocol:test :phone:testDebugUnitTest :glasses:testDebugUnitTest assembleDebug`.
