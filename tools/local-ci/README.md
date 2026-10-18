# On-demand local release CI

Run from a clean, pushed release branch on the Mac:

```sh
python3 tools/local-ci/run-release.py --publish-dev
```

Omit `--publish-dev` to build and store a candidate without changing any channel.
Stable promotion is still a separate workflow, with the same identity, version,
signature, immutable-artifact and forward-only checks. The default candidate
workflow (`local_build=false`) still uses GitHub-hosted machines.

The controller dispatches the exact commit, waits for authorization/icon preparation,
then starts one ephemeral Mac runner and one Windows runner. They have no default
runner labels, use random per-run labels, and run a controller-owned pre-job hook
that checks repository, event, actor, run, commit and platform job. No service is
installed. The controller stops processes and removes registrations even on failure.
Do not interrupt it with SIGKILL; SIGINT/SIGTERM run cleanup. If the controller host
loses power, cancel the run and inspect both machines before starting another run.

**These are trusted-code builds, not sandboxed builds.** The public repository
must never route pull requests or arbitrary workflows to these machines. The
owner and code being released are trusted with the `agent` account's access.
Runner labels and hooks reduce accidental scheduling; they do not isolate a host
from malicious trusted code. Keep the hosted fallback for untrusted changes.
GitHub documents this risk in its [runner security guidance](https://docs.github.com/en/actions/reference/security/secure-use#hardening-for-self-hosted-runners).

Persistent Gradle, npm and Cargo caches live in private
`~/.local/share/cobblify-ci/cache` directories on both machines. They can contain
private compiled resources, so do not share or upload them. Local jobs never
upload the workstation's caches to GitHub. Backend credentials
are passed as Gradle project environment variables, never saved to persistent
Gradle properties. Job cleanup removes staged jars and mod build directories.
The previous platform bundle directory is cleared before each build so old
installers/images cannot become this candidate's artifacts.

The Mac needs Node/Rust, both Apple Rust targets, JDK 21, Zulu JDK 8 at
`/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home`, and CLT SDK 26.5.
Windows needs Rust/MSVC, Node and Git Bash. setup-java provisions its JDKs.
No persistent GitHub CLI credentials are copied to Windows; a short-lived runner
registration token goes through SSH stdin and is cleared before running jobs.

This Mac has no Xcode `actool`. A small hosted Mac prerequisite compiles the
current `.icon`; the local adapter checks the exact source tree, output hashes
and commit before giving those files to Tauri. Rust, Gradle, universal linking,
native Rust tests, packaging and signing happen on the local machines. Light/dark icon presence,
SDK version and code signature checks remain mandatory.

Runner binaries must be installed before first use (no admin/service install).
At implementation, verified GitHub `actions/runner` v2.338.0 packages are used:

| Platform | Package | SHA-256 |
|---|---|---|
| Mac ARM64 | `actions-runner-osx-arm64-2.338.0.tar.gz` | `df4cebda25c86a886ed204e49fee63f5c2e7cec5f447b5c98440a826bbdf9df2` |
| Windows x64 | `actions-runner-win-x64-2.338.0.zip` | `f48e0750a21812bca5f82de5f7f5aeae71abee647fab5a582f1742d07eba455f` |

Download only from the official [runner release](https://github.com/actions/runner/releases/tag/v2.338.0),
verify hashes, and unpack under `~/.local/share/cobblify-ci/runner-macos` and
`C:\Users\agent\.local\share\cobblify-ci\runner-windows`. Runner auto-update is
left enabled. Private runner logs and a non-secret `timing.json` are kept under
`~/.local/share/cobblify-ci/runs/<run-id>/` on the Mac. Timings include dispatch,
the small hosted icon job, both platform builds and optional dev publication;
first builds include dependency downloads and must be reported separately from
warm builds. Both machines must remain awake and reachable until completion.
