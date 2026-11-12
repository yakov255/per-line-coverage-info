<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# per-line-coverage-info Changelog

## [Unreleased]

- Use `raketa.dev` as the default GitLab domain again: it is the address every user can reach, while the internal `gitlab.raketa.in` resolves to an office-only address

## [2.8.0] - 2026-11-11

- The IDE no longer freezes for seconds when you show the coverage gutter or delete an artifact: git, the cache and the coverage daemon are used in the background
- The Artifacts tab refreshes without UI glitches when a load finishes in the background
- The first-run GitLab token dialog appears once per IDE start, even when several projects open together
- A branch switch made outside the IDE (a linked worktree, a terminal checkout) is noticed within about 30 seconds at most, and commits of a submodule no longer trigger needless coverage reloads
- Editors restored at startup are re-highlighted after you edit them, even if the coverage was not loaded yet when they opened
- Keep the GitLab access token in the IDE credential store (PasswordSafe) instead of plain text in the settings XML; a token left by an older version is moved automatically on the first start
- Stop syncing the plugin settings between machines (roaming is disabled), because the token lives in the local credential store
- Make the token field a password field: the stored token is not displayed, and a new "Remove the stored token when applied" checkbox deletes it
- Build the token link and the project search from the same base URL as the loader, so a GitLab with an explicit `http://host:port` domain works everywhere
- Fix the default GitLab domain to `gitlab.raketa.in` instead of the external URL `raketa.dev`
- Rename the enable checkbox to "Enable the coverage plugin for this IDE installation": it is an IDE-wide switch, not a per-project one
- Coverage no longer breaks when the cache file is deleted or damaged by another IDE or an interrupted write: the affected files simply show no coverage instead of failing the whole highlighting
- A long GitLab download inside the coverage daemon is no longer restarted after a timeout, so it cannot take twice as long or leave a second daemon process behind
- Offline coverage is taken from the artifact of the current component, not from one cached for another component
- Line mapping no longer diffs every file on every highlight when the file is unchanged, and files with Windows line endings map correctly
- Coverage daemon start errors (for example a locked cache directory) are reported with their own reason instead of a generic "daemon unavailable"
- The daemon token is no longer written to `idea.log` or the Log tab when a handshake is malformed
- While a CI coverage job is running the plugin asks again after 5, 10, 20, 40 seconds (then every minute) instead of every second
- A pinned coverage artifact (chosen in Artifacts or imported from a local .covt file) is no longer replaced by the 30-second refresh or by automatic retries; "Load Coverage from GitLab" and "Fetch" lift the pin and load the current commit
- "Clear Coverage Data" now stays cleared: it stops the refresh timer and lifts the pin; coverage comes back on the next commit change, a settings change or a manual load
- A coverage answer that arrives after you switched commits is shown as outdated and the coverage of the new commit is loaded right away, instead of being presented as up to date
- Fewer rare glitches with simultaneous loads and editor repaints: the coverage state is now updated atomically
- Bundle the covd daemon for all five platforms in the plugin zip: earlier release builds shipped without it and could not load any coverage ("daemon unavailable"); the build now fails when a binary is missing
- Keep an updated plugin from running the daemon binary that an older version extracted: the binary is extracted to a directory named after its content, through an atomic move, so a half-written file is never used
- Retry a coverage load that fails on a transient daemon or GitLab error three times (5 s / 15 s / 45 s, never sooner than the daemon asks) and then show a "Coverage Notifications" balloon with *Retry now* and *Show log*; a new balloon replaces the old one
- Stop retrying an expired or invalid GitLab token: a sticky balloon offers *Update token…*, the 30 s poller and the HEAD tracker stay quiet until the token, the domain or the HEAD changes, and applying the settings loads again without an IDE restart
- Stop asking GitLab every 30 seconds for a deterministic failure (`NO_COMPONENT_JOBS`, `NO_DATA`, `PROJECT_SETUP`, `GIT`): the poller waits for a new HEAD or changed settings, and a manual *Load Coverage from GitLab* always bypasses the block
- Add test/diagnostics knobs `-Dcoverage.retry.delaysMs=500,1500,4500` and `-Dcoverage.uiTestCommands=<file>`; the UI-test snapshot reports `retryAttempt`, `retryMax`, `blocked`, `notifications` and `lastCommand`
- Add real-project diagnostics: `-Dcoverage.cacheRoot` and `-Dcoverage.perf.dir` relocate the covd cache and the perf log, `-Dcoverage.covdResponses=<dir>` saves every `ensure`/`import` answer (never the token), the daemon trace now keeps its GitLab calls and errors, the perf log gains `covd.response`/`covd.spawn`/`ev=covdsample` (pid, RSS, CPU, threads, VSZ of the daemon), and the UI-test snapshot reports `loading`, `coverageGeneration`, `dumb`, `covdInstanceId` and `covdPid`
- Start the covd daemon once when several coverage requests arrive together (a cold client used to spawn one process per caller), and restart it once, not once per caller, after it exits
- Handle daemon answers by HTTP status: an error without a `state` (for example an expired daemon token) is reported as an error instead of an empty state, and a non-JSON reply from a proxy is reported as "daemon unavailable" instead of failing with a parser exception
- Accept an explicit scheme in the GitLab domain setting (`http://host:8080`) for GitLab instances without TLS; a bare domain still means `https://`
- Fix the GitLab project list in Settings → Tools → GitLab Coverage: project paths were always empty (snake_case fields were not mapped), so rows were blank and search by path found nothing
- Retry the project list request on timeouts and `5xx` answers (3 attempts with a pause) and show the real failure reason in the dialog instead of an empty list
- Load coverage through the covd daemon: pipeline resolution, artifact download and COV4 building happen outside the IDE process and are shared by all IDE windows; the daemon is required, the plugin no longer downloads or parses artifacts itself
- Import local `.covt`/`.covt.gz` files through the daemon, so the plugin no longer carries a COVT parser, shard merger or COV4 writer
- Reduce memory and CPU overhead when processing coverage artifacts: decode shared test sets once and normalize each unique test name once per report
- Reuse the decoded test set when reading cached COV4 data, so opening a file no longer allocates one test list per covered line
- Add structured performance logging (heap, per-thread allocations, CPU, GC per phase) with a Find Action to reveal the log folder; disable with `-Dcoverage.perf.log=false`
- Reuse a cached master (baseline) coverage artifact when the current pipeline has no jobs for the component, instead of re-downloading and rewriting it on every refresh
- Speed up large coverage loads: merge shards in one pass, write COV4 sets as int arrays, and open the cache index without reading the whole file

