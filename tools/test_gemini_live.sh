#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# test_gemini_live.sh — Probe the Gemini Live model's REST endpoint
# directly. The Live API itself is WebSocket-based, but every Live
# model also exposes the standard generateContent REST endpoint.
# A 200 there means the model is reachable / Google isn't returning
# 5xx for it right now — which is the real question when your Live
# session breaks.
#
# Inputs (all optional):
#   --key       <GEMINI_API_KEY>  default: pulled from the connected
#                                 glasses via `adb`, falling back to
#                                 the GEMINI_API_KEY env var.
#   --model     <model-id>        default: gemini-3.1-flash-live-preview
#                                 (try gemini-2.5-flash-live for the
#                                 stable comparison)
#   --text      <prompt>          default: "Reply with one word: pong."
#   -v / --verbose                print the full JSON body
#
# Examples:
#   tools/test_gemini_live.sh
#   tools/test_gemini_live.sh --model gemini-2.5-flash-live
#   tools/test_gemini_live.sh --model gemini-3.1-flash-live-preview -v
#
# Exit codes:
#   0   200 OK, model reachable
#   1   non-2xx HTTP response (Gemini error — body printed)
#   2   bad invocation / missing dependencies / no API key found
#   3   transport/network error
# ─────────────────────────────────────────────────────────────────────────────
set -u

KEY=""
MODEL="gemini-3.1-flash-live-preview"
TEXT="Reply with exactly one word: pong."
VERBOSE=0

usage() { sed -n '2,30p' "$0" | sed 's/^# *//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --key)        KEY="$2"; shift 2 ;;
    --model)      MODEL="$2"; shift 2 ;;
    --text)       TEXT="$2"; shift 2 ;;
    -v|--verbose) VERBOSE=1; shift ;;
    -h|--help)    usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v curl >/dev/null 2>&1 || { echo "Missing dependency: curl" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "Missing dependency: python3" >&2; exit 2; }

if [[ -z "$KEY" ]]; then KEY="${GEMINI_API_KEY:-}"; fi
if [[ -z "$KEY" ]] && command -v adb >/dev/null 2>&1; then
  ADB_PREFS=$(adb shell run-as com.rayneo.visionclaw cat \
    /data/data/com.rayneo.visionclaw/shared_prefs/visionclaw_prefs.xml 2>/dev/null \
    || true)
  if [[ -n "$ADB_PREFS" ]]; then
    KEY=$(printf '%s\n' "$ADB_PREFS" \
      | python3 -c '
import sys, re
xml = sys.stdin.read()
m = re.search(r"<string name=\"gemini_api_key\">([^<]+)</string>", xml)
print(m.group(1) if m else "")
')
  fi
fi
if [[ -z "$KEY" ]]; then
  echo "No API key. Pass --key, set GEMINI_API_KEY, or connect glasses via adb." >&2
  exit 2
fi
KEY_PREVIEW="${KEY:0:6}…${KEY: -4}"

# Live models don't expose generateContent — they're WebSocket-only
# (bidiGenerateContent). Use the model-metadata endpoint instead,
# which works for every model type and returns supportedGenerationMethods.
URL="https://generativelanguage.googleapis.com/v1beta/models/${MODEL#models/}"

echo "─── Gemini Live model probe (models.get) ─────────────────────────"
echo "  URL    : $URL"
echo "  Model  : $MODEL"
echo "  Key    : $KEY_PREVIEW"
echo "──────────────────────────────────────────────────────────────────"

TMP=$(mktemp); trap 'rm -f "$TMP"' EXIT

T0_NS=$(date +%s%N 2>/dev/null || echo 0)
HTTP_CODE=$(curl -sS \
  -w '%{http_code}' \
  -o "$TMP" \
  -H "x-goog-api-key: ${KEY}" \
  "$URL" \
) || { echo "❌ Network error (curl exit $?)" >&2; exit 3; }
T1_NS=$(date +%s%N 2>/dev/null || echo 0)
ELAPSED_MS=$(( (T1_NS - T0_NS) / 1000000 ))

echo "  Status : HTTP $HTTP_CODE     Elapsed: ${ELAPSED_MS} ms"

if [[ "$HTTP_CODE" != 2* ]]; then
  echo "❌ Non-2xx response. Body:"
  echo "──────────────────────────────────────────────────────────────────"
  cat "$TMP"
  echo
  echo "──────────────────────────────────────────────────────────────────"
  echo "Hints:"
  echo "  • 404       — model name doesn't exist or isn't available in"
  echo "                your region. Try --model gemini-2.5-flash-live"
  echo "                or call ListModels for a current name list."
  echo "  • 401 / 403 — bad / wrong API key, or model not enabled in"
  echo "                your project."
  echo "  • 429       — rate limited. Wait a moment and retry."
  echo "  • 500 / 503 — transient Google-side error. Retry."
  exit 1
fi

LIVE=$(python3 -c '
import sys, json
d = json.load(open(sys.argv[1]))
methods = d.get("supportedGenerationMethods") or []
is_live = any(m.lower() == "bidigeneratecontent" for m in methods)
print("yes" if is_live else "no")
print(d.get("displayName", ""))
print(",".join(methods))
' "$TMP")

IS_LIVE=$(echo "$LIVE" | sed -n 1p)
DISPLAY_NAME=$(echo "$LIVE" | sed -n 2p)
METHODS=$(echo "$LIVE" | sed -n 3p)

echo "  Display: $DISPLAY_NAME"
echo "  Methods: $METHODS"
[[ $VERBOSE -eq 1 ]] && { echo "  Body   :"; cat "$TMP" | python3 -m json.tool 2>/dev/null || cat "$TMP"; }

if [[ "$IS_LIVE" == "yes" ]]; then
  echo "✓ Model is a Live model and reachable."
  exit 0
else
  echo "⚠  Model is reachable but does NOT support bidiGenerateContent —"
  echo "   real-time Live conversations would fail. Pick a different"
  echo "   model (e.g. --model gemini-2.5-flash-live)."
  exit 1
fi
