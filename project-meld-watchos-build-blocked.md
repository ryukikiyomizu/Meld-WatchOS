---
name: meld-watchos-build-blocked
description: The Meld/Metrolist sandbox has no JDK/Android SDK and network is allowlisted to github.com, so Gradle can never verify the Wear work locally — review by inspection and read real compiler errors from CI annotations.
metadata:
  type: project
---

The `Meld-WatchOS` sandbox cannot build: no `java`, no `~/.gradle`, no `local.properties`/`ANDROID_HOME`, and `install-jdk.sh` fails (only `github.com` egress). **Why:** every Wear OS change (new `:wearApp` module, `wearbridge/` srcDir shared into `:app`, `settings.gradle.kts`, `gradle/libs.versions.toml`) must be reviewed by inspection instead of compiled. **How to apply:** don't burn turns on toolchain installs; grep-verify symbols against the sources and cross-check the `SimpMusic-WearOS` reference clone at `/home/user/.cache/ref/SimpMusic-WearOS` for proven wear-compose API usage.

**Status (2026-09-14, turn 4):** ALL Wear code is written and pushed to `origin/arena/01a09db0-meld-watchos` (`d573e35` feat(wear), 34 files → `3dc5de9` fix(wear) LocalLifecycleOwner + error-surfacing step → `af87f28` ci(wear) pipefail + annotations). The dedicated workflow is `.github/workflows/build_wear.yml` (name "Build Wear App": JDK 21, metroproto submodule, setup-protobuf, `:wearApp:assembleDebug` + `:app:compileFossDebugKotlin`, artifact `meld-wear-debug` from `wearApp/build/outputs/apk/debug/*.apk`). **Every run fails inside the gradle step (exit 1) and the actual `e:` compile errors are still unknown** — that is the one blocker between the user and their APK.

**Job logs are unreadable from here; route errors through annotations:** `gh run view --log-failed` and `gh api repos/<o>/<r>/actions/jobs/<jobId>/logs` die with `EOF` (they redirect to `*.blob.core.windows.net`, off the allowlist); `gh api .../actions/runs/<id>/summary` → 404. **What works:** `fetch_page` on `https://github.com/ryukikiyomizu/Meld-WatchOS/actions/runs/<runId>/job/<jobId>` — its "Annotations" section is served by github.com. So the failure step must emit `::error::` workflow commands (written) and must never itself fail.

**Next step (in progress):** the "Surface compile errors" step exits 2 before emitting anything. Local repro (`bash -n` clean, `set -eo pipefail`): an empty `$GITHUB_STEP_SUMMARY` makes the appends die (`line 9: : No such file or directory`) and `grep ... | head -n 200` aborts the step on SIGPIPE under pipefail. Fix = `out="${GITHUB_STEP_SUMMARY:-/dev/null}"`, `| awk 'NR<=N'` instead of `head`, `|| true` per command, `exit 0` last; `--continue` already added so both modules report. Then push, wait ~5 min, read annotations, fix every `e:` line, iterate until green and tell the user the artifact name to download.

**Related:** [[meld-watchos-scope]]
