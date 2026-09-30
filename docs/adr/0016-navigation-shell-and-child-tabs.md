# ADR 0016: App navigation shell and child tab structure

## Scope and sources

EH-T1, based on E2-T4 863c89f on codex/eh-t1-navigation-shell.
Sources: complete PRD v2.2 sections 3 (child UX), 7 (parent entry),
8.6–8.7 (tab layout); ADR 0009 (profile management),
ADR 0015 (video detail dual-view); existing ProfilesViewModel / PIN gate.
No new Room schema change. No new feature screens are implemented.

## Problem

Before EH-T1 the app used a flat single-NavController graph rooted at
`onboarding` with all routes as peers. The video detail dual-view task
(E2-T4) required sibling child and parent-video routes but the deeper
need—a persistent bottom-tab shell that retains per-tab scroll and filter
state across profile switches, exposes immersive routes without a bottom
bar, and guards the parent console behind a PIN gate—was deferred to EH.

The prior implementation also mixed onboarding, profile-selection and
content navigation in one graph, making state restoration semantics
ambiguous and the PIN authorization flag reachable through saved-state
restoration.

## Decisions

### 1  Two-layer gate: OnboardingPhase before NavHost construction

The onboarding gate is evaluated before any NavHost is created. Only when
`OnboardingPhase.COMPLETE` is confirmed does the shell branch compose a
NavigationScopeViewModel and ultimately a ChildShell. This means:

- A saved back-stack cannot bypass durable initialization.
- A restored authorization flag in nav-arguments cannot skip the PIN.
- `EnterHome` effects cannot pop a restored player or parent back stack.

The gate is a plain `if/else` on `state.phase`, not a route or argument.

### 2  ProfileNavigationScope owns cross-profile state isolation

`ProfileNavigationScope` wraps a `rememberSaveableStateHolder` keyed by
the current profile's scope string. When the scope changes the previous
profile's SaveableStateProvider is removed, clearing all tab scroll /
filter positions. When the same scope returns (refresh without profile
switch) state is preserved. When scope becomes null the shell unmounts
entirely without side effects.

This separates profile-switch semantics from navigation semantics:
navigation does not need to know about profiles; scope transitions do not
require graph-level popUpTo calls.

### 3  ChildShell owns a single NavController for tabs + overlays

All child destinations share one `NavController` inside `ChildShell`.
Tabs use `findStartDestination` + `saveState/restoreState`; overlay routes
(profiles, parent, import, video, repair, player, dictionary) are pushed on
top without save-state. The `TabMemory` (LazyListState + filter String)
lives in the tab's own back-stack entry scope via `rememberSaveableStateHolder`;
it is not passed through ViewModel or navigation arguments.

The bottom navigation bar is shown only when the current back-stack entry
matches a `ChildTab.route`. All other routes are implicitly immersive.

Alternatives considered:
- Separate NavController per tab (used by large-scale apps): adds
  complexity for back-press handling and cross-tab deep links; rejected for
  a 4-tab MVP shell.
- Storing filter/scroll in ViewModel: over-engineering for state whose
  lifetime matches one profile session and one process; rejected.

### 4  Deep-link timing: await first back-stack entry before routing

External `lazyeng://app/...` links arrive either as the initial
`intent.dataString` (cold start) or via `onNewIntent` (warm).

In both cases `pendingLink` is held as Activity-level `mutableStateOf` and
saved/restored across process death via `onSaveInstanceState`. Inside
`ChildShell` a `LaunchedEffect(pendingLink)` processes the link only after
`navigation.currentBackStackEntryFlow.first()` resolves, guaranteeing that
NavHost has built its graph and pushed the HOME start destination before
any `navigate()` call is issued. Without this guard, `navigate()` throws
`IllegalStateException: NavController has not been set a graph` during the
Scaffold sub-composition window.

`onLinkConsumed` sets `pendingLink = null` at the Activity level;
`LaunchedEffect` is keyed on the link value so it does not re-run for the
same string, and does not run at all when the value is null.

All deep-link path/query validation is concentrated in `ShellRoutes.deepLink`.
Links that carry an authorization flag, unknown host or path, or negative
positions resolve to `ShellDestination.NotFound` before touching the graph.

### 5  Parent PIN gate: production ProfilesViewModel, no saved authorization

The parent console route (`lazyeng://app/parent` or the "家长控制台"
button) navigates to the `parent` composable destination. `ProfilesNavigation`
drives `ProfilesViewModel` into PIN phase via a `LaunchedEffect`, not via
a navigation argument or saved-state flag. `DisposableEffect` calls
`model.onBackground()` on disposal, clearing PIN memory. State restoration
re-enters PIN phase; the "导入视频" button and profile list are only
visible after successful PIN entry. No authorization boolean is passed
through `NavController.navigate()` or stored in `Bundle`.

