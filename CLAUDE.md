# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

mTTD is an Android overlay app for *Torchlight: Infinite* (mobile). It floats a HUD over
the game showing real-time farming income, computed by tailing the game's own log file and
pricing item deltas against a market snapshot. It does **not** modify the game or read its memory
— only the log file the game itself writes.

Docs worth reading before making changes: [README.md](README.md) (mechanism, privacy/security
principles) and [INSTALL.md](INSTALL.md) (user-facing setup flow, useful for understanding what
state the app expects at runtime).

## Build commands

Requires JDK 17 and an Android SDK. First-time setup:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # adjust path
```

```bash
./gradlew :app:assembleDebug              # debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease            # release APK (signed only if keystore.properties exists)
./gradlew :app:installDebug               # build + install on connected device/emulator
./gradlew :app:lint                       # Android lint
./gradlew :app:testDebugUnitTest          # JVM unit tests (app/src/test/kotlin)
```

`app/src/test/kotlin` holds JUnit4 + MockK unit tests for pure-Kotlin domain logic (no Android
framework dependency needed — `SessionAggregator` itself has zero `android.*` imports, so it runs
directly on the JVM without Robolectric). `SessionAggregatorTest` is a regression suite built from
real device-log-captured bug repros (first-sighting stacked-pickup undercounting, baseline/uuid
slot-key mismatch overcounting, exchange pause coexistence) — when you fix a bug in the log-parsing
state machine, add a test reproducing the exact line sequence that triggered it, not just a
synthetic case. There is no `app/src/androidTest` (instrumented) suite; UI/Shizuku/overlay code
still needs a real device — validate those by building, installing, and checking `adb logcat` for
the `mTTD.*` tags (see INSTALL.md §7 for the diagnostic grep patterns per subsystem: `.Prices`,
`.Service`, `.Poller`, `.Shizuku`).

There's a separate offline verification harness at `phase0/workbench/` (git-ignored, not part of
the public repo) that replays a captured device log through the parsing logic outside of Android —
useful when iterating on `SessionAggregator`/log-parsing regexes without a device. It runs two
sims side by side (current vs. a modified version) for diffing behavior changes against a real
25MB log.

## Release process

`versionCode` and `versionName` live in `app/build.gradle.kts`. Both **must** be bumped for a
release or Android won't recognize it as an update, and the in-app update checker
(`UpdateChecker`) compares `versionName` against GitHub release tags (`vX.Y.Z`) via semver.
`keystore.properties` (git-ignored) supplies signing credentials; without it release builds are
unsigned.

### Commit message convention (drives release notes)

Prefix every commit subject with one of these tags so release notes can be generated
mechanically instead of judgment-called each time:

- `feat:` — new user-facing feature or behavior change
- `fix:` — user-facing bug fix
- `chore:` — data/config updates with no code behavior change (e.g. `item_names_ko.json` refresh,
  version bump)
- `refactor:` — internal restructuring, no user-visible effect
- `style:` — cosmetic/UI polish that isn't a functional fix (spacing, alignment, colors)
- `docs:` — README/INSTALL/CLAUDE.md only

**When drafting release notes, only `feat:` and `fix:` commits go in the user-facing list.**
`chore:`/`refactor:`/`style:`/`docs:` commits are left out of the notes shown to users — they
still exist in git history for developers, just not surfaced as "what's new." Always show the
drafted notes to the user for approval before actually publishing a release (`gh release create`).

## Architecture

### The core pipeline: log line → parsed event → aggregated session state

This is the part of the codebase that matters most and is the most non-obvious. Everything else
(UI, Shizuku plumbing, price fetching) exists to feed or expose it.

```
UserService (Shizuku, shell UID)
   -> LogPoller (polls file size, reads new byte chunks via AIDL)
      -> raw lines --split on \n/\r\n/\r, tail-buffered across chunk boundaries--
         -> TrackerForegroundService.startPoller() line collector, which does TWO things per line:
            1. aggregator.observeLine(line)   <- the real work, see below
            2. assembler.feed(line) -> aggregator.consume(msg)   <- only for ItemChange/EnterArea *block* events
