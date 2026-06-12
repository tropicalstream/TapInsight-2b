#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# test_gemini_tts.sh — Probe Google's Gemini TTS endpoint directly.
#
# Why this exists:
#   Gemini TTS occasionally returns HTTP 500 from the preview model
#   (gemini-3.1-flash-tts-preview) when Google's backend is overloaded
#   or rolling out a deploy.  When the readout on the glasses goes
#   silent, the question is:  is it our app, or is it Google's
#   endpoint?  This script bypasses TapInsight entirely and asks
#   Google directly.
#
# Inputs (all optional):
#   --key       <GEMINI_API_KEY>  default: pulled from the connected
#                                 glasses via `adb`, falling back to
#                                 the GEMINI_API_KEY env var.
#   --model     <model-id>        default: gemini-2.5-flash-tts
#                                 (try gemini-3.1-flash-tts-preview to
#                                 reproduce the 500s you saw in app)
#   --voice     <voice-name>      default: Kore
#   --language  <bcp47>           default: en-US
#   --text      <text>            default: short canned phrase
#   --out       <path.wav>        default: /tmp/gemini_tts_test.wav
#   --play                        play the WAV after a 200 OK
#                                 (uses afplay on macOS, aplay on Linux)
#   -v / --verbose                print full request body + raw response
#
# Examples:
#   tools/test_gemini_tts.sh
#   tools/test_gemini_tts.sh --model gemini-3.1-flash-tts-preview --play
#   tools/test_gemini_tts.sh --key "AIza...ABC" --text "Hello world."
#
# Exit codes:
#   0   200 OK with audio bytes
#   1   non-2xx HTTP response (Gemini error — see printed body)
#   2   bad invocation / missing dependencies / no API key found
#   3   transport/network error (curl -f failed)
# ─────────────────────────────────────────────────────────────────────────────
set -u

# ── Defaults ────────────────────────────────────────────────────────────────
KEY=""
MODEL="gemini-2.5-flash-tts"
VOICE="Kore"
LANGUAGE="en-US"
TEXT="This is a test of the Gemini text-to-speech endpoint. If this completes you should hear two short sentences."
OUT="/tmp/gemini_tts_test.wav"
PLAY=0
VERBOSE=0

usage() {
  sed -n '2,40p' "$0" | sed 's/^# *//'
}

# ── Argparse ────────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --key)       KEY="$2"; shift 2 ;;
    --model)     MODEL="$2"; shift 2 ;;
    --voice)     VOICE="$2"; shift 2 ;;
    --language)  LANGUAGE="$2"; shift 2 ;;
    --text)      TEXT="$2"; shift 2 ;;
    --out)       OUT="$2"; shift 2 ;;
    --play)      PLAY=1; shift ;;
    -v|--verbose) VERBOSE=1; shift ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage >&2; exit 2 ;;
  esac
done

# ── Dependency check ────────────────────────────────────────────────────────
need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing dependency: $1" >&2; exit 2
  }
}
need curl
need python3   # used to base64-decode + WAV-wrap the response

# ── Resolve API key (priority: --key, env, adb pull from glasses) ───────────
if [[ -z "$KEY" ]]; then
  KEY="${GEMINI_API_KEY:-}"
fi
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

