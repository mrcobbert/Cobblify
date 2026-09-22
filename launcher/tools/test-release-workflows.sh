#!/usr/bin/env bash
#
# Simulates the release workflows' retention and channel-guard shell steps.
#
# These steps only ever run inside GitHub Actions against the real R2 bucket, so
# the failure mode they guard against (deleting the release a channel points at,
# or publishing over a newer channel because a wrangler error read as "no channel
# yet") is exactly the kind that is discovered in production. This script lifts
# each step's `run:` text straight out of the YAML with the same loader the YAML
# check uses - no copy of the shell here to drift - and executes it against fake
# `aws` and `npx wrangler` CLIs.
#
# Two details keep the simulation honest:
#
#   * Steps without `shell:` run under GitHub's default `bash -e {0}`: no
#     `pipefail`, no `-u`. That is what the cases use. One case also runs under
#     `bash -eo pipefail` to prove the retention step is correct under the
#     stricter shell too, since a pipeline whose `grep` legitimately finds
#     nothing must not fail the job.
#   * The fake `wrangler r2 object get` reproduces wrangler 4.x's wording for a
#     missing remote key ("The specified key does not exist.") separately from an
#     API failure, because the guards classify the two differently.
#
# Nothing here touches the network, the real bucket, or anything outside one
# mktemp -d.
#
# Usage: launcher/tools/test-release-workflows.sh
# Exit:  0 = every case behaved as specified, 1 = at least one did not.

set -uo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo" || exit 1

PROMOTE=.github/workflows/launcher-promote.yml
UPDATE=.github/workflows/launcher-update.yml

pass=0
fail=0

tmp=$(mktemp -d) || { echo "mktemp failed" >&2; exit 1; }
trap 'rm -rf "$tmp"' EXIT

# --- fake CLIs --------------------------------------------------------------

bin="$tmp/bin"
mkdir -p "$bin" || exit 1

# `aws s3api list-objects-v2 --output text` prints the keys separated by tabs;
# `aws s3 rm` records the prefix it was asked to delete instead of deleting it.
cat > "$bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
if [ "${1:-}" = "s3api" ] && [ "${2:-}" = "list-objects-v2" ]; then
  printf '%s\n' "${FAKE_LISTING:-}" | tr '\n' '\t'
  printf '\n'
  exit 0
fi
if [ "${1:-}" = "s3" ] && [ "${2:-}" = "rm" ]; then
  printf '%s\n' "${3:-}" >> "$FAKE_RM_LOG"
  exit 0
fi
echo "fake aws: unexpected invocation: $*" >&2
exit 1
FAKE_AWS

# Only `wrangler r2 object get` is modelled. FAKE_R2_GET picks the outcome:
#   ok       - writes {"version": "$FAKE_CHANNEL_VERSION"} to --file, exit 0
#   notfound - wrangler 4.x's UserError for a missing remote key, exit 1
#   error    - an API/auth failure, exit 1
cat > "$bin/npx" <<'FAKE_NPX'
#!/usr/bin/env bash
if [ "${1:-}" = "wrangler" ] && [ "${2:-}" = "r2" ] && [ "${3:-}" = "object" ] && [ "${4:-}" = "get" ]; then
  file=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --file) file=${2:-}; shift 2 ;;
      *) shift ;;
    esac
  done
  case "${FAKE_R2_GET:-}" in
    ok)
      printf '{"version":"%s"}\n' "${FAKE_CHANNEL_VERSION:-}" > "$file"
      exit 0 ;;
    notfound)
      echo "✘ [ERROR] The specified key does not exist." >&2
      exit 1 ;;
    error)
      echo "✘ [ERROR] A request to the Cloudflare API failed. Authentication error [code: 10000]" >&2
      exit 1 ;;
  esac
fi
echo "fake npx: unexpected invocation: $*" >&2
exit 1
FAKE_NPX

# The steps run `npm ci` before using node/jq; there is nothing to install here.
cat > "$bin/npm" <<'FAKE_NPM'
#!/usr/bin/env bash
exit 0
FAKE_NPM

chmod +x "$bin/aws" "$bin/npx" "$bin/npm" || exit 1

