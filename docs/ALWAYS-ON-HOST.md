# Always-on T3 host (code from the glasses at any time)

The T3 page on the glasses works only while a computer runs T3 Code:
glasses → phone → Tailscale → **T3 Code on the host** → coding agent. This guide makes a spare laptop that
host. It assumes that the laptop runs Windows 11 and has no coding tools. Run the commands in **PowerShell**.
Steps marked **(you)** need a person: a sign-in, a browser page or a choice.

Time: about 1 hour, most of it downloads.

## 0. Check what the laptop has

```powershell
winget --version
git --version; node --version; java -version; gh --version; tailscale version
```

A command that fails shows a tool that is missing. If `winget` fails, install **App Installer** from the
Microsoft Store **(you)**, then open a new PowerShell window.

## 1. Remove get-shit-done (GSD)

This repo has no GSD files. Old GSD installs live in the user folder and add commands, agents and hooks to
Claude Code and Codex. Find them:

```powershell
$roots = "$HOME\.claude", "$HOME\.codex", "$HOME\.config\opencode", "$HOME\.gemini"
Get-ChildItem $roots -Recurse -Depth 3 -Force -ErrorAction SilentlyContinue |
  Where-Object Name -match 'gsd|get-shit-done' | Select-Object FullName
Select-String -Path "$HOME\.claude\settings*.json", "$HOME\.codex\config.toml" -Pattern 'gsd|get-shit-done' -ErrorAction SilentlyContinue
```

If both commands show nothing, go to step 2. If not:

1. Delete what the first command found. These are the usual paths:
   ```powershell
   Remove-Item -Recurse -Force -ErrorAction SilentlyContinue `
     "$HOME\.claude\get-shit-done", "$HOME\.claude\commands\gsd", "$HOME\.claude\gsd-file-manifest.json",
     "$HOME\.claude\gsd-update-check.json", "$HOME\.claude\agents\gsd-*", "$HOME\.claude\hooks\gsd-*",
     "$HOME\.codex\get-shit-done", "$HOME\.codex\skills\gsd-*", "$HOME\.codex\prompts\gsd-*"
   ```
2. Open each file that the second command found. Remove the `statusLine` and `hooks` entries that name a
   `gsd-*` script **(you)**. Keep the other settings.
3. In each cloned project, delete any `.planning\` folder made by GSD.
4. Run the two find commands again. Both must show nothing.

## 2. Install the tools

```powershell
winget install --id Git.Git -e
winget install --id GitHub.cli -e
winget install --id OpenJS.NodeJS.LTS -e
winget install --id Microsoft.OpenJDK.17 -e
winget install --id Tailscale.Tailscale -e
winget install --id Google.AndroidStudio -e
```

Close PowerShell and open a new window, so that the new tools are on `PATH`.

Then install the coding agents that you use:

```powershell
irm https://claude.ai/install.ps1 | iex      # Claude Code
npm install -g @openai/codex                  # Codex (optional)
```

Install **T3 Code** for Windows from its official download (GitHub releases of `pingdotgg/t3code`) **(you)**.

## 3. Sign in **(you)**

```powershell
git config --global user.name "885bryn"
git config --global user.email "<your GitHub email>"
gh auth login          # GitHub.com, HTTPS, log in with a browser
gh auth setup-git
claude                 # sign in, then /exit
codex login            # if you installed Codex
```

Open T3 Code and sign in to the same providers.

## 4. Clone the repo and make it build

```powershell
mkdir $HOME\Documents\Codex -Force; cd $HOME\Documents\Codex
gh repo clone 885bryn/885rokidOS "Rokid Navigation"
cd "Rokid Navigation"
```

The coding agent runs the tests, so the laptop needs the Android build:

1. Open Android Studio once and finish the setup wizard **(you)**. It installs the SDK in
   `%LOCALAPPDATA%\Android\Sdk`. In **SDK Manager**, install Android 14 (API 34) and **Platform-Tools**.
2. Make `local.properties` from the example:
   ```powershell
   Copy-Item local.properties.example local.properties
   ```
   Set `sdk.dir` to the SDK folder (`C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`). Copy `MAPS_API_KEY`
   and `ROUTES_API_KEY` from the `local.properties` file on the old PC **(you)**. Copy the file directly, for
   example with a USB stick. Never paste the keys into a chat and never commit them.
3. Copy the speech libraries, which git does not track. Take the folder
   `phone\src\main\jniLibs\arm64-v8a\` (`libsherpa-onnx-jni.so`, `libonnxruntime.so`) from the old PC, or download
   it as `docs/VOICE.md` says. Without them, the phone build compiles, but the app fails with "Speech engine failed".
4. Run the verify command. Use the short temporary path: Java breaks on paths with spaces.
   ```powershell
   mkdir .tmp -Force | Out-Null
   $t = (New-Object -ComObject Scripting.FileSystemObject).GetFolder("$PWD\.tmp").ShortPath
   $env:TEMP = $t; $env:TMP = $t; $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$t"
   .\gradlew.bat :protocol:test :phone:testDebugUnitTest :glasses:testDebugUnitTest
   ```
   The build must end with `BUILD SUCCESSFUL`.

## 5. Keep the laptop on

```powershell
powercfg /change standby-timeout-ac 0       # never sleep on power
powercfg /change hibernate-timeout-ac 0     # never hibernate on power
powercfg /change monitor-timeout-ac 5       # the screen may turn off
# Closing the lid does nothing while on power:
powercfg /setacvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 0
powercfg /setactive SCHEME_CURRENT
```

Then **(you)**:

1. Keep the laptop on its charger. If the maker's app has a battery charge limit (for example 80 %), turn it on.
2. **Settings → Windows Update → Advanced options → Active hours**: set them to cover the hours that you code.
3. Optional: turn on automatic sign-in (Sysinternals **Autologon**), so that T3 starts again after a restart.
4. Optional: in the BIOS, turn on "Restore on AC power loss" (or "Power on with AC"), if the laptop has it.

## 6. Start T3 Code with Windows

1. In T3 Code settings, turn on start at login **(you)**. If there is no such setting, put a shortcut to T3 Code in
   the Startup folder:
   ```powershell
   explorer shell:startup
   ```
2. In T3 Code, add the `Rokid Navigation` folder as a project. Make the thread that the glasses will use **(you)**.
3. Find the port of the T3 server. On the old PC it is `3773`. Check that it answers:
   ```powershell
   curl.exe -s -o NUL -w "%{http_code}`n" http://127.0.0.1:3773/.well-known/oauth-authorization-server
   ```
   The result must be `200`. If it is not, look in T3 Code settings for the server port and use that port below.

## 7. Put T3 on the tailnet

```powershell
tailscale up --unattended        # sign in with the same Tailscale account (you)
tailscale serve --bg 3773        # tailnet only; not open to the internet
tailscale serve status           # shows https://<laptop-name>.<tailnet>.ts.net
```

`--unattended` keeps Tailscale connected when nobody is signed in to Windows.

Then **(you)**, in the [Tailscale admin console](https://login.tailscale.com/admin/machines):

1. Open the laptop's `…` menu and select **Disable key expiry**. If you skip this, the laptop leaves the tailnet
   after some months.
2. Optional: rename the machine to a short name, for example `t3-host`. The address changes with the name.

From the phone (Tailscale on), open `https://<laptop-name>.<tailnet>.ts.net` in a browser. T3 must answer.

