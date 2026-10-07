#!/bin/bash
#
# Sets this Mac's Lunar / Prism / Cobblify state aside so a launcher test build
# meets a brand-new player, then puts everything back exactly.
#
#   fresh-mac.sh status
#   fresh-mac.sh aside <lunar|prism|all> [--data-only] [--yes]
#   fresh-mac.sh restore [--yes]
#
# Run it in your own account, from Terminal, with Lunar, Prism, their games and
# every Cobblify launcher closed. It never deletes anything:
#   - aside MOVES items into ~/CobblifyStash/<timestamp>/items/;
#   - restore moves anything created while testing into <stash>/new/, moves the
#     originals back, and checks every file against what was recorded at aside.
# You delete the stash folder yourself once you are happy.
#
# Every mode also sets aside Cobblify's own state (~/.weave, ~/.cobblify,
# ~/.bedwarsqol, Cobblify Launcher.app and its ~/Library folders): a new player
# has none of it, and a test build writes there the moment it opens.
# --data-only leaves the Lunar / Prism apps installed (an installed but never
# opened client).
#
# Lunar and Prism must be (re)installed into /Applications: the launcher only
# looks there.
#
# Safety model:
#   - inventory.tsv records every known path as present / absent / kept /
#     untouched at aside. Restore sweeps a path that was absent and now exists.
#   - content/<n>.tsv records every file of every stashed item (path, type, mode,
#     size, symlink target, sha256). Restore verifies the returned items against
#     it.
#   - journal.tsv is append-only: an "intent" line before each move and a "done"
#     line after it. Restore first reconciles any move a crash interrupted, then
#     sweeps, then returns, so it can be re-run until it succeeds.
#
# Environment overrides exist for the test suite only (test-fresh-mac.sh):
# FRESH_HOME, FRESH_APPS, FRESH_PGREP, FRESH_LSREGISTER, FRESH_OSASCRIPT,
# FRESH_MV, FRESH_STAMP.

set -u
export LC_ALL=C

H=${FRESH_HOME:-$HOME}
APPS=${FRESH_APPS:-/Applications}
PGREP=${FRESH_PGREP:-pgrep}
LSREGISTER=${FRESH_LSREGISTER:-/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister}
OSASCRIPT=${FRESH_OSASCRIPT:-osascript}
MV=${FRESH_MV:-mv}
STASH_ROOT="$H/CobblifyStash"
TAB=$(printf '\t')

die() { echo "fresh-mac: $*" >&2; exit 1; }
say() { echo "$*"; }

# ---------------------------------------------------------------------------
# What exists

# group <TAB> path, in move order. Groups: cobblify (every mode), lunar, prism,
# next (the test build: recorded, never stashed).
inventory() {
  local L="$H/Library"
  printf 'cobblify\t%s\n' \
    "$H/.cobblify" "$H/.weave" "$H/.bedwarsqol" "$APPS/Cobblify Launcher.app"
  printf 'lunar\t%s\n' \
    "$APPS/Lunar Client.app" "$H/.lunarclient" \
    "$L/Application Support/lunarclient" "$L/Application Support/minecraft" \
    "$L/Preferences/com.moonsworth.client.plist" \
    "$L/Caches/com.moonsworth.client" "$L/Caches/com.moonsworth.client.ShipIt" \
    "$L/Saved Application State/com.moonsworth.client.savedState" \
    "$L/HTTPStorages/com.moonsworth.client" "$L/HTTPStorages/com.moonsworth.client.binarycookies"
  printf 'prism\t%s\n' \
    "$APPS/Prism Launcher.app" \
    "$L/Application Support/PrismLauncher" \
    "$L/Preferences/org.prismlauncher.PrismLauncher.plist" \
    "$L/Caches/org.prismlauncher.PrismLauncher" "$L/Caches/PrismLauncher" \
    "$L/Saved Application State/org.prismlauncher.PrismLauncher.savedState" \
    "$L/HTTPStorages/org.prismlauncher.PrismLauncher"
  printf 'next\t%s\n' \
    "$H/Applications/Cobblify Launcher (Next).app" "$APPS/Cobblify Launcher (Next).app"
}

