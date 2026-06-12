# TapInsight 0.2b (beta)

First public release of the rebuilt TapInsight.

## Highlights

- **Unipanel HUD**: notification bell with roll-down panel (persists across reboots), battery % inside the glyph, opt-in AQI, live agent status ticker ("Hermes working… 15s").
- **Accessibility captions**: two-line CEA-608-style rolling captions in dim mode, mirrored from YouTube CC (timedtext lock-on with DOM fallback) and TapRadio synced lyrics.
- **Dim mode**: tap the eye to black out the display; swipe up for the night-sky breathing visualizer (a clean, caption-free calm screen).
- **Media Library**: browser, player, playlists, batch operations, new-file indicators, folder Play All; TapRadio with live song ID.
- **Voice pipeline hardening**: duplicate-response fix, agent results survive session restarts, auto-reconnect with question replay, bare-hail guard ("Hermes…" + your question always arrive together).
- **Boot splash**: TapInsight security splash with a 50-line curiosity quote library.
- **Optional relay**: `tools/image_relay.py` + your own Cloudflare tunnel for file staging and remote notifications.

## Setup

See the **[Setup Guide](docs/setup-guide.html)**. You will need your own Gemini API key (free tier works); Groq / AudD / Fish Audio keys are optional.

## Known issues (beta)

- Gemini Live occasionally closes the session (code 1008) after long tool calls; the app auto-reconnects and replays your question.
- Run only one TapInsight variant at a time (two installs share LAN ports).
- RayNeo X3 Pro is the only tested device.

## Checksums / artifacts

- `TapInsight-0.2b.apk` — debug-signed beta build (sideload via `adb install`).