### 6  ExistingDestinations as a wiring-only composable

`ExistingDestinations.kt` adapts existing feature ViewModels to
`ShellDestination` variants. It does not own business logic and does not
import Room entities or DAOs. It will shrink as feature modules grow their
own destination entry-points.

`PlayerDestination` is a validated placeholder: it loads `ChildVideoDetail`
to confirm the video is watchable and the resume position is in range, then
shows title and position without a real player. E4-T1 owns the player
integration.

## Tests and reproduction

Unit tests: `ShellRoutesTest` (deep-link parsing, route encoding),
`NavigationScopeTest` (cancelled read cannot publish stale scope, failed
read is an error not a default profile).

Instrumentation tests: `NavigationShellTest` (9 cases—tab state retention,
saved-state restoration, profile-switch isolation, dictionary dialog,
parent route immersiveness, deep-link error paths, large-font readability)
and `NavigationParentGateTest` (1 case—parent deep link never restores
authorization through saved-state or background).

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Apply MIUI appops preflight from ENV_NOTES.local.md after both installs,
then run each test class separately:

```text
adb -s <serial> shell am instrument -w -r \
  -e class com.lazyeng.family.NavigationShellTest \
  -e timeout_msec 120000 \
  com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner

adb -s <serial> shell am instrument -w -r \
  -e class com.lazyeng.family.NavigationParentGateTest \
  -e timeout_msec 120000 \
  com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner
```

Generated screenshots go to `files/eh-t1-evidence/` in the target
package. The `FLAG_SECURE` window flag is not disabled for tests.

## Execution results (2026-09-26)

- Full build (`assembleDebug assembleDebugAndroidTest testDebugUnitTest
  lintDebug`): BUILD SUCCESSFUL. Unit test run: 259 tasks, zero failures.
  Lint: BUILD SUCCESSFUL in 1m 55s, 614 tasks, no new issues.
- Initial Codex run passed 6/9 device tests; the 3 failures were
  `malformedLinkShowsActionableErrorAndBack`,
  `syntacticallyValidButMissingVideoIsAlsoAnError` and
  `parentDeepLinkSavedStateAndBackgroundNeverRestoreAuthorization`. Root
  cause: `navigate()` was called before NavHost had built its graph during
  Scaffold's deferred sub-composition. Fix: `currentBackStackEntryFlow
  .first()` in `ChildShell.LaunchedEffect(pendingLink)` suspends until the
  start destination is pushed, ensuring the graph exists before routing.
- After fix: all 9/9 device tests pass in 21.435 s on Xiaomi 10S / API 31.
  MIUI appops preflight applied from ENV_NOTES.local.md before each run.
  No test required manual interaction.
- Captures in `app/build/reports/eh-t1/`: `invalid-link.png` (error screen
  for malformed link), `me-font-1.3.png` (ME tab at font scale 1.3),
  `player-return.png` (player screen after dictionary close). Text and
  primary actions fit at 1.3 scale. `FLAG_SECURE` is not disabled.

| EH-T1 acceptance | Result |
| --- | --- |
| Four tabs with independent scroll and filter state | Passed: `allTabsRetainIndependentFiltersAndScroll` |
| Saved-state restores active tab | Passed: `savedStateRestoresActiveTabFilterAndScroll` |
| Profile switch clears previous tab state | Passed: `profileChangeClearsAllPreviousTabStateAndRefreshPreservesSameScope` |
| Dictionary dialog preserves player entry | Passed: `dictionaryCloseAndRestorationKeepExactOriginWithoutBottomBar` |
| Parent route is immersive, back returns to tab | Passed: `parentRouteIsImmersiveAndBackReturnsToTab` |
| Malformed deep link shows actionable error | Passed: `malformedLinkShowsActionableErrorAndBack` |
| Missing video deep link shows error, not placeholder | Passed: `syntacticallyValidButMissingVideoIsAlsoAnError` |
| Large-font tabs readable at 1.3 scale | Passed: `realPlaceholderTabsAreReadableAtLargeFont` |
| Parent deep link never restores authorization | Passed: `parentDeepLinkSavedStateAndBackgroundNeverRestoreAuthorization` |

EH-T1 is complete within its task boundary. Real home/library/vocabulary/
progress screens remain their own tasks. Next task: EH-T2 or as directed.
