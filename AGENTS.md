# Per-Line Coverage Info — IntelliJ Plugin

## Overview

PhpStorm plugin that shows per-line PHP coverage inline in the editor. CI collects coverage via a custom PHP/Behat extension (`.covt` files) and uploads it to GitLab pipeline artifacts; the **covd** daemon (coverage-assistant) resolves the pipeline, downloads the shards and writes a local `.cov4` cache, and the plugin renders it via gutter icons + line backgrounds. Clicking a covered line shows the tests that execute it; tests can be re-run from the popup.

- **Plugin ID**: `com.github.yakov255.perlinecoverageinfo`
- **Platform**: IntelliJ IDEA Ultimate 2025.2+, depends on `com.intellij.modules.platform`, `com.intellij.mcpServer`, `com.jetbrains.php`, `gherkin`, optional `com.jetbrains.php.behat`, optional `Git4Idea`
- **Language**: Kotlin, JVM target 17 (`jvmToolchain(17)`; the platform itself runs on Java 21+) · **Build**: Gradle + IntelliJ Platform Gradle Plugin
- **Coverage engine**: bundled `covd` daemon (Go, from `coverage-assistant`); the plugin is a client

## Project Layout

```
src/main/kotlin/com/github/yakov255/perlinecoverageinfo/   # plugin source
src/test/kotlin/com/github/yakov255/perlinecoverageinfo/   # unit tests
src/main/resources/META-INF/plugin.xml                     # manifest (+ behat-integration.xml, git-integration.xml)
src/main/resources/bin/<platform>/covd[.exe]               # committed covd binaries (+ covd-manifest.properties)
php-behat-coverage-extension/                              # PHP ext that emits .covt
coverage_storage_format_v4.md                              # COV4 binary spec
```

## Source Files