# ── Build request body ──────────────────────────────────────────────────────
REQ=$(python3 -c '
import json, sys
text, voice, language, model = sys.argv[1:5]
body = {
    "model": model,
    "contents": [{"parts": [{"text":
        "Read the following text aloud in a natural, clear voice. "
        "Do not add any commentary.\n\n" + text
    }]}],
    "generationConfig": {
        "responseModalities": ["AUDIO"],
        "speechConfig": {
            "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}},
            "languageCode": language
        }
    }
}
print(json.dumps(body))
' "$TEXT" "$VOICE" "$LANGUAGE" "$MODEL")

URL="https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent"

echo "─── Gemini TTS probe ───────────────────────────────────────────────"
echo "  URL      : $URL"
echo "  Model    : $MODEL"
echo "  Voice    : $VOICE   Language : $LANGUAGE"
echo "  Key      : $KEY_PREVIEW"
echo "  Text     : ${TEXT:0:80}$([[ ${#TEXT} -gt 80 ]] && echo '…')"
[[ $VERBOSE -eq 1 ]] && { echo "  Body     :"; printf '%s\n' "$REQ" | python3 -m json.tool 2>/dev/null || printf '%s\n' "$REQ"; }
echo "────────────────────────────────────────────────────────────────────"

# ── POST + capture status separately ───────────────────────────────────────
TMP_BODY=$(mktemp)
trap 'rm -f "$TMP_BODY"' EXIT

T0_NS=$(date +%s%N 2>/dev/null || echo 0)
HTTP_CODE=$(curl -sS \
  -w '%{http_code}' \
  -o "$TMP_BODY" \
  -H "Content-Type: application/json" \
  -H "x-goog-api-key: ${KEY}" \
  --data-binary "$REQ" \
  "$URL" \
) || {
  echo "❌ Network error (curl exit $?)" >&2
  exit 3
}
T1_NS=$(date +%s%N 2>/dev/null || echo 0)
ELAPSED_MS=$(( (T1_NS - T0_NS) / 1000000 ))

echo "  Status   : HTTP $HTTP_CODE"
echo "  Elapsed  : ${ELAPSED_MS} ms"

if [[ "$HTTP_CODE" != 2* ]]; then
  echo "❌ Non-2xx response. Body:"
  echo "────────────────────────────────────────────────────────────────────"
  cat "$TMP_BODY"
  echo
  echo "────────────────────────────────────────────────────────────────────"
  echo "Hints:"
  echo "  • 500 INTERNAL — transient Google-side error. Retry in ~30 s."
  echo "  • 429          — rate limited. Wait and retry."
  echo "  • 401 / 403    — bad / wrong API key, or model not enabled in your project."
  echo "  • 404          — model name doesn't exist or isn't available in your region."
  exit 1
fi

# ── 2xx — extract base64 PCM, wrap as WAV ───────────────────────────────────
if [[ $VERBOSE -eq 1 ]]; then
  echo "  Raw body :"
  cat "$TMP_BODY" | head -c 1200
  echo
fi

python3 -c '
import sys, json, base64, struct, re

with open(sys.argv[1], "r", encoding="utf-8") as f:
    root = json.load(f)
out_path = sys.argv[2]

pcm = bytearray()
mime = "audio/L16;rate=24000"
for cand in root.get("candidates", []):
    for part in (cand.get("content") or {}).get("parts", []):
        inline = part.get("inlineData") or part.get("inline_data")
        if not inline: continue
        data = inline.get("data") or ""
        if not data: continue
        pcm.extend(base64.b64decode(data))
        mime = inline.get("mimeType", inline.get("mime_type", mime))

if not pcm:
    print("❌ Response was 2xx but contained no audio.", file=sys.stderr)
    sys.exit(1)

m = re.search(r"rate\s*=\s*(\d+)", mime, re.IGNORECASE)
sample_rate = int(m.group(1)) if m else 24000
channels, bps = 1, 16
byte_rate = sample_rate * channels * bps // 8
block_align = channels * bps // 8
data_size = len(pcm)
total = 36 + data_size

with open(out_path, "wb") as f:
    f.write(b"RIFF")
    f.write(struct.pack("<I", total))
    f.write(b"WAVE")
    f.write(b"fmt ")
    f.write(struct.pack("<I", 16))
    f.write(struct.pack("<H", 1))
    f.write(struct.pack("<H", channels))
    f.write(struct.pack("<I", sample_rate))
    f.write(struct.pack("<I", byte_rate))
    f.write(struct.pack("<H", block_align))
    f.write(struct.pack("<H", bps))
    f.write(b"data")
    f.write(struct.pack("<I", data_size))
    f.write(pcm)

print(f"✓ Wrote {out_path} ({data_size} PCM bytes, {sample_rate} Hz, "
      f"{data_size / byte_rate:.2f} s of audio)")
' "$TMP_BODY" "$OUT" || exit 1

if [[ $PLAY -eq 1 ]]; then
  if command -v afplay >/dev/null 2>&1; then
    afplay "$OUT"
  elif command -v aplay >/dev/null 2>&1; then
    aplay -q "$OUT"
  elif command -v ffplay >/dev/null 2>&1; then
    ffplay -nodisp -autoexit -loglevel quiet "$OUT"
  else
    echo "(no afplay/aplay/ffplay found — open $OUT manually)"
  fi
fi

exit 0