# --- harness ----------------------------------------------------------------

last_rc=0

extract() { # <yaml> <job> <step name> <out file>
  ruby -ryaml -e '
    doc = YAML.load_file(ARGV[0])
    jobs = doc["jobs"] or abort "no jobs in #{ARGV[0]}"
    job = jobs[ARGV[1]] or abort "no job #{ARGV[1]}"
    step = job["steps"].find { |s| s["name"] == ARGV[2] } or abort "no step named #{ARGV[2].inspect} in job #{ARGV[1]}"
    run = step["run"] or abort "step #{ARGV[2].inspect} has no run:"
    File.write(ARGV[3], run)
  ' "$1" "$2" "$3" "$4"
}

simulate() { # <yaml> <job> <step name> <working dir> <bash options>
  local yaml=$1 job=$2 step=$3 wd=$4 opts=$5
  : > "$tmp/rm.log"
  : > "$tmp/out"
  : > "$tmp/err"
  if ! extract "$yaml" "$job" "$step" "$tmp/step.sh" 2>"$tmp/err"; then
    last_rc=127
    return 127
  fi
  (
    cd "$repo/$wd" || exit 127
    PATH="$bin:$PATH" \
    RUNNER_TEMP="$tmp" \
    BUCKET=b \
    R2_ENDPOINT=https://r2.test \
    CLOUDFLARE_API_TOKEN=x \
    CLOUDFLARE_ACCOUNT_ID=x \
    FAKE_RM_LOG="$tmp/rm.log" \
    bash $opts "$tmp/step.sh"
  ) >"$tmp/out" 2>"$tmp/err"
  last_rc=$?
  return 0
}

ok()  { pass=$((pass + 1)); printf '  PASS  %s\n' "$1"; }
bad() {
  fail=$((fail + 1))
  printf '  FAIL  %s\n' "$1" >&2
  if [ -n "${2:-}" ]; then printf '        %s\n' "$2" >&2; fi
  printf '        exit=%s\n' "$last_rc" >&2
  sed 's/^/        out| /' "$tmp/out" >&2
  sed 's/^/        err| /' "$tmp/err" >&2
}

expect_rc() { # <label> <expected rc>
  if [ "$last_rc" = "$2" ]; then return 0; fi
  bad "$1" "expected exit $2"
  return 1
}

expect_deleted() { # <label> <expected rm log, newline separated or empty>
  local got want
  got=$(cat "$tmp/rm.log")
  want=$2
  if [ "$got" = "$want" ]; then return 0; fi
  bad "$1" "deleted [$got], expected [$want]"
  return 1
}

expect_text() { # <label> <file> <substring>
  if grep -q -F -- "$3" "$2"; then return 0; fi
  bad "$1" "expected to see: $3"
  return 1
}

case_retention() { # <label> <yaml> <job> <step> <version var> <version> <listing> <expected rm log> [bash opts]
  local label=$1 yaml=$2 job=$3 step=$4 var=$5 opts=${9:-'-e'}
  export FAKE_LISTING=$7
  unset RELEASE_VERSION BUILD_VERSION
  export "$var=$6"
  simulate "$yaml" "$job" "$step" . "$opts" || { bad "$label" "$(cat "$tmp/err")"; return; }
  expect_rc "$label" 0 || return
  expect_deleted "$label" "$8" || return
  ok "$label"
}

case_channel() { # <label> <yaml> <job> <step> <version var> <version> <mode> <channel version> <expected rc> [stdout text] [stderr text]
  local label=$1 yaml=$2 job=$3 step=$4 var=$5
  export FAKE_R2_GET=$7 FAKE_CHANNEL_VERSION=$8
  unset RELEASE_VERSION BUILD_VERSION
  export "$var=$6"
  simulate "$yaml" "$job" "$step" server/stats-worker '-e' || { bad "$label" "$(cat "$tmp/err")"; return; }
  expect_rc "$label" "$9" || return
  if [ -n "${10:-}" ]; then expect_text "$label" "$tmp/out" "${10}" || return; fi
  if [ -n "${11:-}" ]; then expect_text "$label" "$tmp/err" "${11}" || return; fi
  ok "$label"
}