### Settings
| File | Purpose |
|------|---------|
| `CoverageApiSettings.kt` | App-level settings: GitLab domain/token/project, coverage branch, `enabled` master switch, `remoteUrlAutoChecked` sentinel. The access token lives in `PasswordSafe` through `TokenStore.kt`; `State.bearerToken` is a read-only legacy field (`getState()` always returns it empty, migration happens in `loadState`), and the storage declares `roamingType = RoamingType.DISABLED` because the token is machine-local. `baseUrlFor(domain)` is the single base-URL helper (keeps an explicit `http://host:port`, adds `https://` otherwise) |
| `CoverageApiSettingsConfigurable.kt` | Settings UI — **Settings → Tools → GitLab Coverage**; a password field for the token (the stored value is never shown, empty means "keep", "Remove the stored token when applied" clears it), Apply/OK save the settings and run `CoverageSettingsActions.afterSettingsApplied()` (clears every project's failure block and retry counter, then loads) |
| `CoverageSettingsActions.kt` | Shared "settings applied" path used by the settings page and the bridge command `settings.apply`: reloads every open project and lifts a token or deterministic-error block |
| `GitLabTokenDialog.kt` | First-run dialog that asks for the GitLab token when none is stored and `tokenPrompted` is false; password field, links built with `CoverageApiSettings.baseUrlFor` |
| `TokenStore.kt` | `TokenStore` with a `PasswordSafe` implementation (single `CredentialAttributes` entry) and an in-memory one; `TokenCache` keeps the credential store off the EDT (cached get, background load, one writer thread, last-write-wins) and is used by `CoverageApiSettings.bearerToken`. `-Dcoverage.tokenStore=memory` is an inert-by-default test escape hatch |
| `CustomRepoProvider.kt` | `updateSettingsProvider` — points the IDE updater at `docs/updatePlugins.xml` instead of JetBrains Marketplace |

### Component configuration
| File | Purpose |
|------|---------|
| `ComponentConfig.kt` | Hardcoded mapping from repository service directories to CI job prefixes (e.g. `api/avia` → `test:behat:avia`). `detectComponent()` returns the component for the current project root (root → `raketa`, subpath → matching component). Coverage is published by the dedicated `test:behat:coverage:<component>` jobs; `CoverageLoadService` sends both that prefix (`coverageJobPrefix`) and the legacy one to covd, which selects the jobs. `selectCoverageJobNames()` is a pure helper kept for tests and is no longer called at runtime. |

### MCP server
| File | Purpose |
|------|---------|
| `CoverageMcpToolset.kt` | JetBrains MCP framework toolset via `com.intellij.mcpServer`; exposes `get_coverage_for_file`, `get_tests_at_line`, `get_first_tests_at_lines`, and `list_files` tools |

### GitLab and git access
| File | Purpose |
|------|---------|
| `GitLabProjectsClient.kt` | Minimal REST client used only by the settings screen to list/search the projects the token can access. Retries timeouts/`5xx` (3 attempts, growing pause, 2-minute budget) and throws `IOException` on failure; JSON fields are mapped with `@SerialName` (`path_with_namespace`, `web_url`). Blocking: call it from a pooled thread |
| `GitCommand.kt` | Read-only `git` wrapper: HEAD, recent commits, merge-base, `rev-list --count`, and `git show` through `runRaw` (stdout verbatim, no trim, so unchanged content compares equal to the editor text). `run` keeps the trimmed one-line behaviour. stdout and stderr are drained on their own threads and every call has a timeout (`destroyForcibly` on expiry), so a hung or noisy `git` cannot deadlock a pooled thread |
| `GitLabCoordinator.kt` | App-level (`Service.Level.APP`) LRU of shared `Cov4Reader` instances plus `evictSharedReaders()` after a cache rewrite |
| `CovdClient.kt` | Client for the coverage daemon: binary extraction, handshake (daemon starts are serialized, so concurrent callers share one process and one restart), `ensure`, `import`, artifact listing/deletion. Non-2xx answers without a `state` become `error`; a non-JSON body becomes `Unavailable`. Typed `trace` (`request`, `timing`, `gitlab` calls, `decisions`, `errors`) in `EnsureResponse`; every `ensure`/`import` answer is handed to `CovdResponseRecorder` and logged as `covd.response`, each new connection as `covd.spawn`. `cacheRoot` honours `-Dcoverage.cacheRoot`; `currentConnection()`/`daemonPid()` are read by `CoverageUiTestBridge`. `internal` test seams: `binaryOverride`, `cacheRootOverride`, `extraDaemonArgs`, timeouts, `startCount`, `resetForTest()`. An `ensure`/`import` restarts the daemon and repeats the request only when the connection failed before an answer (refused, reset); a read timeout is `Unavailable` at once, so a long download is not doubled. stdout is read by a daemon thread (handshake line, then drained) and stderr by another (`{"covdStartError":{code,message}}` is included in `Unavailable`); a failed handshake destroys the process; the daemon token is masked in every log/message. `Unavailable` is an `IOException` |

### Loading pipeline
| File | Purpose |
|------|---------|
| `CoverageLoadService.kt` | Orchestrator: `loadOfflineFirst(manual)` (cache walk → stale display → daemon refresh), `loadFromGitLab(showErrors, onComplete, onError, bypassBlock)` (one `ensure` against covd; `ready`/`not_ready`/`error` handling), `loadFromLocalFile()` (daemon `import`), `loadFromCache()` (pinned artifact). Applies the failure contract through `RetryPolicy`/`RetryState`: retry counter and block (`retryAttempt`, `retryMax`, `blockedReason()`), delayed retries on the shared `covdRetryAlarm`, reset on success/HEAD change/settings change, manual load bypasses both. Applies readers to `CoverageDataService` and computes the "behind by" warning context. `loading`/`pendingReload` are `AtomicBoolean`s; `isPinned()`/`clearPin()`/`onCleared()`. An answer whose request HEAD differs from the current HEAD is applied as `stale`, raises no "Coverage updated" notice, leaves the retry/block state alone and queues a reload for the new HEAD. `loadFromLocalFile` pins the imported commit |
| `CoverageHeadTracker.kt` | Git4Idea listener: forwards repository changes to `CoverageHeadMonitor`. Skipped when the gutter is hidden |
| `CoverageHeadMonitor.kt` / `CoverageHeadTracking.kt` | Project service owning the last known HEAD revision, shared by the Git4Idea listener and the poller; repositories outside the coverage git root (submodules) are ignored (`CoverageRepositoryFilter`). On a change it calls `CoverageLoadService.onHeadChanged()` (a token block survives it) and schedules the debounced (2 s) `loadOfflineFirst()`; the first observed revision is skipped (startup handles the initial load). `HeadRevisionTracker` is the pure last-revision logic |
| `CoverageStartupActivity.kt` | On project open: remote-URL auto-check, the first-run token dialog (posted with `invokeLater`, shown once per IDE run through `TokenPromptGuard`, never in unit-test mode), then `loadOfflineFirst()` on a pooled thread; logged JVM arguments for the perf log. No coroutine suspension (see Conventions) |
| `CoveragePipelinePoller.kt` | 30-second tick that asks the daemon again; no GitLab logic. Skipped while a load is in progress or while `CoverageLoadService.isBlocked()` holds, and disabled when the gutter is hidden. Also skipped while a coverage artifact is pinned. On every tick it also asks `CoverageHeadMonitor.checkHeadFromPoller()` (`git rev-parse HEAD`), so a checkout Git4Idea reports late (linked worktrees) is caught within one poll |
| `LoadCoverageAction.kt` | Find Action: "Load Coverage from GitLab" |
| `LoadLocalCoverageAction.kt` | Find Action: "Load Coverage from File" (.covt/.covt.gz), imported through the daemon |
| `ClearCoverageAction.kt` | Find Action: "Clear Coverage Data" — stops the poller, lifts the pin, drops the retry alarm and the queued reload, clears the data |

### On-disk format
| File | Purpose |
|------|---------|
| `Cov4Reader.kt` | Reads COV4 on demand (lazy per-file decoding, streams the index instead of loading the file); implements `Closeable`. The header, test list and INDX are consumed through one buffered channel cursor. Every length and counter is validated against the file size before allocation, so a truncated cache raises `CoverageApiException(ARTIFACT_PARSE)` instead of `NegativeArraySizeException`/OOM. Each section's CRC32 is verified on read: a damaged section yields no coverage for that file and one log line, the rest stays usable (a stored CRC of 0 is treated as "not written" and skipped). A `.cov4` removed by another IDE degrades to an empty reader with one log line, never an exception. Reloads its index when the file was replaced. Accepts optional path/test normalizers, so old raketa `.cov4` caches are normalized while reading |
| `CoveragePathNormalizer.kt` | Normalizes raketa `.covt` keys/tests to git-root-relative paths; used when reading pre-upgrade caches (the daemon normalizes new ones) |
| `CoverageCacheService.kt` | Read-only view of `<cacheRoot>/<projectId>/` (`-Dcoverage.cacheRoot`, default `~/.cache/coverage-plugin`): `cache-index.json` + `<hash>-<component>.cov4`, reader sharing, artifact listing. Writes and deletions go through the daemon. `cache-index.json` is decoded with `ignoreUnknownKeys = true` (a field added by a newer covd must not hide the cache) and `findCachedCommit(candidates, component)` only considers entries of the active component |

### In-memory model
| File | Purpose |
|------|---------|
| `CoverageDataService.kt` | Holds active coverage as one immutable `@Volatile` `Snapshot` (primary/baseline `Cov4Reader`, commits, gitRoot, stale, headHash, mergeBase, behind-by), replaced whole under a lock; `snapshot()` gives a consistent view and the old properties are thin getters. `setCoverage(commit, gitRoot, stale, reader)` sets context and reader in one step. `coverageGeneration` is an `AtomicLong`, +1 per change. `Cov4Reader` only — no in-memory coverage map |
| `CoverageWarningService.kt` | Per-generation cache of "coverage is from an older commit" warnings: primary vs `HEAD`, baseline vs merge-base, with behind-by counts; formats plain text and HTML for the Affected tab banner |
| `LineMappingService.kt` | Maps old coverage line numbers to current document lines via `git show` + `ComparisonManager` diff; supports both primary and baseline commits; LRU cache of file contents |
| `CoverageLineMapper.kt` | Pure functions: diff old/new content, build old→new line map |
| `CoverageDiff.kt` | Pure helpers: `featureOnly(primary, baseline)` (set-diff by test name) and `union(primary, baseline)` (distinct, primary first) |

### Rendering
| File | Purpose |
|------|---------|
| `CoverageHighlighter.kt` | Applies line-background highlighters and gutter renderers; classifies each line as `COVERED` (green) / `UNCOVERED` (red) / `FEATURE_ONLY` (blue) when baseline is present |
| `CoverageGutterRenderer.kt` | Gutter strip coloured by `CoverageCategory`; tooltip shows count of feature-only tests; click opens "Covering Line" panel |
| `CoverageEditorListener.kt` | Applies highlights on editor open; installs one document listener per file-backed editor (also before coverage is loaded) and re-applies on document change (debounced 300 ms) |
| `CoverageGutterVisibilityService.kt` | Persists gutter visible/hidden toggle across restarts; gates all loading activity when hidden |
| `CoverageUserSelectionService.kt` | Persists the pinned commit hash. While a pin is set, automatic loads (poller, retry alarm, pending reload, HEAD tracker, startup) neither call the daemon nor replace the data. Cleared by "Load Coverage from GitLab", Fetch, Clear Coverage Data, or when the pinned artifact is missing from the cache |
| `HideCoverageGutterAction.kt` / `ShowCoverageGutterAction.kt` | Toggle coverage gutter project-wide; hide also stops pipeline poller, show triggers `loadOfflineFirst()` on a pooled thread |
| `CoverageIcons.kt` | Green/red icon constants |

### Tests tool window
| File | Purpose |
|------|---------|
| `CoverageTestsToolWindowFactory.kt` | Registers "Coverage Tests" bottom tool window with **five** tabs: Covering Line / Affected by Changes / New Coverage / Artifacts / Log |
| `CoverageTestNodeData.kt` | Sealed node-payload hierarchies: `TestNodeData` for the tests tree (Dir, BehatGroup, BehatScenario, PhpUnitGroup, PhpUnitMethod) and `FileNodeData` for the files trees (Dir, FileEntry) |
| `TestTreeView.kt` | Shared test-tree widget: tree+model+renderer, double-click navigate, popup (Run / Debug / Go to), flat-vs-tree toggle, Behat/PhpUnit hierarchy builders, Run-All-bundled |
| `CoveringLinePanel.kt` | "Covering Line" tab: per-line view — tests covering the line clicked in the editor gutter; toolbar: toggleView, Run All, Run All Debug, **Both / Master Only / New on This Branch** filter (visible only in dual-coverage mode) |
| `AffectedTestsPanel.kt` | "Affected by Changes" tab: `AffectedFilesPane` + `TestTreeView` in splitter; warning banner from `CoverageWarningService`; toolbar: Find HEAD, Find Working Tree, Refresh, toggleView, Run All, Run All Debug, Remove Selected; F5 = Refresh |
| `AffectedFilesPane.kt` | Left pane of the Affected tab: `CheckboxTree` of affected files with per-node test count and `(-N)` delta, toolbar (Refresh, tree/flat toggle, expand/collapse, check/uncheck all); writes the checkbox state back into `AffectedTestsModel` |
| `NewCoveragePanel.kt` / `NewCoverageModel.kt` | "New Coverage" tab: files and lines covered only on the feature branch (primary has tests, baseline has none). Uses `git diff baseline..primary` to pick the line-mapping path for changed files and raw line comparison for unchanged ones; double-click opens the first feature-only line |
| `CoverageArtifactsPanel.kt` | Artifacts tab: table of cached COV4 files with Fetch / Load / Delete toolbar. The index is read in the background and the table is updated on the EDT (stale refreshes are dropped by a ticket); Delete runs in the background |
| `CoverageLogPanel.kt` | Log tab: `ConsoleView` + `CoverageLogService` subscription |

### Affected-tests analysis
| File | Purpose |
|------|---------|
| `ChangedLinesAnalyzer.kt` | `git diff -U0` parser → changed old-side line numbers; modes: COMMITTED / WORKING_TREE |
| `AffectedTestsService.kt` | Looks up changed lines in coverage data → `AffectedTests` result |
| `AffectedTestsModel.kt` | Pure data model for affected-files view: checked files, removed tests, delta computation |
| `CoveragePathResolver.kt` | Resolves git-root-relative path in coverage map (direct + leading-`/` variant); also `resolveBaseline()` for baseline reader |
| `ToggleAffectedFilesViewAction.kt` | Toolbar toggle: tree ↔ flat list in the files pane |

### Behat runner
| File | Purpose |
|------|---------|
| `BehatTestRunner.kt` | Runs scenarios via Behat run-configuration template; single or bundled multi-path launch; passes feature file paths as separate positional arguments (e.g. `file:1 file:2`) |
| `CoverageTestNavigator.kt` | PSI navigation to scenario; converts git-root-relative feature paths to project-relative |

### Errors & logging
| File | Purpose |
|------|---------|
| `CoverageApiException.kt` | Typed exception with `CoverageErrorKind` enum |
| `CoverageErrorReporter.kt` | IDE "Submit" error reporter |
| `CoverageLog.kt` | Logger wrapper — writes to `idea.log` **and** `CoverageLogService` |
| `CoverageLogService.kt` | Ring buffer (2000 entries) with thread-safe subscribe/backfill |
| `OpenCoveragePerfLogAction.kt` | Find Action: "Open Coverage Performance Log Folder" |
| `CoverageUiTestBridge.kt` | Test-only project service, inert unless `-Dcoverage.uiTestBridge=true`. Writes a JSON snapshot (state, commit, `baselineCommit`, `loading`, `stale`, `headHash`, `coverageGeneration`, `dumb` (indexing), `covdInstanceId`, `covdPid`, `gutterVisible`, `retryAttempt`, `retryMax`, `blocked`, `notifications`, `lastCommand`, per-line classification of `-Dcoverage.uiTestFiles`, `updatedAt`) atomically to `-Dcoverage.uiTestState=<file>` (default `~/.cache/coverage-plugin/ui-test-state.json`) so an out-of-process UI test can see what RemoteRobot cannot. Polls `-Dcoverage.uiTestCommands` every 250 ms and runs each new line once (`reload`, `settings.token=<value>` stored but never logged, `settings.apply`). Line numbers are the coverage commit's, **without** line mapping |
| `RetryPolicy.kt` | Pure failure contract (`RetryPolicy`, `RetryState`): default 5/15/45 s ladder overridable by `-Dcoverage.retry.delaysMs`, a single wait capped at 60 s, `retryAfterMs` as a floor, and the decision per daemon code (`RETRY` / `BLOCK_AUTH` / `STOP_UNTIL_CHANGE` / `NONE`). No IDE types, unit-tested directly |
| `NotReadyBackoff.kt` | Pure `not_ready` backoff: 5/10/20/40 s then the 60 s ceiling per reason, a daemon `retryAfterMs` as a floor. A different reason, `ready`, a HEAD change, a settings change or a manual load resets it. No IDE types, unit-tested directly |
| `CoverageNotifier.kt` | Project notifications in the `Coverage Notifications` group: one balloon per kind (`retry`, `auth`), actions *Retry now* / *Show log* / *Update token…*; keeps the last 20 records (title, content, type, action labels, time) for the bridge and tests |
| `CoverageUiTestCommands.kt` | Parser and offset reader for the UI-test command file: complete lines only, each run once, token values masked in `lastCommand` and the log |
| `CoveragePaths.kt` | Resolves `-Dcoverage.cacheRoot` (default `~/.cache/coverage-plugin`), `-Dcoverage.perf.dir` (default `<cacheRoot>/perf`) and `-Dcoverage.covdResponses` (off by default); the test seams of `CovdClient`/`CoverageCacheService` still win |
| `CovdResponseRecorder.kt` | Saves every `ensure`/`import` answer to `-Dcoverage.covdResponses=<dir>` as `<seq:04d>-<yyyyMMdd-HHmmss-SSS>-<kind>-<state>.json`: request fields (never the GitLab token, which is also scrubbed), HTTP status, elapsed ms and the daemon's body unmodified. Off by default; write errors only go to the log |
| `CovdProcessSampler.kt` | 1 Hz `ev=covdsample` samples (pid, RSS, CPU %, threads, VSZ) of the covd process. The daemon may belong to another IDE, so the PID comes from the handshake port via `/proc/net/tcp(6)` → socket inode → `/proc/*/fd`; Linux only, daemon thread, stopped by `CovdClient.resetForTest()` |

## Dual-coverage mode

When the current branch has its own pipeline **and** the coverage branch (master) has a different commit, the plugin loads both simultaneously:

- **Primary** = current-branch pipeline (feature or coverage-branch fallback).
- **Baseline** = coverage-branch (master) pipeline. `null` when commits are equal (single-coverage mode) or when resolution fails.

`covd` resolves both pipelines and writes two `.cov4` files; `CoverageLoadService` attaches the returned paths to `CoverageDataService` as primary and baseline readers.

**Gutter colours** (set by `CoverageHighlighter.categorizeLine`):
| Colour | Meaning |
|--------|---------|
| 🟢 Green | Line covered; all tests also exist on master |
| 🔵 Blue | Line covered; at least one test is new on this branch (`FEATURE_ONLY`) |
| 🔴 Red | Line not covered by any test |

**Test filter** (bottom "Covering Line" panel, visible only in dual mode):
| Filter | Shows |
|--------|-------|
| Both | Union of primary + baseline tests |
| Master Only | Tests that appear in both (still on master) |
| New on This Branch | Tests only in primary (feature-only diff) |

`CoverageDiff` provides the set-diff / union helpers used by both the highlighter and the panel.

The **New Coverage** tab needs a baseline: without one it shows "Dual-coverage mode is not active" and lists nothing. It compares by line, not by test name: a line is "new coverage" when primary has covering tests and baseline has none. Files changed between the two commits are compared after line mapping, unchanged files by raw line numbers.

## Flow

On startup, HEAD change, settings save, or the 30-second poll: `loadOfflineFirst()` shows any cached artifact immediately (stale), then `loadFromGitLab()` sends one `ensure` to covd. The daemon resolves the primary pipeline from the branch / HEAD SHA and, when a coverage branch is set, the baseline from that branch (merge-base first, then its ancestors; a baseline never replaces a missing primary), waits for coverage jobs, downloads the COVT shards, merges them, writes the `.cov4` files and updates the index; the plugin attaches readers to the returned paths. `not_ready` answers are retried after `retryAfterMs`; `error` answers go through `RetryPolicy`: retryable errors (including an unreachable daemon) three times, auth errors block until the settings change, deterministic errors block until HEAD or the settings change (see "Failure handling").

The daemon is shared by every IDE instance and keyed by `--cache-dir`; identical concurrent `ensure` calls are coalesced inside it. The plugin never writes the cache index and never downloads artifacts itself. All auto-loading is skipped when gutter visibility is off.

- `CovdClient` treats `connection refused` as "daemon exited after idle timeout": it starts a new process and retries the request once. The daemon is never shut down by the plugin.
- `ensure` and `import` use an 11-minute read timeout; `ping`/artifact calls use 3 seconds.

Cache layout is per component (`<cacheRoot>/<projectId>/<hash>-<component>.cov4`, cache root `~/.cache/coverage-plugin` or `-Dcoverage.cacheRoot`). On each editor open, `CoverageHighlighter` maps old line numbers to current positions via `LineMappingService` for both primary and baseline, then classifies each line.

## Failure handling (F1, F2 contract)

`RetryPolicy` classifies one daemon answer; `RetryState` holds the per-project counter and block. The contract is fixed in `docs/real-project-testing.md`.

| Answer | Behaviour |
|--------|-----------|
| `error` with `retryable=true` (`CONNECTION`, `GITLAB_API`) or an unreachable daemon | Silent retries, 3 by default, after 5 / 15 / 45 s (`-Dcoverage.retry.delaysMs` replaces them), never sooner than `retryAfterMs`, never later than 60 s. After the last one: balloon *Retry now* / *Show log* |
| `AUTH_INVALID`, `ACCESS_DENIED` | No retries. `blocked="AUTH"` for the current settings (a HEAD change does **not** lift it: the next commit gets the same 401); sticky balloon *Update token…*. Applying settings clears the block and loads again |
| `NO_COMPONENT_JOBS`, `NO_DATA`, `PROJECT_SETUP`, `GIT` | No retries, no balloon. `blocked="NON_RETRYABLE:<code>"`; the poller and HEAD tracker stay quiet until HEAD or the settings change |
| `ready` | Counter and block reset |

- A token block is tied to a SHA-256 fingerprint of the settings (token included in the hash, never logged); a deterministic-error block is also tied to the HEAD. `CoverageLoadService.isBlocked()` lifts it when what it is tied to changed.
- One balloon per kind: `CoverageNotifier` expires the previous one before showing the new one and keeps the last 20 records for the bridge.
- A manual load (`LoadCoverageAction`, `showErrors=true`, the balloon's *Retry now*) always bypasses the block and restarts the retry budget.
- Perf events: `covd.retry attempt delayMs code`, `covd.blocked reason code`, `notify.shown kind`.

## MCP

```json
{ "mcpServers": { "coverage": { "url": "http://localhost:17178/mcp" } } }
```

Tool: `get_coverage_for_file` — reads a file with per-line coverage markers. Each line includes content, line number, coverage flag, and count of covering tests. Supports `offset`/`limit` pagination and `coverage` filter (`all`/`covered`/`uncovered`). Does NOT return test names (use `get_tests_at_line` instead).

Tool: `get_tests_at_line` — returns paginated test names for a specific line. Use `offset`/`limit` (default 5, max 100) to paginate through long test lists.

Tool: `get_first_tests_at_lines` — returns the first covering test name for each of the given comma-separated line numbers. Useful for quickly checking which tests exercise which lines.

Tool: `list_files` — lists files with coverage data under a directory.

MCP calls need **two** project arguments when several projects are open: `project` (our tool resolves the IDE project from it) and `projectPath` (the IDE MCP framework routes the call with it). Without `projectPath` the framework answers "Unable to determine the target project". Endpoint in the licensed IDE is `http://127.0.0.1:64343` (`/sse` + `/message`).

## Coverage daemon (covd)

Artifact processing runs in the `covd` daemon from the coverage-assistant repository:
it resolves the pipeline in GitLab, downloads the COVT shards, merges them and writes the
`.cov4` cache, so none of that work touches the IDE JVM.

- Binaries are built there with `make build-covd-all` (`dist/covd/covd-<os>-<arch>[.exe]`) and
  **committed here** under `src/main/resources/bin/<platform>/covd[.exe]` (platforms `linux-x64`,
  `linux-arm64`, `mac-x64`, `mac-arm64`, `win-x64`, ~37 MB together), so every build, CI included,
  ships a daemon. Refresh them with `scripts/update-covd.sh [dist-dir]` (it checks the dist
  `SHA256SUMS`, renames the files and rewrites `covd-manifest.properties` with the covd version and the
  SHA-256 of each binary) and commit the result alone as `Update covd to <version>`: every update adds
  ~37 MB to the history. The `verifyCovdBinaries` task (a dependency of `buildPlugin`, `prepareSandbox`
  and `check`) fails the build when a binary is missing, too small, not an executable of its platform or
  does not match the manifest; `BundledCovdTest` checks the same on the classpath.
  `-PcovdDist=/path/to/dist/covd` (or `COVD_DIST`) overrides the committed binaries for a local
  experiment with an unreleased covd (the manifest check is skipped then).
- `CovdClient` extracts the binary to `~/.cache/coverage-plugin/bin-covd-<sha256 prefix>/` (named after the
  content, so an updated plugin never reuses an older covd; written through a temp file and an atomic move,
  a file of the wrong size is replaced), starts it
  with `--cache-dir ~/.cache/coverage-plugin` (`-Dcoverage.cacheRoot` replaces both paths), and reads the single stdout handshake line
  (`{"covdReady":{port,token,instanceId}}`). It never calls `/v1/shutdown`: the daemon is
  shared by all IDE instances and exits on its idle timeout.
- `loadWithCovd` posts `/v1/ensure` with `gitRoot`, `component`, `pathMode`, `jobPrefixes`
  (from `ComponentConfig`) and the GitLab settings; the token travels in the request body and
  is never stored. `ready` → attach `Cov4Reader` to the returned `cov4Path`; `not_ready` →
  retry after `retryAfterMs`; `error` → report `code`/`hint`.
- The daemon owns `<cache>/<projectId>/cache-index.json` and the `.cov4` files:
  `CoverageCacheService` is read-only and deletions go through `DELETE /v1/artifacts`.
- The daemon is a hard dependency: without a working binary the plugin reports "coverage
  daemon unavailable" and loads nothing. There is no in-process fallback any more.
- Local `.covt` import: `POST /v1/import` with `{covtPath, projectId, component, pathMode,
  gitRoot}`; the commit key is the SHA-256 of the file content, so re-importing the same file
  reuses one cache entry.

## Build & Run

```bash
./gradlew compileKotlin            # compile
./gradlew test                     # unit tests (excludes the heavy package)
./gradlew heavyTest                # heavy integration tests inside a full IDE process
GITLAB_TOKEN_FROM_IDE=1 ./scripts/ui-test.sh   # real-IDE UI scenario (see "UI tests" below)
./gradlew buildPlugin              # distributable zip
./gradlew runIde                   # sandbox IDE
./gradlew generateUpdatePluginsXml # regenerate docs/updatePlugins.xml
```

- `heavyTest` is an `intellijPlatformTesting.testIde` task: it launches the real IDE
  ($platformVersion) with the plugin installed and runs
  `com.github.yakov255.perlinecoverageinfo.heavy.*` (`BasePlatformTestCase`).
  `test` filters that package out, so the default suite stays fast.
- **covd integration tests** (`src/test/kotlin/.../covd/`, run by plain `./gradlew test`): the real
  `covd` binary against `FakeGitLab` (a JDK `HttpServer` serving pipelines, jobs, the artifact and
  `merge_base`) with a temporary git repository and `--cache-dir`. `CovdTestBase` finds the binary
  in `-Dcovd.dist` / `COVD_DIST` / the sibling `coverage-assistant/dist/covd`, then falls back to the
  committed `src/main/resources/bin/<platform>/covd`, so these tests do not skip. `CovdFakeDaemonContractTest` needs no binary: a fake daemon behind a shell
  wrapper checks handshake, auth header, restart and status handling. Fixtures:
  `src/test/resources/covd/`. `heavy.HeavyCovdLoadTest` drives `CoverageLoadService` end to end
  through the same pieces inside the IDE (the heavyTest classpath has no test resources, so its
  COVT fixtures are embedded as base64). `CoverageApiSettings.gitlabDomain` accepts an explicit
  `http://host:port` for such tests. The default domain is `raketa.dev` (GitLab's `external_url`, reachable
  from any network); the internal `gitlab.raketa.in` answers only from the office network.
- `heavy.HeavyCovdFailureTest` covers the failure contract inside the IDE: `AUTH_INVALID` blocks
  without retries and a new token loads through the shared Apply path; `NO_COMPONENT_JOBS` stops the
  poller until HEAD moves; a retryable error runs the 1+3 ladder (short delays from
  `-Dcoverage.retry.delaysMs`), shows the balloon and succeeds after *Retry now*. Token and
  deterministic codes use the real daemon + `FakeGitLab`; the retry ladder uses a fake daemon behind
  a shell wrapper.
- `testIde` has its own platform configuration: it does not inherit the project-level
  `platformBundledPlugins`. `heavyTest` therefore declares them again, plus the plugins and
  modules PHP/Gherkin depend on (`org.intellij.intelliLang`, `com.intellij.modules.json`,
  `com.intellij.modules.ultimate`). Without `com.intellij.modules.ultimate` the plugin is
  rejected with "requires plugin com.jetbrains.php to be enabled".
- `heavyTest` sets `-Didea.home.path` to the resolved platform, otherwise the platform treats
  itself as Community. The path is resolved at configuration time because the platform-path
  provider cannot be serialized into a `CommandLineArgumentProvider` (configuration cache).
- Do not assert `ToolWindowManager.getToolWindow(...)` in these tests: a light fixture project
  has no registered tool windows. Assert the plugin is loaded and the `ToolWindowEP` is
  registered instead.
- A manual `runIde` uses `.intellijPlatform/sandbox/per-line-coverage-info/<version>/`. If
  `config/disabled_plugins.txt` lists `com.intellij.modules.ultimate`, PHP and this plugin are
  rejected ("requires plugin com.jetbrains.php to be enabled"); `syncIdeLicense` removes that
  line.
- `runIde` keeps the IDE window open until it is closed, so a scripted check must stop the
  process itself. `scripts/smoke-ide.sh` does that: it runs `./gradlew runIde`, waits for
  `Loaded custom plugins: ... per-line-coverage-info`, then kills the sandbox IDE JVM and
  returns 0/1.
- The sandbox IDE has no license, and without one JetBrains Ultimate (and therefore PHP) is
  disabled. `syncIdeLicense` copies `idea.key`/`phpstorm.key`/`plugin_PCWMP.license` from the
  newest local `~/.config/JetBrains/IntelliJIdea*` into the sandbox config before `runIde` and
  `runIdeForUiTests`. The license is never committed; without a local key the task only logs
  and the IDE starts unlicensed.
- Machine-local extra JVM arguments for the sandbox IDE (a javaagent, `--add-opens`) live in
  `~/.gradle/gradle.properties` as `ideaSandboxJvmArgs=...` (space-separated) or in the
  `IDEA_SANDBOX_JVM_ARGS` environment variable — never in the repository. `runIde`,
  `runIdeForUiTests` and `heavyTest` append them to the forked IDE JVM. An agent that hides
  itself from `RuntimeMXBean.getInputArguments()` (e.g. ja-netfilter) never shows up in the
  `JVM options:` line of `idea.log`; verify it in the IDE process command line
  (`/proc/<pid>/cmdline`) or by its banner in the Gradle output instead. Full guide:
  `docs/local-java-agent.md`.
- **UI tests** (`src/uiTest`, RemoteRobot 0.11.23, JUnit 5): the scenario drives a real sandbox IDE
  (`runIdeForUiTests`) against real GitLab: first-run token dialog → Settings → Tools → GitLab
  Coverage → project search → Apply → coverage loaded (checked through `CoverageUiTestBridge`:
  fresh, not stale) → "Coverage Tests" window with five tabs → Artifacts table → open the PHP
  fixture → click the gutter → "Covering Line". `scripts/ui-test.sh` starts the IDE, waits for the
  plugin log line **and** the robot port, runs `./gradlew uiTest`, and stops only its own IDE.
  - Token: `GITLAB_TOKEN_FILE`, `GITLAB_TOKEN` (copied to a mode-600 temp file) or the explicit
    opt-in `GITLAB_TOKEN_FROM_IDE=1`. The test reads it from the file, so it is not in the
    environment. The token is typed into plain text fields: the saved hierarchy is masked, but a
    failure screenshot (`build/uiTest-artifacts/*.png`) may show it, so do not publish artifacts.
  - Knobs: `-PuiTestRobotPort` (`ROBOT_PORT`, default 8082), `-PuiTestPhpFile` (`UI_TEST_PHP_FILE`),
    `UI_TEST_PROJECT` (default `$HOME/projects/raketa`). The IDE runs with `-Xmx4g` and
    `-XX:-HeapDumpOnOutOfMemoryError` (a heap dump written into the unpacked platform makes Gradle
    reject that immutable directory) and `-Didea.log.path` pointing at the sandbox
    `log_runIdeForUiTests`, which is also how the script finds and stops that IDE.
  - The scenario requires a **fresh** (non-stale) load. If HEAD of the opened project has no pipeline
    yet, covd answers `not_ready (no_pipeline_yet)` and only the cached artifact is shown (`stale`):
    the test then fails with an explanation. `UI_TEST_ALLOW_STALE=1` accepts the cache knowingly (the
    GitLab download itself is then not exercised).
  - `uiTestSandbox` wipes the sandbox config/system/log and the snapshot file before each run.
  - The fixture must be unchanged between the coverage commit and the working tree (the bridge
    does no line mapping); the scenario checks that with `git diff`.
  - Failures save `build/uiTest-artifacts/` (screenshot, masked Swing hierarchy, idea.log, state),
    including failures in the `@BeforeAll` setup. Look locators up in that hierarchy dump
    (`accessiblename` carries labels; dialogs are `MyDialog`; the new UI tool window stripe has
    icons only, so open windows by action id).
  - Not covered: Affected / New Coverage tabs beyond their presence, dual-coverage filters.
  - `uiTest` is a plain `Test` task and uses `--add-opens java.base/java.lang` so RemoteRobot's
    Gson can report the IDE-side error instead of `InaccessibleObjectException`.
- `covd` binaries are committed (see "Coverage daemon (covd)"); the plugin zip carries all five.
  Release zips up to 2.7.1 were built without them (611 KB) and could load nothing.
- Real end-to-end check against `gitlab.raketa.in` (project 335): install `buildPlugin`'s zip
  into a licensed IDE's user plugins directory (`~/.local/share/JetBrains/PhpStorm2026.2/per-line-coverage-info`),
  open `~/projects/raketa` and query MCP on `http://127.0.0.1:64343`. Alternatively run
  `build/covd-resources/bin/linux-x64/covd --cache-dir /tmp/covd` and `POST /v1/ensure` with
  `baseUrl=https://gitlab.raketa.in`, `projectId=335`, `component=raketa`, `pathMode=raketa`,
  `jobPrefixes` from `ComponentConfig`: the branch pipeline (primary) and master (baseline)
  resolve in about 72 s, ~21.7k files / 428k lines.

## Performance diagnostics

`CoveragePerfLog` writes structured `key=value` lines to
`~/.cache/coverage-plugin/perf/run-<timestamp>-<pid>.log` (separate from `idea.log` and the
Log tab):

- `ev=span` — wall time, current-thread CPU, bytes allocated by the thread, heap before/after,
  GC count/ms delta, plus caller-provided details, with a `ph=start`/`ph=end` pair and nesting depth.
- `ev=counter` — aggregated count/avg/max/total for high-frequency work, flushed once per second.
- `ev=sample` — 1 Hz samples: heap used/committed/max, non-heap, threads, process CPU %, load
  average, GC deltas, JVM uptime.
- `ev=covdsample` — 1 Hz while a daemon is known: `pid rssMb cpuPct threads vszMb` of the covd
  process (another IDE's child included; resolved through `/proc` by the handshake port).
- `covd.response` events — per `ensure`/`import` answer: `kind seq state code commit cached
  replaced stale joinedFlight requestId httpStatus elapsedMs` plus the daemon's `totalMs
  resolveMs downloadMs parseMs mergeMs writeMs baselineMs gitlabCalls gitlabBytes gitlabErrors`.
- `covd.spawn` events — how the connection was obtained (`how=spawn|reuse|restart`), `pid port
  instance startMs`.

Enabled by default; `-Dcoverage.perf.log=false` disables it and Gradle sets it for unit tests.
Find Action **Open Coverage Performance Log Folder** reveals the directory. Instrumented:
`load.offlineFirst`, `covd.ensure`/`covd.ready`/`covd.notReady`/`covd.error`, `covd.retry` (attempt, delayMs, code), `covd.blocked` (reason), `notify.shown` (kind), `poll.tick`, `cov4.open`, `highlight.*`, `linemap.gitShow`, and `plugin.start` (JVM args, `-Xmx`).
Artifact parsing, merging and writing are measured by covd's own trace, not here.

Diagnostics properties (contract: `docs/real-project-testing.md`): `-Dcoverage.cacheRoot=<dir>`
moves the covd cache and the extracted binary (the daemon is shared per cache root),
`-Dcoverage.perf.dir=<dir>` moves the perf log, `-Dcoverage.covdResponses=<dir>` saves the raw
daemon answers, `-Dcoverage.retry.delaysMs=500,1500,4500` replaces the retry ladder (test hook)
and `-Dcoverage.uiTestCommands=<file>` feeds `reload` / `settings.token=` / `settings.apply` to the
bridge (test hook), `-Dcoverage.tokenStore=memory` keeps the GitLab token in the process instead of `PasswordSafe` (test escape hatch). All are off by default; a normal run never sets them.

## Release

Version lives in `gradle.properties` (`pluginVersion`, SemVer) — single source of truth.

Two-commit flow:
1. Work commit(s) add user-visible entries under `## [Unreleased]` in `CHANGELOG.md`.
2. Release commit titled `Release X.Y.Z`:
   - bump `pluginVersion` in `gradle.properties`;
   - move the `[Unreleased]` entries under `## [X.Y.Z] - YYYY-MM-DD`, leaving `[Unreleased]` empty;
   - regenerate `docs/updatePlugins.xml` (`./gradlew generateUpdatePluginsXml`).

Pushing to `main` triggers `.github/workflows/build.yml`, which runs `./gradlew buildPlugin`, creates a GitHub release `X.Y.Z` with the `per-line-coverage-info-X.Y.Z.zip` asset and notes from `getChangelog`, then regenerates and commits `docs/updatePlugins.xml`.

Rules:
- `.githooks/pre-commit` (enabled via `core.hooksPath`) **fails** if the version is bumped while `[Unreleased]` still has entries, and always regenerates and stages `docs/updatePlugins.xml`.
- Never hand-edit `docs/updatePlugins.xml` — it is generated.
- `docs/updatePlugins.xml` is the custom plugin update manifest served to users.

## Conventions

- **Logging**: `private val log = CoverageLog.get(Foo::class.java)` — never `Logger.getInstance` directly.
- **Paths**: canonical key is git-root-relative (e.g. `api/hotels/src/Foo.php`). `LineMappingService.toGitRelativePath` converts project-relative → git-relative. Feature paths in test names are also git-root-relative; strip with `CoverageTestNavigator.toProjectRelativeFeaturePath`.
- **Line numbers**: 1-based throughout. `git show` is read raw (`GitCommand.runRaw`); content comparison in `CoverageLineMapper` ignores line separators (CRLF/CR vs LF).
- **Cache key**: commit hash + component (not pipeline ID). Files: `<hash>-<component>.cov4` under `<cacheRoot>/<projectId>/` (`~/.cache/coverage-plugin` unless `-Dcoverage.cacheRoot`).
- **Daemon-only pipeline**: the plugin never talks to GitLab pipelines or artifacts; every load goes through `CovdClient.ensure()`. `ComponentConfig.detectComponent()` supplies `component` and `jobPrefixes` for that request.
- **Coverage CI job prefixes**: coverage archives are published by `test:behat:coverage:<jobName>` jobs; the daemon gets both prefixes (`coverageJobPrefix` and the legacy `jobPrefix`) and prefers the dedicated one. `ComponentEntry.jobName` (`hotels`) only builds these prefixes.
- **Component key**: the `component` sent to covd, used in cache file names (`<sha>-api_hotels.cov4`), `cache-index.json`, the pin and the Artifacts table is `ComponentEntry.serviceDir` (`api/hotels`, `raketa`), not `jobName`. covd treats it as an opaque cache key and accepts slashes (it maps them to `_` in file names); it never picks jobs by it. `serviceDir` values must stay unique after `/` → `_` (`ComponentKeyTest`). Only raketa has `serviceDir == jobName`, so `component == "raketa"` checks (path mode, path normalizer) are safe
- **Raketa coverage paths**: the daemon normalizes them (`pathMode: "raketa"`); the plugin still normalizes while reading pre-upgrade caches. If `SERVICE_DIR` changes in the raketa CI, update both `coverage-assistant/internal/linecov/normalize.go` and `CoveragePathNormalizer`.
- **Waiting and fallback** live in covd: it waits for running coverage jobs, serves the newest older ready pipeline as `stale` while the latest is unfinished, and reports `not_ready` with `reason`/`retryAfterMs` (the plugin retries with `NotReadyBackoff`, 5 s → 60 s per reason; `retryAfterMs` is a floor).
- **Artifact metadata**: each cache entry stores `component` (String) and `branch` (String?) for display in the Artifacts table.
- **No coroutine suspension**: the plugin is compiled with Kotlin 2.3 (coroutine debug metadata version 2) but the platform stdlib (2025.2) checks for version 1, and the IDE installs coroutine debug probes. The first time a plugin coroutine is *resumed* (`withContext`, `launch`, `async`, `delay`, `Dispatchers.*`) the probes throw `Debug metadata version mismatch` and the work silently never happens (the startup load did not start at all). A `suspend fun` without a real suspension point is harmless (`ProjectActivity.execute`, the MCP tools); use `invokeLater`, `executeOnPooledThread` and callbacks. `NoCoroutineSuspensionTest` fails when the compiled plugin contains a coroutine state machine.
- **Threading**: `ensure` runs on a pooled thread; daemon answers are applied to data services there and UI updates go through `invokeLater`. Nothing that blocks (git, the cache index, opening a COV4 reader, `CovdClient`, `PasswordSafe`) runs on the EDT, actions and panel handlers included; subscribe to the message bus with a parent disposable.
- **Errors**: daemon `code`/`cause`/`hint` drive the user-facing message in `CoverageLoadService`; auto-triggered callers only log. Retry/block decisions live in `RetryPolicy` only — do not branch on codes elsewhere.
- **Failure state**: `RetryState` is keyed by a settings fingerprint (and by HEAD for deterministic errors); `CoverageHeadTracker.onHeadChanged()` resets everything except a token block, `CoverageSettingsActions.afterSettingsApplied()` resets all of it. The poller and auto-loads must call `CoverageLoadService.isBlocked()`; a manual load passes `bypassBlock = true`.
- **Pin**: the pin protects the shown artifact from every automatic path. Automatic callers use `CoverageLoadService.isPinned()` together with `isBlocked()`. Only explicit user actions (`LoadCoverageAction`, Fetch, Clear) call `clearPin()`; a local `.covt` import and "Load" of an artifact set the pin.
- **Stale answers**: `CoverageLoadService` remembers `currentHead()` before `ensure`. Never treat a response as fresh, and never reset `RetryState` for it, when HEAD moved meanwhile.
- **Data service**: change `CoverageDataService` state only through its snapshot update (`update { }` / `setCoverage`); do not add separate `@Volatile` fields.
- **Token**: read and write it only through `CoverageApiSettings.bearerToken`; `TokenStore`/`TokenCache` own the credential store and keep every `PasswordSafe` call off the EDT. Never log or display a token, and never add a second plain-text copy to `State`.
- **Enabled flag**: `CoverageApiSettings.enabled` — checked in `validateSettings()` before every load.
- **Gutter visibility gating**: when `CoverageGutterVisibilityService.visible` is `false`, all loading activity is suppressed — `loadOfflineFirst()`, `loadFromGitLab()`, `CoverageHeadTracker`, `CoverageStartupActivity`, `CoveragePipelinePoller` all skip/stop. Showing the gutter resumes normal behavior via `loadOfflineFirst()`.
- **Dual coverage**: `CoverageDataService` holds primary + baseline `Cov4Reader`. Baseline is `null` in single-coverage mode. `CoverageDiff.featureOnly(primary, baseline)` computes the per-line blue set. `CoverageHighlighter.categorizeLine(primary, baseline, hasBaseline)` is the authoritative classifier for gutter colour.
- **HEAD tracker**: `CoverageHeadTracker` skits the initial `null → revision` event to avoid duplicating `CoverageStartupActivity`.
- **Behat paths**: `BehatTestRunner.runMultiplePaths()` passes feature file paths as separate positional arguments (e.g. `file:1 file:2 file:3`), not as comma-separated lines with `--paths` option.
