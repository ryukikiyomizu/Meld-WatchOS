---
name: meld-watchos-scope
description: What the Meld Wear OS work is allowed to be — watch-native UI plus real remote control of the phone, audio always played by the phone, no new backend subsystems on either side.
metadata:
  type: project
---

The user's ask for `Meld-WatchOS`: "fix stuffs for using it on my wear os", modelled on `ryukikiyomizu/SimpMusic-WearOS`, i.e. a genuinely watch-native UI rather than a shrunk phone layout. Scope, as they revised it: "actually i stil lwant its functions while making the ui watch friendly since i'll be using this on my watch7" — so real Meld functions (playback control, queue, library/browse, search, downloads, sleep timer) must be reachable from the watch, but the earlier limit stands: **no new backend subsystems and no watch-side playback engine**. Audio keeps playing through the phone; "downloaded" on the watch means browsing and pruning downloads that live on the phone.

**Why:** they wear a Galaxy Watch 7 and use the phone as the source of truth — a watch that re-implements streaming, sync or accounts would be a different project, and the OEM battery/heat constraints make polling-only + foreground-only work the sane design.

**How to apply:** new watch capability = thin request/response over the existing `MediaBrowser` custom-command surface, never a new service or DB table; user-facing strings go to `wearApp/src/main/res/values/strings.xml` (watch) or `app/src/main/res/values/metrolist_strings.xml` (phone, AGENTS.md rule — don't touch `strings.xml`); keep polling gated on the resumed lifecycle. They also rejected further scope questions: decide, build, and explain afterwards. Audio-only is deliberate — video-backed entries are filtered out on the phone side before they reach the watch.

**Related:** [[meld-watchos-build-blocked]]