## 8. Point the phone at the laptop **(you)**

1. On the phone, open the 885rokidOS app, then **Settings**.
2. Put the laptop's address (`https://<laptop-name>.<tailnet>.ts.net`) in the T3 address field.
3. Select **T3: sign in** and accept in the browser. A sign-in on the old PC does not work on the laptop. The
   sign-in lasts 30 days.
4. Select **T3 thread** and pick the thread from step 6.
5. On the glasses: swipe back from home, tap, speak, then select **Send**. The phone reads the reply aloud.

## 9. Turn off the old PC's T3 access

On the old PC:

```powershell
& "C:\Program Files\Tailscale\tailscale.exe" serve --https=443 off
```

Also turn off T3's local network access on the old PC, if it is on.

## Check list

| Check | How |
|---|---|
| No GSD files | Step 1 find commands show nothing |
| Tests pass | Step 4 ends with `BUILD SUCCESSFUL` |
| T3 answers locally | Step 6 curl shows `200` |
| T3 answers on the tailnet | Phone browser opens the `ts.net` address |
| Laptop survives a restart | Restart it; T3 and Tailscale come back without you |
| Glasses work | Step 8, item 5 |

## When something fails

- **"T3 not reachable" on the glasses:** Tailscale is off on the phone or the laptop, or the laptop sleeps. Check
  `tailscale status` on the laptop: the phone must not show `offline`. On the phone, set Tailscale as
  **Always-on VPN** and set its battery use to **Unrestricted**.
- **"Sign in to T3 again in phone Settings":** the 30-day sign-in ended, or you changed the address. Do step 8 again.
- **"Answer T3 on the PC":** T3 waits for an approval or an answer. Open the `ts.net` address in any browser on the
  tailnet and answer there.
- **The build fails at once with "Could not create the Java Virtual Machine":** the temporary path has a space.
  Use the short path from step 4.