STABLE_STEP='Refuse to move the stable channel backwards'
DEV_STEP='Refuse to move the dev channel backwards'
PROMOTE_RETAIN='Retain only the newest three immutable releases'
DEV_RETAIN='Retain only the newest two dev releases'

FOUR='releases/0.15.2/a
releases/0.16.0/a
releases/0.16.1/a
releases/0.16.2/a
releases/0.16.2-dev.5/a'

RUN='releases/0.16.0/a
releases/0.16.1/a
releases/0.16.2/a
releases/0.16.3/a'

DEVS='releases/0.16.2-dev.5/a
releases/0.16.2-dev.6/a
releases/0.16.2-dev.7/a
releases/0.16.1/a'

echo "== A7: retention never deletes the release the channel now names =="
case_retention 'promoting a hotfix below the newest prefixes keeps it' \
  "$PROMOTE" promote "$PROMOTE_RETAIN" RELEASE_VERSION 0.15.2 "$FOUR" 's3://b/releases/0.16.0/'
case_retention 'promoting the newest version prunes the oldest plain prefix' \
  "$PROMOTE" promote "$PROMOTE_RETAIN" RELEASE_VERSION 0.16.3 "$RUN" 's3://b/releases/0.16.0/'
case_retention 'a first release deletes nothing' \
  "$PROMOTE" promote "$PROMOTE_RETAIN" RELEASE_VERSION 0.16.0 'releases/0.16.0/a' ''
case_retention 'a first release deletes nothing under pipefail' \
  "$PROMOTE" promote "$PROMOTE_RETAIN" RELEASE_VERSION 0.16.0 'releases/0.16.0/a' '' '-eo pipefail'
case_retention 'dev retention keeps the published build and the newest other' \
  "$UPDATE" publish-dev "$DEV_RETAIN" BUILD_VERSION 0.16.2-dev.5 "$DEVS" 's3://b/releases/0.16.2-dev.6/'

echo "== A8: promotion only moves the stable channel forward =="
case_channel 'a newer version is promoted' \
  "$PROMOTE" promote "$STABLE_STEP" RELEASE_VERSION 0.16.0 ok 0.15.1 0
case_channel 'the version already on stable is refused' \
  "$PROMOTE" promote "$STABLE_STEP" RELEASE_VERSION 0.16.0 ok 0.16.0 1
case_channel 'a version below stable is refused' \
  "$PROMOTE" promote "$STABLE_STEP" RELEASE_VERSION 0.16.0 ok 0.16.1 1
case_channel 'a missing stable.json is the first release' \
  "$PROMOTE" promote "$STABLE_STEP" RELEASE_VERSION 0.16.0 notfound '' 0 'no stable channel yet'
case_channel 'an API failure refuses to promote blind' \
  "$PROMOTE" promote "$STABLE_STEP" RELEASE_VERSION 0.16.0 error '' 1 '' 'Authentication error'

echo "== A9: the dev guard fails closed on an unreadable channel =="
case_channel 'a newer dev build is published' \
  "$UPDATE" publish-dev "$DEV_STEP" BUILD_VERSION 0.16.0-dev.7 ok 0.16.0-dev.5 0
case_channel 'an older dev build is refused' \
  "$UPDATE" publish-dev "$DEV_STEP" BUILD_VERSION 0.16.0-dev.7 ok 0.16.0-dev.9 1
case_channel 'the dev build already published is refused' \
  "$UPDATE" publish-dev "$DEV_STEP" BUILD_VERSION 0.16.0-dev.7 ok 0.16.0-dev.7 1
case_channel 'a missing dev.json is the first dev build' \
  "$UPDATE" publish-dev "$DEV_STEP" BUILD_VERSION 0.16.0-dev.7 notfound '' 0 'no dev channel yet'
case_channel 'an API failure refuses to publish blind' \
  "$UPDATE" publish-dev "$DEV_STEP" BUILD_VERSION 0.16.0-dev.7 error '' 1 '' 'Authentication error'

echo
if [ "$fail" -ne 0 ]; then
  echo "$fail of $((pass + fail)) cases FAILED" >&2
  exit 1
fi
echo "all $pass cases passed"
