# TapInsight

**An open-source AI companion for RayNeo X3 Pro AR glasses.**
Voice assistant, heads-up display, media library, accessibility captions, and optional AI-agent integration — all running on hardware you own, with keys you control.

> **Beta 0.2b** — early public release. Expect rough edges; please file issues.

## What it does

- **Voice assistant (Gemini Live)** — talk naturally; the assistant hears, sees (camera on demand), and answers through the glasses' speakers with a live HUD transcript.
- **Unipanel HUD** — clock, battery, weather, calendar, tasks, news headlines, notification bell, and a live status ticker, floating in your field of view.
- **Dim mode** — one tap blacks out the display for distraction-free listening; keeps now-playing info and **live closed captions** (YouTube CC mirroring and radio lyrics) — built for accessibility first.
- **Breathing visualizer** — a calm night-sky audio visualizer with a breathing guide, one swipe away in dim mode.
- **Media Library** — music, video, photos, playlists and text files on the glasses, with a browser UI, a media player, and TapRadio internet radio with live song ID + synced lyrics.
- **Media relay (optional)** — a small Python relay on your computer, published through your own Cloudflare tunnel, lets agents stage files for the glasses and delivers notifications from anywhere.
- **AI agents (optional)** — route requests to your own Hermes / OpenClaw-style agent sessions ("Hermes, what's the news?") with spoken readouts and HUD notifications.

## Quick start

1. **Read the [Setup Guide](docs/setup-guide.html)** — a step-by-step walkthrough from unboxing to first conversation, including API keys, the companion app, and the optional relay/tunnel.
2. Or the short version:
   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Open the **companion web app** (the glasses serve it on your LAN — address shown in the app) and paste in your own API keys: Gemini (required for voice), and optionally Groq, AudD, Fish Audio.

**You bring your own keys.** Nothing in this repo phones home; every external service is one you configure yourself.

## Building

- Android Studio (or plain Gradle) with JDK 17, compileSdk 35, minSdk 29.
- Optional build property for the media relay default — in `~/.gradle/gradle.properties`:
  ```properties
  TAPINSIGHT_RELAY_BASE=https://relay.your-domain.com
  ```
  Leave it unset and the app derives relay addresses from your configured agent endpoint at runtime.

## Repository layout

| Path | What it is |
|---|---|
| `app/` | Voice service, Gemini Live pipeline, companion server + web app, tools |
| `tapbrowser/` | Browser/HUD module: unipanel overlay, dual WebView, media library, players |
| `tools/image_relay.py` | Optional Mac/PC media + notification relay (pairs with a Cloudflare tunnel) |
| `docs/` | Project site, user guide, **setup guide** |

## Documentation

- **[Setup Guide](docs/setup-guide.html)** — start here (step-by-step, with examples)
- **[User Guide](docs/user-guide.html)** — gestures, voice commands, features
- **[Release notes](GITHUB_RELEASE_0.2b.md)**

## Hardware

Built for and tested on **RayNeo X3 Pro**. Other Android-based glasses may work but are untested.

## License

[GPL-3.0](LICENSE). Forks and derivatives must remain open source under the same license.

## Privacy

TapInsight has no backend. Audio goes to the AI provider you configure (e.g., Google Gemini Live) under your own API key. The optional relay runs on your own machine behind your own domain. Camera, microphone, and notification access are used only for the features described above.
