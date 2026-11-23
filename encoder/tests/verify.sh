#!/usr/bin/env bash
# Checks MP4s written by the helper's end-to-end tests with ffprobe/ffmpeg (Mac). Each file
# needs the `.expect` side file the test wrote beside it; pattern clips also have `.ref60.rgb`.
# Files made on Windows are checked the same way after copying them (with their side files).
#
#   bash encoder/tests/verify.sh <file.mp4>...
#
# Checks: codec (H.264 High, or HEVC tagged hvc1), yuv420p, BT.709 tags, size, frame rate and
# frame count, AAC 48 kHz stereo, sound length (as sent, and within 50 ms of the picture), moov
# before mdat, frame 60 upright with PSNR >= 30 dB against the generator's frame, tone in the left
# channel only, and for the quality clips each rate within 25% of the request and file sizes
# strictly growing with the requested rate.
set -uo pipefail

command -v ffprobe >/dev/null && command -v ffmpeg >/dev/null || { echo "verify.sh needs ffprobe and ffmpeg" >&2; exit 2; }
[ $# -gt 0 ] || { echo "usage: verify.sh <file.mp4>..." >&2; exit 2; }

failures=0
groups=""

fail() { echo "  FAIL $*"; failures=$((failures + 1)); }
pass() { echo "  ok   $*"; }

# probe <file> <stream> <entries> -> key=value lines
probe() { ffprobe -v error -select_streams "$2" -show_entries "stream=$3" -of default=nw=1 "$1"; }
field() { printf '%s\n' "$1" | sed -n "s/^$2=//p" | head -n 1; }
# within <a> <b> <tolerance>
within() { awk -v a="$1" -v b="$2" -v t="$3" 'BEGIN { d = a - b; if (d < 0) d = -d; exit !(d <= t) }'; }

top_level_boxes() {
  local file=$1 size offset=0 len type
  size=$(wc -c <"$file" | tr -d ' ')
  while [ "$offset" -lt "$size" ]; do
    read -r -a b <<<"$(od -An -tu1 -j "$offset" -N 16 "$file" | tr '\n' ' ')"
    len=$(( (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3] ))
    type=$(printf "\\$(printf '%03o' "${b[4]}")\\$(printf '%03o' "${b[5]}")\\$(printf '%03o' "${b[6]}")\\$(printf '%03o' "${b[7]}")")
    if [ "$len" -eq 1 ]; then
      len=$(( (b[8] << 56) | (b[9] << 48) | (b[10] << 40) | (b[11] << 32) | (b[12] << 24) | (b[13] << 16) | (b[14] << 8) | b[15] ))
    elif [ "$len" -eq 0 ]; then
      len=$((size - offset))
    fi
    [ "$len" -ge 8 ] || { echo "?"; return; }
    printf '%s ' "$type"
    offset=$((offset + len))
  done
}

for file in "$@"; do
  echo "$file"
  expect="${file%.mp4}.expect"
  [ -f "$file" ] || { fail "missing file"; continue; }
  [ -f "$expect" ] || { fail "missing $expect"; continue; }
  codec="" width="" height="" fps="" frames="" bitrate="" audio_seconds="" tone=0 ref60=0 rate_group="" encoder=""
  while IFS='=' read -r k v; do
    case "$k" in
      codec|width|height|fps|frames|bitrate|audio_seconds|tone|ref60|rate_group|encoder) printf -v "$k" '%s' "$v" ;;
    esac
  done <"$expect"
  echo "  (encoder reported: ${encoder:-?})"

  v=$(probe "$file" v:0 codec_name,profile,codec_tag_string,pix_fmt,color_primaries,color_transfer,color_space,width,height,r_frame_rate,duration,bit_rate)
  if [ "$codec" = h264 ]; then
    [ "$(field "$v" codec_name)" = h264 ] && [ "$(field "$v" profile)" = High ] \
      && pass "H.264 High" || fail "codec $(field "$v" codec_name) profile $(field "$v" profile)"
  else
    [ "$(field "$v" codec_name)" = hevc ] && [ "$(field "$v" codec_tag_string)" = hvc1 ] \
      && pass "HEVC hvc1" || fail "codec $(field "$v" codec_name) tag $(field "$v" codec_tag_string)"
  fi
  [ "$(field "$v" pix_fmt)" = yuv420p ] && pass "yuv420p" || fail "pix_fmt $(field "$v" pix_fmt)"
  cp=$(field "$v" color_primaries) ct=$(field "$v" color_transfer) cs=$(field "$v" color_space)
  [ "$cp" = bt709 ] && [ "$ct" = bt709 ] && [ "$cs" = bt709 ] && pass "BT.709" || fail "colour $cp/$ct/$cs"
  [ "$(field "$v" width)x$(field "$v" height)" = "${width}x${height}" ] && pass "${width}x${height}" \
    || fail "size $(field "$v" width)x$(field "$v" height), want ${width}x${height}"
  [ "$(field "$v" r_frame_rate)" = "$fps/1" ] && pass "$fps fps" || fail "rate $(field "$v" r_frame_rate)"
  counted=$(ffprobe -v error -select_streams v:0 -count_frames -show_entries stream=nb_read_frames -of default=nw=1:nk=1 "$file")
  [ "$counted" = "$frames" ] && pass "$frames frames" || fail "$counted frames, want $frames"
  vdur=$(field "$v" duration)
  want_v=$(awk -v f="$frames" -v r="$fps" 'BEGIN { printf "%.6f", f / r }')
  one_frame=$(awk -v r="$fps" 'BEGIN { printf "%.6f", 1 / r }')
  within "$vdur" "$want_v" "$one_frame" && pass "picture ${vdur}s" || fail "picture ${vdur}s, want ${want_v}s"

  a=$(probe "$file" a:0 codec_name,sample_rate,channels,duration)
  [ "$(field "$a" codec_name)" = aac ] && [ "$(field "$a" sample_rate)" = 48000 ] && [ "$(field "$a" channels)" = 2 ] \
    && pass "AAC 48 kHz stereo" || fail "sound $(field "$a" codec_name) $(field "$a" sample_rate) $(field "$a" channels)"
  adur=$(field "$a" duration)
  within "$adur" "$audio_seconds" 0.025 && pass "sound ${adur}s (sent ${audio_seconds}s)" || fail "sound ${adur}s, sent ${audio_seconds}s"
  within "$adur" "$vdur" 0.050 && pass "sound within 50 ms of picture" || fail "sound ${adur}s vs picture ${vdur}s"

  boxes=$(top_level_boxes "$file")
  case " $boxes" in
    *" moov "*"mdat "*) pass "moov before mdat ($boxes)" ;;
    *) fail "box order: $boxes" ;;
  esac

  if [ "$ref60" = 1 ]; then
    ref="${file%.mp4}.ref60.rgb"
    psnr=$(ffmpeg -nostdin -hide_banner -nostats -i "$file" -f rawvideo -pix_fmt rgb24 -s "${width}x${height}" -i "$ref" \
      -lavfi "[0:v]select='eq(n,60)',setpts=PTS-STARTPTS,format=rgb24[a];[1:v]format=rgb24[b];[a][b]psnr" -f null - 2>&1 \
      | sed -n 's/.*PSNR.* average:\([0-9.inf]*\).*/\1/p' | tail -n 1)
    if [ -n "$psnr" ] && awk -v p="$psnr" 'BEGIN { exit !(p == "inf" || p + 0 >= 30) }'; then
      pass "frame 60 PSNR ${psnr} dB"
    else
      fail "frame 60 PSNR '${psnr}' dB (< 30, or flipped)"
    fi
  fi

  if [ "$tone" = 1 ]; then
    levels=$(ffmpeg -nostdin -hide_banner -nostats -i "$file" -map 0:a -af astats=measure_overall=none:measure_perchannel=RMS_level -f null - 2>&1 \
      | awk '/Channel: 1/ { c = 1 } /Channel: 2/ { c = 2 } /RMS level dB:/ { print c "=" $NF }')
    left=$(field "$levels" 1) right=$(field "$levels" 2)
    if awk -v l="$left" -v r="$right" 'BEGIN { exit !((l + 0) > -20 && (r == "-inf" || (r + 0) < -60)) }'; then
      pass "tone left ${left} dB, right ${right} dB"
    else
      fail "tone left '${left}' dB, right '${right}' dB"
    fi
  fi

  if [ -n "$rate_group" ]; then
    vrate=$(field "$v" bit_rate)
    ratio=$(awk -v m="$vrate" -v r="$bitrate" 'BEGIN { printf "%.3f", m / r }')
    awk -v x="$ratio" 'BEGIN { exit !(x >= 0.75 && x <= 1.25) }' && pass "rate ${vrate} for ${bitrate} (${ratio})" \
      || fail "rate ${vrate} for ${bitrate} (${ratio})"
    groups+="$rate_group $bitrate $(wc -c <"$file" | tr -d ' ')"$'\n'
  fi
done

if [ -n "$groups" ]; then
  echo "quality ordering"
  for g in $(printf '%s' "$groups" | awk '{ print $1 }' | sort -u); do
    sizes=$(printf '%s' "$groups" | awk -v g="$g" '$1 == g { print $2, $3 }' | sort -n)
    count=$(printf '%s\n' "$sizes" | wc -l | tr -d ' ')
    if printf '%s\n' "$sizes" | awk 'NR > 1 && $2 <= prev { bad = 1 } { prev = $2 } END { exit bad }' && [ "$count" -ge 3 ]; then
      pass "$g: sizes grow with rate ($(printf '%s' "$sizes" | awk '{ printf "%s:%s ", $1, $2 }'))"
    else
      fail "$g: sizes $(printf '%s' "$sizes" | awk '{ printf "%s:%s ", $1, $2 }') (need 3, strictly growing)"
    fi
  done
fi

echo
if [ "$failures" -eq 0 ]; then echo "verify.sh: all checks passed"; else echo "verify.sh: $failures check(s) failed"; fi
[ "$failures" -eq 0 ]