## [2.7.2] - 2026-11-05
- Fix raketa coverage paths after the `core` → `app/raketa` move: strip the new `app/raketa/web/` CI prefix (legacy `core/web/` still supported)
- Restore the `features/` segment in raketa behat test paths so test navigation and run work again
- Update the bundled PHP coverage extension to the v2 repo-relative contract (`perLine.repoRoot`)
- Normalize old `.cov4` caches on read (paths and test names), so existing coverage keeps working after the format change without re-downloading artifacts
- Remove the `<until-build>` cap so the plugin stays installable on future IDE versions (required for `since-build` 243+)

## [2.7.1] - 2026-10-02
- Fix coverage download after the CI refactor: use the dedicated `test:behat:coverage:<component>` jobs (coverage-test stage) instead of the legacy `test:behat:<component>` jobs, which no longer publish a coverage archive
- Only jobs with a real archive artifact are treated as coverage jobs (behat jobs always have trace/junit artifacts, which previously masked the missing coverage report)

## [2.7.0] - 2026-08-17
- Shared app-level GitLab coordinator: single worker thread, TTL-cached metadata and a rate limiter — dramatically fewer API requests
- Pipeline resolution memoized per repository/HEAD; refresh skipped within a 60s cooldown (zero requests on branch switches with cached coverage)
- Identical artifact downloads coalesced across project windows
- Pipeline polling interval reduced from 5s to 30s; polls share the cached API so N windows cost one network request
- `Cov4Reader` instances shared across windows (large files read once per commit)
- Disk cache writes synchronized for safety with multiple windows
- Retry HTTP 429 honouring `Retry-After`
- Send plugin name and version as `User-Agent` so requests are identifiable in GitLab server logs
- Visibility logging: cache hits/misses, rate-limiter waits, cross-window coalescing

