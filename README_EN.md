# ZCodeRemote

English | [简体中文](README.md)

![Version](https://img.shields.io/badge/version-0.0.2-00E676?style=flat-square)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android-lightgrey?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-00E676?style=flat-square)

> In one sentence: turn the ZCode desktop "phone remote-control" web page into a **proper mobile app**.

Scan the remote-control QR code from desktop ZCode and you can keep driving your AI coding agent from your phone — but it's originally just a web page, and opening it in a mobile browser is a poor experience. ZCodeRemote ships an injection-based enhancement layer that makes it feel like a real app.

> ⚠️ This is an **unofficial** project, not affiliated with Z.ai / ZCode. For personal use and learning only. ZCode and its logo are trademarks of Zhipu AI; logo assets are sourced from zcode.z.ai.

---

## Why you need it

When you open the ZCode remote-control link directly in a mobile browser:

- 😫 The input box gets crushed by the keyboard; long messages scroll out of view mid-typing
- 📴 The moment you switch apps, the connection drops and you have to reconnect
- 🔇 New replies trigger no notification at all — you keep switching back to check
- 🧭 Jumping to an earlier question means endless scrolling
- 🤚 It's just a web page: no gestures, no floating controls

ZCodeRemote solves all of this: **floating input** (keyboard-proof), **background keep-alive** (no disconnect), **reply notifications** (system-level), **in-chat navigation** (jump between questions), **gesture control** (one-thumb operation via the floating logo).

---

## Highlights

This is not a simple "WebView wrapper." It includes a ~**2000-line injection-based enhancement layer**, injected via `evaluateJavascript` after page load and recursively broadcast to all same-origin iframes and Shadow DOM, transforming the web page into a native-grade experience:

- **🧩 Injection layer** — 8 modular JS bundles (`dev/bundle/`: core / style / fix / theme / nav / composer / settings / boot), covering mobile layout fixes, frosted-glass design tokens, floating input, question navigation, reply monitoring, diagnostics
- **🌉 Native bridge `zcodeBridge`** — JS calls native directly via `window.zcodeBridge`: reply push, keep-alive notification preview, theme sampling, floating-logo position persistence, diagnostic tracing
- **_alive Keep-alive** — "lies" to the page about visibility (`document.hidden` always false) so the WebSocket stays alive when you leave the app; paired with a foreground service + system notifications so replies still arrive
- **🎨 Theme follow** — samples header/footer colors in-place; system bars track the page's light/dark theme in real time for a fully immersive look
- **🛡️ Security-first** — bearer-link credential model, no data exfiltration, SSL errors always rejected (see [Security](#security) and [SECURITY.md](SECURITY.md))
- **🔁 Reply dedup** — FNV-1a 64-bit fingerprint + 7-day window, so historical messages aren't re-notified after a reconnect

---

## Features

### Entry

- **Scan to enter**: scan the QR code popped by the "phone icon" at the bottom-left of desktop ZCode
- **Paste link**: paste a `https://zcode.z.ai/remote/...` link manually; auto-prefilled if your clipboard holds one at launch
- **Recent sessions**: remembers the last 10 remote-control links; tap to enter, long-press to delete
- **Auto-resume**: cold-start returns to your last session; press Back to go back to the entry page and switch

### Experience

- **Floating input**: the input box stays collapsed by default (no space taken); expand to full-screen via the floating button; collapse with the arc button at the top-right
- **In-chat navigation**: pull-down to summon the question list; hover-drag to preview; tap an entry to jump
- **Reply notifications**: get a system notification on new replies when you've left the app (visible on lock screen, with preview)
- **Background keep-alive**: the connection persists after you leave the app; replies still arrive
- **Prompt dialogs**: ask / confirm / plan-consultation dialogs in the page render as bottom cards and auto-close on selection
- **Dark theme**: black-gray palette throughout, consistent with system dark mode

---

## Gestures

| Gesture | Action |
|---|---|
| Tap logo | Show / hide floating input |
| Double-tap logo | Open settings |
| Long-press & drag logo | Reposition (auto-saved) |
| Pull-down on logo | Show / hide question navigation |
| Swipe up on logo | Jump to bottom |
| Left-edge swipe up ×3 + right-edge swipe down ×3 | Show diagnostics card (copies trace) |

---

## Download & install

### Option 1: GitHub Releases (recommended)

Go to the [Releases page](https://github.com/Enc-hanted/zcode-remote/releases), download the latest `ZCodeRemote-vX.X.X.apk`, and install it on your phone.

> Android will warn about "unknown sources" — this is the standard prompt for non-Play-Store apps; allow it. The APK is built on GitHub Actions in CI and signed with a public-default debug keystore, so it can be upgraded in place.

### Option 2: Build from source

```bash
git clone https://github.com/Enc-hanted/zcode-remote.git
cd zcode-remote
# Rebuild the embedded bundle and validate (same as CI)
python3 _patch_web2.py
python3 _validate.py
gradle assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and Gradle 8.9. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full dev workflow.

---

## Architecture

### Stack

Kotlin + AppCompat + Material Components + WebView; QR scanning via [zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded). Only 5 runtime dependencies — minimal.

- `minSdk 26` (Android 8.0+) · `targetSdk 35` · `versionName 0.0.2`

### Project layout

| Layer | Description |
|---|---|
| **App layer** | `EntryActivity` (scan / paste / recent), `WebActivity` (WebView + injection, the core), `SettingsActivity`, `KeepAliveService` (foreground keep-alive), `RecentStore` / `SettingsStore` (SharedPreferences + JSON) |
| **Injection layer** | 8 modular JS files under `dev/bundle/` (01_core ~ 08_boot), split by `_split_bundle.py` for maintenance and stitched into `_bundle.js` by `_patch_web2.py`, then embedded as the `BUNDLE_JS` constant in `WebActivity.kt` |
| **Native bridge** | `webView.addJavascriptInterface(AppBridge, "zcodeBridge")`; JS calls native via `window.zcodeBridge`: `onReply` / `onProgress` / `onTheme` / `onBars` / `openSettings` / `saveLogoPos` / `getLogoPos` / `getTrace` |

### Key mechanisms

- **Injection timing**: triggered on `onPageFinished`, recursively broadcast to same-origin iframes and Shadow DOM
- **Keep-alive**: intercepts `visibilitychange` so the page "thinks" it's always visible; the WebSocket stays alive; a foreground service keeps the process alive and stops on schedule
- **Reply dedup**: `ReplyDedupe.kt` — FNV-1a 64-bit fingerprint + 7-day window
- **Diagnostics**: traces collected via injection-layer probes + native `getTrace`, surfaced on the diagnostics card

---

## Security

This is the design center of the project. The remote-control link itself is a **credential (bearer-link)** — whoever has it has full control of your desktop ZCode. Therefore:

- 🔒 **No built-in links**: the app contains no credentials and talks to no backend
- 🔒 **No data upload**: links are stored only on your phone (SharedPreferences)
- 🔒 **Domain isolation**: `z.ai` links open inside the WebView; everything else is sent to the system browser; non-http(s) schemes are blocked
- 🔒 **Strict SSL**: certificate errors always call `handler.cancel()`, rejecting access — no self-signed certs accepted
- 🔒 **Link confirmation**: non-remote-control links prompt for confirmation first

**Do not share this app or its stored links with anyone** — that's equivalent to sharing your desktop control privileges. See [SECURITY.md](SECURITY.md) for details.

---

## FAQ

**Q: Does this conflict with an official ZCode app?**
A: This is an independent, unofficial client, not affiliated with Zhipu. It only wraps the official remote-control web page; it does not modify or replace any official product.

**Q: Is my remote-control link collected?**
A: No. The app has no backend. Links live only on your phone. Network requests go only to `zcode.z.ai` (the page itself) and the camera (for QR scanning).

**Q: Why a debug build?**
A: This project is signed with a public-default debug keystore for sideloading and learning only. Don't use it for formal distribution.

---

## Screenshots

<!-- Screenshots TBD: planning entry page, floating input, in-chat navigation, dark theme, etc.
     Note: screenshots must be sanitized (remove real session content and remote-control links). -->

---

## Roadmap

- [ ] Real-device screenshots / demo GIFs (sanitized)
- [ ] Reply-notification UX (grouping, do-not-disturb windows)
- [ ] Parallel multi-session switching
- [ ] Internationalization (i18n)

---

## Open source

- **License**: MIT (see [LICENSE](LICENSE))
- **Debug builds** use a public-default keystore (`android` password) for sideloading only; do not use for release
- **Brand**: ZCode and its logo are trademarks of Zhipu AI; this repo contains no undisclosed Zhipu APIs or credentials
- **Contributing**: Issues and PRs welcome — see [CONTRIBUTING.md](CONTRIBUTING.md)
- **Changelog**: see [CHANGELOG.md](CHANGELOG.md)
- **Security issues**: do not open a public Issue — see [SECURITY.md](SECURITY.md)