group_of_name() {
  local n; n=$(printf '%s' "$(basename "$1")" | tr '[:upper:]' '[:lower:]')
  case "$n" in
    *lunar*|*moonsworth*) echo lunar ;;
    *prism*) echo prism ;;
    *cobblify*) echo cobblify ;;
    *) echo other ;;
  esac
}

# Keeps only top-most paths: drops any line that is inside an earlier-kept one
# or inside a path listed in the file named by $1 (may be empty).
drop_covered() {
  local cover=$1
  sort -u | awk -v cover="$cover" '
    BEGIN { if (cover != "") while ((getline c < cover) > 0) covers[++nc] = c }
    {
      p = $0
      for (i = 1; i <= nc; i++) if (p == covers[i] || index(p, covers[i] "/") == 1) next
      for (i = 1; i <= nk; i++) if (index(p, kept[i] "/") == 1) next
      kept[++nk] = p; print p
    }'
}

# ~/Library discovery: anything named like Lunar / Moonsworth / Prism / Cobblify
# within three levels, minus what the fixed inventory already covers.
# Prints group <TAB> path.
discover() {
  local cover; cover=$(mktemp) || die "mktemp failed"
  inventory | cut -f2 > "$cover"
  find "$H/Library" -maxdepth 3 \( -iname '*lunar*' -o -iname '*moonsworth*' \
      -o -iname '*prism*' -o -iname '*cobblify*' \) -print 2>/dev/null \
    | drop_covered "$cover" \
    | while IFS= read -r p; do printf '%s\t%s\n' "$(group_of_name "$p")" "$p"; done
  command rm -f -- "$cover"
}

exists() { [ -e "$1" ] || [ -L "$1" ]; }

# Related processes that must not run while files move. The patterns are the
# apps' own paths, so this script (wherever it lives) never matches itself.
running() {
  local pat
  for pat in '/Lunar Client.app/' 'Lunar Client Helper' "$H/.lunarclient/" \
             '/Prism Launcher.app/' 'PrismLauncher/' '/Contents/MacOS/cobblify-launcher'; do
    "$PGREP" -fl -- "$pat" 2>/dev/null
  done | sort -u
}

refuse_if_running() {
  local r; r=$(running)
  [ -z "$r" ] || die "close these first (Lunar, Prism, their games, every Cobblify launcher):
$r"
}

login_items() {
  "$OSASCRIPT" -e 'tell application "System Events" to get the name of every login item' 2>/dev/null \
    || echo "(could not read login items; check System Settings > General > Login Items by hand)"
}

