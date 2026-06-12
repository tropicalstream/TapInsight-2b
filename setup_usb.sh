#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
# TapInsight USB Companion Setup
# ──────────────────────────────────────────────────────────────────
# This script sets up ADB port forwarding so you can access the
# TapInsight companion app at http://localhost:19110 over USB.
#
# Usage:
#   ./setup_usb.sh          — one-shot: forward port and exit
#   ./setup_usb.sh --watch  — persistent: re-forward on reconnect
#
# The --watch mode keeps running and automatically re-establishes
# the port forwarding whenever the glasses reconnect (USB unplug/
# replug, reboot, ADB restart, etc).
# ──────────────────────────────────────────────────────────────────

PORT=19110
POLL_INTERVAL=3  # seconds between checks in watch mode

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

forward_port() {
    adb forward tcp:$PORT tcp:$PORT 2>/dev/null
    return $?
}

check_device() {
    adb devices 2>/dev/null | grep -q "device$"
    return $?
}

check_forward() {
    adb forward --list 2>/dev/null | grep -q "tcp:$PORT"
    return $?
}

echo -e "${CYAN}╔═══════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   TapInsight USB Companion Setup      ║${NC}"
echo -e "${CYAN}╚═══════════════════════════════════════╝${NC}"
echo ""

# Check ADB is available
if ! command -v adb &>/dev/null; then
    echo -e "${RED}Error: adb not found in PATH.${NC}"
    echo "Install Android SDK Platform Tools:"
    echo "  macOS:   brew install android-platform-tools"
    echo "  Linux:   sudo apt install adb"
    echo "  Windows: Download from https://developer.android.com/tools/releases/platform-tools"
    exit 1
fi

# Check device connected
if ! check_device; then
    echo -e "${YELLOW}No device detected. Make sure your RayNeo X3 Pro is:${NC}"
    echo "  1. Connected via USB"
    echo "  2. USB debugging is enabled"
    echo "  3. You've authorized the USB debugging prompt on the glasses"
    echo ""
    echo "Waiting for device..."
    adb wait-for-device
    echo -e "${GREEN}Device connected!${NC}"
fi

# Forward port
if forward_port; then
    echo -e "${GREEN}Port forwarding established:${NC}"
    echo -e "  localhost:$PORT → glasses:$PORT"
    echo ""
    echo -e "${CYAN}Open in your browser:${NC}"
    echo -e "  ${GREEN}http://localhost:$PORT${NC}"
    echo ""
else
    echo -e "${RED}Failed to set up port forwarding.${NC}"
    echo "Try: adb kill-server && adb start-server"
    exit 1
fi

# Watch mode
if [ "$1" = "--watch" ] || [ "$1" = "-w" ]; then
    echo -e "${YELLOW}Watch mode active — will re-forward on reconnect.${NC}"
    echo "Press Ctrl+C to stop."
    echo ""

    was_connected=true
    while true; do
        sleep $POLL_INTERVAL

        if check_device; then
            if ! check_forward; then
                # Device connected but forward is gone — re-establish
                if forward_port; then
                    echo -e "$(date '+%H:%M:%S') ${GREEN}Re-established port forwarding${NC}"
                else
                    echo -e "$(date '+%H:%M:%S') ${RED}Failed to re-forward${NC}"
                fi
            fi
            if [ "$was_connected" = false ]; then
                echo -e "$(date '+%H:%M:%S') ${GREEN}Device reconnected${NC}"
                forward_port
                was_connected=true
            fi
        else
            if [ "$was_connected" = true ]; then
                echo -e "$(date '+%H:%M:%S') ${YELLOW}Device disconnected, waiting...${NC}"
                was_connected=false
            fi
        fi
    done
else
    echo -e "Tip: Run with ${CYAN}--watch${NC} to auto-reconnect:"
    echo -e "  ${CYAN}./setup_usb.sh --watch${NC}"
fi
