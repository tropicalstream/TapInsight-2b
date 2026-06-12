#!/bin/bash
#
# TapClaw Image Relay — macOS helper installer
#
# Installs the image relay as a background service that auto-starts on login
# on macOS. The same relay can also run on Linux or Windows, but Linux/Windows
# setup uses the manual `image_relay.py` command plus systemd, tmux, screen,
# Startup, or Task Scheduler instead.
#
# Usage:
#   curl -sL <url>/install-relay.sh | bash
#   — or —
#   bash tools/install-relay.sh [--media-root <dir>]... [--glasses-url <url>]... [--glasses-token <token>]
#
# Reinstalls PRESERVE the existing service config: every --media-root entry
# and the glasses notify-bridge (--glasses-url/--glasses-token) already in
# the installed plist are carried over automatically, then merged with any
# flags passed on this command line.
#
# To uninstall:
#   bash tools/install-relay.sh --uninstall

set -e

INSTALL_DIR="$HOME/.tapclaw"
RELAY_SCRIPT="$INSTALL_DIR/image-relay.py"
PLIST_NAME="com.tapinsight.image-relay"
PLIST_PATH="$HOME/Library/LaunchAgents/$PLIST_NAME.plist"
WORKSPACE="${OPENCLAW_WORKSPACE:-$HOME/.openclaw/workspace}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Parse CLI flags ──────────────────────────────────────────────────────
MEDIA_ROOTS=()
GLASSES_URLS=()
GLASSES_TOKEN=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --uninstall)
            echo "Uninstalling TapClaw Image Relay..."
            launchctl unload "$PLIST_PATH" 2>/dev/null || true
            rm -f "$PLIST_PATH"
            rm -rf "$INSTALL_DIR"
            echo "Done. Relay service removed."
            exit 0
            ;;
        --media-root)    MEDIA_ROOTS+=("$2"); shift 2 ;;
        --glasses-url)   GLASSES_URLS+=("$2"); shift 2 ;;
        --glasses-token) GLASSES_TOKEN="$2"; shift 2 ;;
        *) echo "Unknown flag: $1"; exit 1 ;;
    esac
done

# ── Preserve existing service config across reinstalls ──────────────────
# Every --media-root and the glasses notify-bridge survive a reinstall;
# values passed on this command line are merged in (deduped, CLI wins for
# the token).
if [[ -f "$PLIST_PATH" ]]; then
    while IFS=$'\t' read -r kind value; do
        case "$kind" in
            media-root)
                keep=1
                for r in "${MEDIA_ROOTS[@]:-}"; do [[ "$r" == "$value" ]] && keep=0; done
                [[ $keep -eq 1 ]] && MEDIA_ROOTS+=("$value")
                ;;
            glasses-url)
                keep=1
                for u in "${GLASSES_URLS[@]:-}"; do [[ "$u" == "$value" ]] && keep=0; done
                [[ $keep -eq 1 ]] && GLASSES_URLS+=("$value")
                ;;
            glasses-token)
                [[ -z "$GLASSES_TOKEN" ]] && GLASSES_TOKEN="$value"
                ;;
        esac
    done < <(/usr/bin/python3 - "$PLIST_PATH" <<'PYEOF'
import plistlib, sys
try:
    with open(sys.argv[1], "rb") as f:
        args = plistlib.load(f).get("ProgramArguments", [])
except Exception:
    sys.exit(0)
i = 0
while i < len(args):
    flag = args[i]
    if flag in ("--media-root", "--glasses-url", "--glasses-token") and i + 1 < len(args):
        print(flag.lstrip("-") + "\t" + args[i + 1])
        i += 2
    else:
        i += 1
PYEOF
)
fi

# ── Install ──────────────────────────────────────────────────────────────
echo "Installing TapClaw Image Relay..."
echo "  Workspace: $WORKSPACE"

# Ensure directories exist
mkdir -p "$WORKSPACE"
mkdir -p "$INSTALL_DIR"

# Copy relay script
if [[ -f "$SCRIPT_DIR/image_relay.py" ]]; then
    cp "$SCRIPT_DIR/image_relay.py" "$RELAY_SCRIPT"
else
    echo "Error: image_relay.py not found in $SCRIPT_DIR"
    exit 1
fi
chmod +x "$RELAY_SCRIPT"
echo "  Installed: $RELAY_SCRIPT"

PYTHON3="$(which python3 2>/dev/null || echo /usr/bin/python3)"

# Create launchd plist with correct workspace path
mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST_PATH" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$PLIST_NAME</string>
    <key>ProgramArguments</key>
    <array>
        <string>$PYTHON3</string>
        <string>$RELAY_SCRIPT</string>
        <string>--workspace</string>
        <string>$WORKSPACE</string>
$(for r in "${MEDIA_ROOTS[@]:-}"; do
    [[ -n "$r" ]] && printf '        <string>--media-root</string>\n        <string>%s</string>\n' "$r"
done)
$(for u in "${GLASSES_URLS[@]:-}"; do
    [[ -n "$u" ]] && printf '        <string>--glasses-url</string>\n        <string>%s</string>\n' "$u"
done)
$(if [[ -n "$GLASSES_TOKEN" ]]; then
    printf '        <string>--glasses-token</string>\n        <string>%s</string>\n' "$GLASSES_TOKEN"
fi)
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/tapclaw-image-relay.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/tapclaw-image-relay.log</string>
</dict>
</plist>
EOF
echo "  Installed: $PLIST_PATH"

# Stop existing service if running
launchctl unload "$PLIST_PATH" 2>/dev/null || true

# Start the service
launchctl load "$PLIST_PATH"
echo "  Service started"

# Verify
sleep 1
if curl -s "http://localhost:18790/status" > /dev/null 2>&1; then
    echo ""
    echo "✓ TapClaw Image Relay is running on port 18790"
    echo "  Frames will be saved to: $WORKSPACE/camera_frame.jpg"
    for r in "${MEDIA_ROOTS[@]:-}"; do
        [[ -n "$r" ]] && echo "  Media root: $r"
    done
    for u in "${GLASSES_URLS[@]:-}"; do
        [[ -n "$u" ]] && echo "  Notify bridge: -> $u"
    done
    echo "  Logs: /tmp/tapclaw-image-relay.log"
    echo ""
    echo "  The relay starts automatically on login."
    echo "  To uninstall: bash $SCRIPT_DIR/install-relay.sh --uninstall"
else
    echo ""
    echo "⚠ Service started but not responding yet. Check: /tmp/tapclaw-image-relay.log"
fi