active_stash() {
  local d
  for d in "$STASH_ROOT"/*/; do
    [ -f "${d}state" ] || continue
    [ "$(cat "${d}state")" = active ] && { printf '%s\n' "${d%/}"; return 0; }
  done
  return 1
}

# Where an original lives inside a stash area (items/ or new/<n>/). Apps lose
# their bundle suffix there so LaunchServices and Spotlight never run them.
enc() {
  local p=$1 r
  case "$p" in
    "$H"/*) r="home/${p#"$H"/}" ;;
    "$APPS"/*) r="apps/${p#"$APPS"/}" ;;
    *) die "path outside the managed roots: $p" ;;
  esac
  case "$r" in *.app) r="$r.stashed" ;; esac
  printf '%s' "$r"
}

is_app() { case "$1" in *.app) return 0 ;; esac; return 1; }

# ---------------------------------------------------------------------------
# Content manifest: one line per entry, sorted, relative to the item's parent so
# it is identical before and after the round trip.
# path <TAB> type <TAB> mode <TAB> size <TAB> link target <TAB> sha256

content_of() {
  local item=$1 parent name
  parent=$(dirname "$item"); name=$(basename "$item")
  ( cd "$parent" || exit 1
    # A folder that cannot be listed hides its files from the record (and from the check at restore), so any
    # traversal error makes the whole item unreadable rather than silently partial.
    if [ -n "$(find "$name" -print 2>&1 >/dev/null)" ]; then echo "UNREADABLE"; exit 0; fi
    if find "$name" -name "*$TAB*" -print -quit | grep -q . \
       || [ "$(find "$name" -print | wc -l)" != "$(find "$name" -print0 | tr -cd '\0' | wc -c)" ]; then
      echo "UNSAFE-NAME"; exit 0
    fi
    find "$name" -type f -print0 | xargs -0 shasum -a 256 2>/dev/null \
      | sed 's/^\([0-9a-f]\{64\}\)  /\1\t/' > "$tmp_hashes"
    find "$name" -print0 | xargs -0 stat -f "%N$TAB%HT$TAB%Lp$TAB%z$TAB%Y" \
      | awk -F'\t' -v hashes="$tmp_hashes" '
          BEGIN { while ((getline l < hashes) > 0) { i = index(l, "\t"); h[substr(l, i + 1)] = substr(l, 1, i - 1) } }
          { size = ($2 == "Directory") ? "-" : $4
            sum = ($2 == "Regular File") ? (($1 in h) ? h[$1] : "UNHASHED") : "-"
            printf "%s\t%s\t%s\t%s\t%s\t%s\n", $1, $2, $3, size, $5, sum }' \
      | sort )
}

write_content() {
  tmp_hashes=$(mktemp) || die "mktemp failed"
  content_of "$1" > "$2"
  command rm -f -- "$tmp_hashes"
  ! grep -q '^UNSAFE-NAME$' "$2" || die "$1 holds a file name with a tab or newline; move it by hand"
  ! grep -q 'UNHASHED$' "$2" || die "could not read every file in $1 (permissions?)"
  ! grep -q '^UNREADABLE$' "$2" || die "could not list every folder in $1 (permissions?); fix them, then run aside again"
}

# ---------------------------------------------------------------------------
# Journal

J() { printf '%s\n' "$STASH/journal.tsv"; }

jnext() { local n; n=$(awk -F'\t' 'END { print NR + 1 }' "$(J)"); printf '%s' "$n"; }

jwrite() { printf '%s\t%s\t%s\t%s\t%s\n' "$@" 2>/dev/null >> "$(J)" && sync; }

# The journal is the only record of what moved: when it cannot be written, nothing else moves.
jfail() {
  echo "fresh-mac: cannot write the stash journal $(J). Nothing more is moved. Free some disk space or fix its permissions, then run: fresh-mac.sh restore" >&2
}

# Moves src to dst through the journal. Returns non-zero on failure.
#
# mv runs in this shell, not a subshell, so a crash during it is a crash of the
# script (intent with no done), which reconcile() resolves. When mv reports a
# failure, the outcome is still read from the disk rather than assumed: a "void"
# written for a move that did happen would make a later restore treat the
# returned original as test data.
jmove() {
  local op=$1 src=$2 dst=$3 seq errf="$STASH/mv-error.txt"
  seq=$(jnext)
  mkdir -p "$(dirname "$dst")" || return 1
  if exists "$dst"; then echo "fresh-mac: refusing to overwrite $dst" >&2; return 1; fi
  if [ "$(stat -f %d "$(dirname "$src")")" != "$(stat -f %d "$(dirname "$dst")")" ]; then
    echo "fresh-mac: $src and $dst are on different volumes; refusing a copying move" >&2; return 1
  fi
  jwrite "$seq" "$op" intent "$src" "$dst" || { jfail; return 1; }
  # A move whose "done" cannot be written stays an open intent, which reconcile() settles from the disk.
  if "$MV" "$src" "$dst" 2>"$errf"; then
    jwrite "$seq" "$op" done "$src" "$dst" || { jfail; return 1; }
    return 0
  fi
  if exists "$dst" && ! exists "$src"; then
    jwrite "$seq" "$op" done "$src" "$dst" || { jfail; return 1; }
    return 0
  fi
  if exists "$src" && ! exists "$dst"; then
    jwrite "$seq" "$op" void "$src" "$dst" || jfail
  else
    echo "fresh-mac: a failed move left both or neither of $src and $dst; the journal keeps it open for restore" >&2
  fi
  echo "fresh-mac: could not move $src: $(cat "$errf")" >&2
  if is_app "$src" && grep -qi 'not permitted' "$errf" \
     && [ "$(sw_vers -productVersion 2>/dev/null | cut -d. -f1)" -ge 15 ] 2>/dev/null; then
    echo "fresh-mac: macOS needs Terminal to have App Management: System Settings > Privacy & Security > App Management > turn on Terminal, then run this again." >&2
  fi
  return 1
}

# Folders testing created only to hold what was swept (~/Applications,
# ~/Library/WebKit, ...): removed when empty and absent at aside. rmdir cannot
# remove a folder that holds anything.
prune_new_dirs() {
  local d
  while IFS= read -r d; do
    while [ "$d" != "$H" ] && [ "$d" != "$APPS" ] && [ "$d" != / ]; do
      grep -qxF "$d" "$STASH/dirs.txt" && break
      rmdir "$d" 2>/dev/null || break
      say "  removed empty folder ${d#"$H"/}"
      d=$(dirname "$d")
    done
  done
}

# Finishes the record of any move a crash interrupted, by looking at which side
# of it exists. Must run before anything is classified.
reconcile() {
  local seq op src dst
  awk -F'\t' '$3 == "intent" { i[$1] = $0 } $3 != "intent" { delete i[$1] } END { for (s in i) print i[s] }' "$(J)" \
    | sort -n | while IFS="$TAB" read -r seq op _ src dst; do
      if exists "$dst" && ! exists "$src"; then jwrite "$seq" "$op" done "$src" "$dst" || { jfail; exit 3; }
      elif exists "$src" && ! exists "$dst"; then jwrite "$seq" "$op" void "$src" "$dst" || { jfail; exit 3; }
      else
        echo "fresh-mac: an interrupted move left both or neither of:
  $src
  $dst
Nothing was changed. Check both paths by hand, then run restore again." >&2
        exit 3
      fi
    done
}

# Originals whose stash move completed, and whose return has not.
stashed_not_returned() {
  awk -F'\t' '
    $3 == "done" && $2 == "stash"  { s[$4] = $5; order[++n] = $4 }
    $3 == "done" && $2 == "return" { r[$5] = 1 }
    END { for (k = 1; k <= n; k++) { o = order[k]; if (!(o in r)) printf "%s\t%s\n", o, s[o] } }' "$(J)"
}

returned() { awk -F'\t' -v p="$1" '$3 == "done" && $2 == "return" && $5 == p { f = 1 } END { exit !f }' "$(J)"; }

# Puts every stashed-not-returned item back. Returns non-zero if any move fails.
return_pass() {
  local orig src fail=0
  while IFS="$TAB" read -r orig src; do
    [ -n "$orig" ] || continue
    if exists "$orig"; then
      echo "fresh-mac: $orig exists, cannot return its original" >&2; fail=1; continue
    fi
    if jmove return "$src" "$orig"; then
      is_app "$orig" && "$LSREGISTER" -f "$orig" >/dev/null 2>&1
      say "  returned  ${orig#"$H"/}"
    else
      fail=1
    fi
  done < <(stashed_not_returned)
  return $fail
}

# Checks every stashed item against its content manifest and every recorded
# path against its state at aside.
verify() {
  local bad=0 group state path n f
  while IFS="$TAB" read -r group state path n; do
    case "$state" in
      present)
        f="$STASH/content/$n.tsv"
        if ! exists "$path"; then echo "  MISSING   $path" >&2; bad=1
        else
          tmp_hashes=$(mktemp) || die "mktemp failed"
          if ! content_of "$path" | cmp -s - "$f"; then
            echo "  CHANGED   $path" >&2
            content_of "$path" | diff "$f" - | sed -n '1,12p' | sed 's/^/            /' >&2
            bad=1
          fi
          command rm -f -- "$tmp_hashes"
        fi ;;
      absent) if exists "$path"; then echo "  UNEXPECTED $path" >&2; bad=1; fi ;;
      kept) if ! exists "$path"; then echo "  MISSING   $path" >&2; bad=1; fi ;;
    esac
  done < "$STASH/inventory.tsv"
  return $bad
}

set_state() { printf '%s\n' "$1" > "$STASH/state"; }

confirm() {
  [ "$yes" = 1 ] && return 0
  printf '%s [y/N] ' "$1"
  local a; read -r a < /dev/tty || a=""
  case "$a" in y|Y|yes|YES) return 0 ;; esac
  return 1
}

# ---------------------------------------------------------------------------
# Commands

cmd_status() {
  local group path
  say "Known paths (state now):"
  inventory | while IFS="$TAB" read -r group path; do
    if exists "$path"; then say "  present  [$group] $path"; else say "  absent   [$group] $path"; fi
  done
  say "Also found in ~/Library:"
  discover | while IFS="$TAB" read -r group path; do say "  [$group] $path"; done
  say "Running related processes:"
  local r; r=$(running); say "${r:-  none}"
  say "Login items:"
  login_items | sed 's/^/  /'
  local s; if s=$(active_stash); then say "Active stash: $s (run: fresh-mac.sh restore)"; else say "No active stash."; fi
}

cmd_aside() {
  local mode="" data_only=0
  while [ $# -gt 0 ]; do
    case "$1" in
      lunar|prism|all) mode=$1 ;;
      --data-only) data_only=1 ;;
      --yes) yes=1 ;;
      *) die "usage: fresh-mac.sh aside <lunar|prism|all> [--data-only] [--yes]" ;;
    esac; shift
  done
  [ -n "$mode" ] || die "usage: fresh-mac.sh aside <lunar|prism|all> [--data-only] [--yes]"
  local s; if s=$(active_stash); then die "a stash is already active: $s. Run: fresh-mac.sh restore"; fi
  refuse_if_running

  in_mode() {
    case "$1" in
      cobblify) return 0 ;;
      lunar) [ "$mode" = lunar ] || [ "$mode" = all ] ;;
      prism) [ "$mode" = prism ] || [ "$mode" = all ] ;;
      *) return 1 ;;
    esac
  }

  # Build the inventory with each path's state, then the move list.
  local plan; plan=$(mktemp) || die "mktemp failed"
  local group path state
  { inventory; discover; } | while IFS="$TAB" read -r group path; do
    if [ "$group" = next ]; then state=$(exists "$path" && echo kept || echo absent)
    elif [ "$group" = other ] || ! in_mode "$group"; then state=untouched
    elif ! exists "$path"; then state=absent
    elif [ "$data_only" = 1 ] && is_app "$path" && [ "$group" != cobblify ]; then state=kept
    else state=present
    fi
    printf '%s\t%s\t%s\n' "$group" "$state" "$path"
  done > "$plan"

  say "This will MOVE (not delete) these into ~/CobblifyStash:"
  awk -F'\t' '$2 == "present" { print "  " $3 }' "$plan"
  grep -q "${TAB}present${TAB}" "$plan" || { command rm -f -- "$plan"; die "nothing to set aside"; }
  if ! confirm "Set these aside?"; then command rm -f -- "$plan"; die "nothing was moved"; fi

  STASH="$STASH_ROOT/${FRESH_STAMP:-$(date +%Y%m%d-%H%M%S)}"
  [ -e "$STASH" ] && die "$STASH already exists"
  mkdir -p "$STASH/content" "$STASH/items" || die "could not create $STASH"
  : > "$STASH/journal.tsv"
  printf '%s\n%s\n' "$mode" "$data_only" > "$STASH/mode"
  awk -F'\t' 'BEGIN { OFS = "\t" } { print $1, $2, $3, ($2 == "present") ? ++n : "-" }' "$plan" > "$STASH/inventory.tsv"
  command rm -f -- "$plan"
  login_items > "$STASH/login-items.txt"
  # Every folder that existed at aside and could hold a swept path.
  { find "$H/Library" -maxdepth 2 -type d -print 2>/dev/null
    cut -f3 "$STASH/inventory.tsv" | while IFS= read -r path; do
      path=$(dirname "$path")
      while [ "$path" != / ]; do [ -d "$path" ] && printf '%s\n' "$path"; path=$(dirname "$path"); done
    done
  } | sort -u > "$STASH/dirs.txt"

  say "Recording every file (large folders take a while)..."
  local n
  while IFS="$TAB" read -r group state path n; do
    [ "$state" = present ] || continue
    say "  recording ${path#"$H"/}"
    write_content "$path" "$STASH/content/$n.tsv"
  done < "$STASH/inventory.tsv"
  cp "$STASH/inventory.tsv" "$STASH/confirmed.tsv"
  # Active only once every item is recorded: an item that cannot be recorded stops aside with nothing moved
  # and no stash to restore.
  set_state active

  local failed=0
  while IFS="$TAB" read -r group state path n; do
    [ "$state" = present ] || continue
    is_app "$path" && "$LSREGISTER" -u "$path" >/dev/null 2>&1
    if jmove stash "$path" "$STASH/items/$(enc "$path")"; then
      say "  set aside ${path#"$H"/}"
    else
      failed=1; break
    fi
  done < "$STASH/inventory.tsv"

  if [ "$failed" = 1 ]; then
    say "Putting back what was moved..."
    if reconcile && return_pass && verify; then
      set_state aborted
      die "aside failed and everything was put back. Stash record kept in $STASH"
    fi
    die "aside failed AND putting things back failed. Fix the cause above, then run: fresh-mac.sh restore"
  fi
  say ""
  say "Done. Stash: $STASH"
  say "Login items at the time (re-check them after restore):"
  sed 's/^/  /' "$STASH/login-items.txt"
  say "Install Lunar / Prism into /Applications when testing. Run 'fresh-mac.sh restore' when finished."
}

cmd_restore() {
  while [ $# -gt 0 ]; do
    case "$1" in --yes) yes=1 ;; *) die "usage: fresh-mac.sh restore [--yes]" ;; esac; shift
  done
  STASH=$(active_stash) || die "no active stash under $STASH_ROOT"
  refuse_if_running
  confirm "Restore from $STASH? Anything created while testing moves into $STASH/new/." || die "nothing was changed"

  reconcile || exit 1

  local mode; mode=$(sed -n 1p "$STASH/mode")
  local attempt=1; while [ -e "$STASH/new/$attempt" ]; do attempt=$((attempt + 1)); done
  local newdir="$STASH/new/$attempt"

  # Sweep: what testing created. Never a returned original.
  local group state path n sweep; sweep=$(mktemp) || die "mktemp failed"
  while IFS="$TAB" read -r group state path n; do
    case "$state" in
      absent) exists "$path" && printf '%s\n' "$path" ;;
      present) if exists "$path" && ! returned "$path"; then
                 stashed_not_returned | cut -f1 | grep -qxF "$path" && printf '%s\n' "$path"
               fi ;;
    esac
  done < "$STASH/inventory.tsv" > "$sweep"
  local cover; cover=$(mktemp) || die "mktemp failed"
  cut -f3 "$STASH/inventory.tsv" > "$cover"
  discover | while IFS="$TAB" read -r group path; do
    case "$group" in
      cobblify) ;;
      lunar) [ "$mode" = lunar ] || [ "$mode" = all ] || continue ;;
      prism) [ "$mode" = prism ] || [ "$mode" = all ] || continue ;;
      *) continue ;;
    esac
    grep -qxF "$path" "$cover" || printf '%s\n' "$path"
  done >> "$sweep"
  command rm -f -- "$cover"

  local failed=0
  while IFS= read -r path; do
    [ -n "$path" ] || continue
    is_app "$path" && "$LSREGISTER" -u "$path" >/dev/null 2>&1
    if jmove sweep "$path" "$newdir/$(enc "$path")"; then
      say "  moved to new/  ${path#"$H"/}"
    else
      failed=1
    fi
  done < <(drop_covered "" < "$sweep")
  command rm -f -- "$sweep"
  # Every sweep the journal records, this attempt's and any an interrupted one finished: their parents may be
  # folders testing created.
  awk -F'\t' '$2 == "sweep" && $3 == "done" { print $4 }' "$(J)" \
    | while IFS= read -r path; do dirname "$path"; done | sort -ru | prune_new_dirs
  [ "$failed" = 0 ] || die "could not move everything created while testing. Fix the cause above and run restore again."

  return_pass || die "some originals could not be returned. Fix the cause above and run restore again."

  say "Checking every returned file against the record..."
  if ! verify; then
    die "restore finished moving but the check above found differences. The stash stays active; nothing was deleted."
  fi
  set_state restored
  say ""
  say "Restored. Every returned file matches the record taken at aside."
  say "Anything created while testing is in: $newdir"
  say "Login items when you set things aside (re-check them):"
  sed 's/^/  /' "$STASH/login-items.txt"
  say "Delete $STASH yourself once you are happy."
}

yes=0
cmd=${1:-}; [ $# -gt 0 ] && shift
case "$cmd" in
  status) cmd_status ;;
  aside) cmd_aside "$@" ;;
  restore) cmd_restore "$@" ;;
  *) sed -n '2,21p' "$0"; exit 2 ;;
esac
