#!/usr/bin/env bash
# Off-device range test. For each case: resolve with the app's core code, then cut ONLY the range
# from the remote URLs with ffmpeg input seeking (-ss/-to before -i => HTTP Range requests),
# re-encoding to H.264 + AAC like the app, and report ffprobe output + bytes fetched.
set -u
cd "$(dirname "$0")"
OUT=${OUT:-out}; mkdir -p "$OUT"
HARNESS=build/install/postclip-range-harness/bin/postclip-range-harness
CASES=(
  "short_60-75|giDLxZzkdXc|60|75"
  "long_1h05m10-1h05m40|XuYm5c0Xfs4|3910|3940"
  "long_0-30|XuYm5c0Xfs4|0|30"
  "long_10min_1h05m10-1h15m10|XuYm5c0Xfs4|3910|4510"
  "long_5h00m00-5h05m00|XuYm5c0Xfs4|18000|18300"
)
python3 count_proxy.py 18999 "$OUT/proxy_bytes" & PROXY=$!
trap 'kill $PROXY 2>/dev/null' EXIT
sleep 1
declare -A RESOLVED
for c in "${CASES[@]}"; do
  IFS='|' read -r name id s e <<<"$c"
  echo "=== $name ($id $s-$e) ==="
  if [ -z "${RESOLVED[$id]:-}" ]; then
    if ! "$HARNESS" "$id" 720 > "$OUT/resolve_$id.json" 2> "$OUT/resolve_$id.err"; then
      echo "RESOLVE FAILED:"; head -3 "$OUT/resolve_$id.err"; continue
    fi
    RESOLVED[$id]=1
  fi
  python3 - "$OUT/resolve_$id.json" <<'PY'
import json,sys;d=json.load(open(sys.argv[1]))
print("title:",d["title"],"| duration:",d["duration"],"| type:",d["streamType"],"| heights:",d["heights"],"| progressive:",d["progressive"],"| fullBytes:",d["fullBytes"])
for k in ("video","audio"):
  m=d[k]
  if m: print(k,":",m["width"],"x",m["height"],"bitrate",m["bitrate"],"contentLength",m["contentLength"],"approxDurationMs",m["approxDurationMs"],"codec",m["codec"])
PY
  V=$(python3 -c "import json;print(json.load(open('$OUT/resolve_$id.json'))['video']['url'])")
  A=$(python3 -c "import json;a=json.load(open('$OUT/resolve_$id.json'))['audio'];print(a['url'] if a else '')")
  EST=$(python3 -c "import json;d=json.load(open('$OUT/resolve_$id.json'));print(round(d['fullBytes']*($e-$s)/max(d['duration'],1)))")
  before=$(cat "$OUT/proxy_bytes" 2>/dev/null || echo 0)
  t0=$(date +%s)
  UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
  if [ -n "$A" ]; then
    http_proxy=http://127.0.0.1:18999 https_proxy=http://127.0.0.1:18999 ffmpeg -hide_banner -loglevel error -y \
      -user_agent "$UA" -ss "$s" -to "$e" -i "$V" -user_agent "$UA" -ss "$s" -to "$e" -i "$A" \
      -map 0:v:0 -map 1:a:0 -c:v libx264 -preset veryfast -crf 23 -c:a aac -b:a 128k -movflags +faststart "$OUT/$name.mp4"
  else
    http_proxy=http://127.0.0.1:18999 https_proxy=http://127.0.0.1:18999 ffmpeg -hide_banner -loglevel error -y \
      -user_agent "$UA" -ss "$s" -to "$e" -i "$V" -c:v libx264 -preset veryfast -crf 23 -c:a aac "$OUT/$name.mp4"
  fi
  rc=$?; sleep 1
  after=$(cat "$OUT/proxy_bytes" 2>/dev/null || echo 0)
  echo "ffmpeg exit: $rc  wall: $(( $(date +%s) - t0 ))s"
  echo "bytes fetched: $(( after - before ))  (app estimate: $EST)"
  if [ -f "$OUT/$name.mp4" ]; then
    ffprobe -v error -show_entries format=duration,size:stream=codec_name,width,height,duration -of default=nw=1 "$OUT/$name.mp4"
  fi
done