```

**`SessionAggregator.observeLine()`** (`domain/SessionAggregator.kt`) is a hand-rolled streaming
state machine over the raw log text — not a generic parser. It runs on every incoming line and is
hot-path code (the game can emit ~100 lines/sec while active), which is why it front-loads a cheap
substring pre-filter (`isInteresting`) before touching any of its ~15 regexes. Key mechanics:

- **Inventory deltas, not events.** The game log doesn't emit "player picked up X" — it emits
  slot-state lines (`ItemChange@ Update/Add Id=<baseId>_<uuid> BagNum=N in PageId=X SlotId=Y`)
  followed by a `BagMgr@:Modfy` confirmation carrying the *total* count now in that slot. Actual
  gain/loss is `newCount - lastKnownCount` for that slot UUID, tracked in `slotLastCount`. A
  `Update`/`Add` line is a **real change** only if the very next line is a `Modfy` for the same
  `PageId:SlotId`; otherwise it's part of a bulk inventory snapshot and gets folded into baseline
  instead of counted. This one-line lookahead (`pendingSlot`) was reverse-engineered against a
  real 17MB log and is load-bearing — don't "simplify" it without re-checking against a real log.
- **Baseline gating (`baselineReady`).** Nothing is counted until a full bag snapshot has been
  observed (triggered by the user pressing "sort bag" in-game, or on town return/reconnect).
  Before that, `handleModfy` only updates `slotLastCount` and returns. This is why the app tells
  users to hit "sort" once after opening it.
- **Runs are delimited by map-open (`Spv3Open` block start), not by `EnterArea`.** `EnterArea`
  fires ~100ms *after* the consumption lines for compass/beacon costs, so clearing the
  "this run" bucket there would erase the negative entries that already landed. `startNewRun()`
  is called from the `Spv3Open` start marker instead.
- **Auction-house price observation** (`XchgSearchPrice` Send/Recv blocks, matched by `SynId`) is
  parsed by the same `observeLine` state machine via a small sub-state-machine (`XchgMode`). Prices
  the user personally queries in-game are recorded to `ObservedPriceStore` and take priority over
  the snapshot price feed (see `ValueCalculator`'s priority order).
- Everything not funneled through `observeLine` (currently just `EnterArea` begin/end blocks) goes
  through `MessageAssembler`, a simple header/payload/footer accumulator keyed by
  `LogLineParser.HeaderKind`, and reaches the aggregator via `consume()`.

### Memory policy: summaries live in RAM, item detail lives on disk

`SessionAggregator` keeps only *finished-run summaries* (`MapRun` with `items = emptyList()`) plus
the in-progress run's item list in memory. The moment a run closes, its full item list is hooked
through `onRunFinished` to `RunRepository` (Room, `data/runs/`) and dropped from memory except for
derived totals (`sessionItemTotals`, incrementally updated — never re-scans all runs). This is
deliberate: without it, a long farming session would grow process memory linearly with pickup
count. Opening a run's detail popup re-reads its items from Room on demand
(`TrackerForegroundService.loadRunItems`). Keep this asymmetry in mind before "simplifying" `MapRun`
to always carry its items.

### Shizuku: the only way this app touches the game's files

Android 11+ blocks normal apps from reading another app's `Android/data/<pkg>/` directory. Shizuku
grants shell UID (2000) access, and `UserService` (`service/UserService.kt`, implementing the AIDL
in `aidl/com/mttd/IUserService.aidl`) runs as a **separate process under that UID**, reachable
from the app process only via the fixed AIDL surface — never free-form shell commands. This is a
hard security boundary, not incidental structure:

- Every file op passes through `ensurePathAllowed()`, which whitelists only the three known game
  packages' `Android/data/.../files/UE4Game` paths. Read-only: there is no write/delete op in the
  interface, and op #5 (a since-removed log-delete call) is explicitly retired and must never be
  reused — see the comment in the `.aidl` file.
- `ShizukuManager` (`data/shizuku/`) tracks a 4-part readiness state (`installed`, `binderAlive`,
  `permission`, `userServiceBound`); `ShizukuState.ready` gates whether polling can start.
  `TrackerForegroundService.ensurePollerRunning()` retries binding on a timer without ever calling
  the permission-request dialog itself (that only happens from UI-driven `requestPermissionOrBind`)
  — a background retry loop that pops a permission dialog every few seconds was a real bug class
  here.
- If you're asked to add write/delete/install capability to `UserService`, that's a deliberate
  scope change the project has pushed back on twice (see HANDOFF.md) — confirm with the user
  before adding new AIDL ops, and never add one that takes a raw shell command string.

### Service/UI shape

- `TrackerForegroundService` (`service/`) is the long-lived owner of the poller, aggregator, price
  repo, and overlay. It's a `LifecycleService` bound by `MainActivity` via `LocalBinder`, but it's
  designed to keep working with **no activity bound at all** — `ensurePollerRunning()` and
  `autoShowOverlay()` let it self-start when Shizuku becomes ready, since relying on
  `MainActivity.onResume()` as the only trigger caused "have to leave and reopen the app" bugs
  historically.
- `OverlayHost` (`ui/overlay/`) manages two `WindowManager`-attached Compose views (a small
  draggable icon badge, and an expandable HUD) independent of any Activity — this is what lets the
  HUD render on top of the game.
- Price data has two independently swappable sources (`PriceSource.ETOR` protobuf snapshot,
  `PriceSource.TTD` static JSON), abstracted behind `PriceRepository`. Both are normalized to the
  same unit (itemId `100300`, "Primal Flame Crystal" = 1.0) so switching sources doesn't rescale
  existing totals. `ValueCalculator` layers `ObservedPriceStore` (live auction queries) on top as
  the higher-priority source.
- Outbound network calls are intentionally limited to four hosts — the ETor price API, the TTD
  price API, `api.github.com` for the one-shot update check, and `cdn.tlidb.com` for item icon
  images (loaded on demand via Coil, not bundled in the APK — see `PickupSummary.iconUrl` /
  `ItemInfoLookup.ItemInfo.img`). Don't add a fifth without updating the privacy sections of
  README.md/INSTALL.md and getting explicit sign-off; this is a stated privacy commitment, not an
  oversight.

## Working with the log-format code

`data/log/` (`LogLineParser`, `MessageAssembler`, `TorchlightPayloadParser`, `LogLineFilter`) plus
the regexes inside `SessionAggregator` encode a reverse-engineered wire format with no spec, backed
by measurements against real captured logs (numbers like "163 of 484 Update/Add lines pair with a
Modfy" appear in comments precisely because they were counted, not guessed). If you touch parsing
logic:

- Treat the inline comments citing line counts/percentages from real logs as regression evidence,
  not decoration — they explain *why* a particular ordering or fallback exists.
- Prefer verifying changes against `phase0/workbench/sim-fixed.js` vs `sim-current.js` (a captured
  device log diffed through old vs. new logic) over reasoning from the regexes alone, if that
  directory is present locally.
- The HANDOFF.md file (git-ignored-adjacent, meant to be deleted after the initial repo migration)
  documents one known-unhandled case: equipment dismantling emits `ItemChange@ Delete` lines with
  no count, requiring a slot-vanish special case that doesn't exist yet. Worth checking if it's
  still absent before assuming the aggregator handles all slot-removal paths.
