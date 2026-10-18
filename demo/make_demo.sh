#!/usr/bin/env bash
# 演示视频生成:pip3 install edge-tts + pkg install ffmpeg 后运行
# 流程:script.json → 逐句 TTS(edge-tts)→ 章节音轨拼接 → 幻灯片 → 章节片段 → 合片 → 烧字幕 → GIF 预览
# 产物:demo/seclab-demo.mp4(带语音+字幕)/ demo/subtitles.srt / demo/preview.gif
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
VOICE=$(python3 -c "import json;print(json.load(open('$HERE/script.json'))['voice'])")
RATE=$(python3 -c "import json;print(json.load(open('$HERE/script.json'))['rate'])")
FONT=/system/fonts/NotoSansCJK-Regular.ttc
GAP=0.35
AUD="$HERE/audio"; FRM="$HERE/frames"; SEG="$HERE/segments"
rm -rf "$AUD" "$SEG"; mkdir -p "$AUD" "$SEG"
[ -d "$FRM" ] || python3 "$HERE/make_slides.py"

# 1) 逐句 TTS
echo "== TTS 合成 =="
python3 - "$HERE" <<'PY'
import json, os, subprocess, sys
here = sys.argv[1]
script = json.load(open(os.path.join(here, "script.json"), encoding="utf-8"))
n = 0
for ch in script["chapters"]:
    for c in ch["cues"]:
        out = os.path.join(here, "audio", f"{n:03d}.mp3")
        subprocess.run(["edge-tts", "--voice", script["voice"], "--rate", script["rate"],
                        "--text", c, "--write-media", out], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        n += 1
print(f"cues={n}")
PY

# 2) 静音间隔 + 章节音轨 + srt
echo "== 拼接音轨/生成字幕 =="
ffmpeg -hide_banner -loglevel error -f lavfi -i anullsrc=r=24000:cl=mono -t "$GAP" \
  -c:a libmp3lame -b:a 48k -ar 24000 -ac 1 "$AUD/sil.mp3" -y
python3 - "$HERE" "$GAP" <<'PY'
import json, os, subprocess, sys
here, gap = sys.argv[1], float(sys.argv[2])
script = json.load(open(os.path.join(here, "script.json"), encoding="utf-8"))

def dur(p):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                        "-of", "csv=p=0", p], capture_output=True, text=True, check=True)
    return float(r.stdout.strip())

cue_idx, srt, t = 0, [], 0.0
last_ch = len(script["chapters"]) - 1
for ci, ch in enumerate(script["chapters"]):
    lst = os.path.join(here, "segments", f"{ch['id']}.txt")
    cue_times = []
    with open(lst, "w") as f:
        for j, c in enumerate(ch["cues"]):
            p = os.path.join(here, "audio", f"{cue_idx:03d}.mp3")
            d = dur(p)
            f.write(f"file '{p}'\n")
            if not (ci == last_ch and j == len(ch["cues"]) - 1):
                f.write(f"file '{here}/audio/sil.mp3'\n")
            cue_times.append((t, t + d, c))
            t += d + gap
            cue_idx += 1
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "concat", "-safe", "0",
                    "-i", lst, "-c", "copy", os.path.join(here, "segments", ch['id'] + ".mp3"), "-y"], check=True)
    for s, e, txt in cue_times:
        def fmt(x):
            h, r = divmod(x, 3600); m, s2 = divmod(r, 60)
            return f"{int(h):02d}:{int(m):02d}:{s2:06.3f}".replace(".", ",")
        srt.append(f"{len(srt) + 1}\n{fmt(s)} --> {fmt(e)}\n{txt}\n")
open(os.path.join(here, "subtitles.srt"), "w", encoding="utf-8").write("\n".join(srt))
print(f"total={t:.1f}s cues={cue_idx}")
PY

# 3) 章节片段(静态图 + 章节音轨)
echo "== 编码章节片段 =="
: > "$SEG/list.txt"
for ch in $(python3 -c "import json;print(' '.join(c['id'] for c in json.load(open('$HERE/script.json'))['chapters']))"); do
  ffmpeg -hide_banner -loglevel error -loop 1 -framerate 30 -i "$FRM/$ch.png" -i "$SEG/$ch.mp3" \
    -c:v libx264 -tune stillimage -pix_fmt yuv420p -r 30 -c:a aac -b:a 128k -shortest \
    -movflags +faststart "$SEG/$ch.mp4" -y
  echo "file '$SEG/$ch.mp4'" >> "$SEG/list.txt"
done

# 4) 合片 + 烧字幕
echo "== 合片并烧字幕 =="
ffmpeg -hide_banner -loglevel error -f concat -safe 0 -i "$SEG/list.txt" -c copy "$SEG/full.mp4" -y
STYLE="FontName=Noto Sans CJK SC,FontSize=17,PrimaryColour=&H00F7EDE6,OutlineColour=&H80000000,BorderStyle=3,Outline=1,Shadow=0,MarginV=42"
ffmpeg -hide_banner -loglevel warning -i "$SEG/full.mp4" \
  -vf "subtitles=filename=$HERE/subtitles.srt:fontsdir=/system/fonts:force_style='$STYLE'" \
  -c:v libx264 -crf 21 -preset veryfast -pix_fmt yuv420p -c:a copy -movflags +faststart \
  "$HERE/seclab-demo.mp4" -y 2> "$SEG/burn.log"
grep -i "fontselect.*failed\|library not found" "$SEG/burn.log" | head -5 || true

# 5) GIF 预览(片头 9 秒)
echo "== 生成 GIF 预览 =="
ffmpeg -hide_banner -loglevel error -t 9 -i "$HERE/seclab-demo.mp4" \
  -vf "fps=7,scale=320:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse" \
  "$HERE/preview.gif" -y

ls -la "$HERE/seclab-demo.mp4" "$HERE/preview.gif" "$HERE/subtitles.srt"
ffprobe -v error -show_entries format=duration -of csv=p=0 "$HERE/seclab-demo.mp4"