## [2.6.0] - 2026-07-10
- Component-based coverage: separate `.cov4` per component, keyed by commit+component
- Component config with 10 components (api/avia, raketa, api/hotels, api/transfer, api/rail, api/profile-sync, api/queue, api/aeroexpress, api/contract, api/bus)
- Filter CI jobs by detected component; wait for running behat jobs (infinite, cancel via progress or branch switch)
- Fallback to baseline (master) pipeline when current branch has no jobs for the component
- Branch name stored per artifact; shown in Artifacts table
- Cache cleanup runs on every `writeCov4`; entries expire by `lastUsedMs` instead of `timestampMs`
- Reuse preloaded baseline reader to avoid redundant `.cov4` opens
- Skip initial HEAD reload in `CoverageHeadTracker` (avoids duplicate load at startup)
- Auto-refresh Artifacts panel on coverage change via `COVERAGE_CHANGED_TOPIC`
- `pluginUntilBuild` extended to `299.*`
- Simplify CI: build + release only, no tests/Qodana/Verifier/Marketplace
- Auto-create GitHub release with `.zip` asset on push to main (if version changed)
- Pre-commit hook to auto-generate `docs/updatePlugins.xml`
- Rewrite README for in-house Raketa usage

## [2.5.1] - 2026-07-13
- Show startup dialog to configure GitLab token when not set
- Normalize raketta coverage paths: strip `core/web/` prefix when downloading artifacts
- Replace coverage column with separate Покрыто/Не покрыто/Всего строк columns in artifacts table
- Add pre-commit hook to auto-generate `docs/updatePlugins.xml`
- Rewrite README for in-house Raketa usage

## [2.5.0] - 2026-06-05
- MCP: new `get_tests_at_line` tool with paginated test listing for a specific line
- MCP: new `get_first_tests_at_lines` tool — quickly check which test covers each line
- MCP: `get_coverage_for_file` now returns per-line content with pagination (`offset`/`limit`) and `coverage` filter
- MCP: `list_files` gains `coverage` filter (`all`/`uncovered`/`fully_covered`)
- Suppress progress indicator during pipeline resolution — only show status bar when actually downloading artifacts
- Simplify loading pipeline: unified download flow, removed dead code (`refreshBaselineFromCache`, `applyCoverageFromReader`)
- Fix double-close of `Cov4Reader` in post-download apply path

## [2.4.0] - 2026-06-04
- Stop all loading activity (GitLab downloads, pipeline polling, HEAD tracking, startup loading) when coverage gutter is hidden — resume when shown again

## [2.3.1] - 2026-06-04
- Switch to standard IntelliJ custom plugin repository for updates
- Pipeline polling for automatic coverage refresh
- Show report merging progress in the progress bar
- Show download speed and per-job byte progress in progress bar
- Set-based deduplication when merging coverage reports
- Queue reload when coverage load is triggered during an in-progress load
- Suppress misleading "0 commit(s) behind" warning
- Migrate MCP server to JetBrains MCP framework
- Add detailed logging across coverage pipeline
- Remove coverage status notification popups
- Fix EDT violations: move I/O off EDT, sync LRU cache, wrap Swing subscribers
- Fix "no coverage" display after refresh

## [2.3.0] - 2026-05-20
- Dual coverage diff view: show only new coverage
- MCP toolset: coverage access for LLM, list_files tool, detail parameter
- Async coverage artifact download
- Detect expired artifacts
- Speed up artifacts downloading
- Coverage auto-updating after settings save
- Warning if coverage is stale
- Loading optimization
- Show tab content during indexing
- No fallback to configured target branch
- Split affected tests and covered line tests
- Download current branch coverage

## [2.2.0] - 2026-04-14
- Embedded MCP server for LLM coverage access
- Auto-reload coverage on any HEAD change, not just branch switch
- Move coverage actions from Tools menu to Artifacts panel
- Artifacts tab in Coverage Tests tool window
- Allow all coverage actions during IDE indexing (dumb mode)
- Auto load coverage on start
- Select coverage dialog and saved selected artifact
- Store artifact usage history (last used artifact)
- Load from file now saves to cache and appears in artifacts list
- Handle GitLab token expired error
- Fix path resolution
- Many UI/UX improvements

## [2.1.0] - 2026-04-09
- Offline-first coverage loading
- Replace raw .covt cache with indexed COV4 binary format
- Add auto-refresh, disk cache, and local file loading
- Filter artifact downloads to behat jobs only
- Show Coverage gutter context menu action
- Reduce RAM on cache download
- Improve cache handling

## [2.0.0] - 2026-04-06
- Migrate plugin from custom API to GitLab coverage artifacts
- Three-tier merge-base resolution with GitLab API fallback
- Show covered test count
- Replace project search with load-all + live filter
- Fix project search: move HTTP call off EDT to background thread
- Fix coverage artifact download to match real GitLab behavior
- Improve error dialogs with detailed diagnostic info

## [1.0.0] - 2026-04-01
- Initial release
- Basic per-line coverage display in editor gutter
