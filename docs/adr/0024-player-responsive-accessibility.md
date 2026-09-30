# ADR 0024: Player Responsive Layout and Accessibility

## Status

Accepted for E4-T4.

## Context

The existing player had a portrait transcript layout and a side-by-side layout whenever width exceeded height. That treated a phone in landscape like a tablet, left the controls permanently visible, and exposed inconsistent TalkBack descriptions and touch sizes.

## Decisions

- Select the layout from available Compose constraints. A landscape window with a height below 600dp uses the compact immersive player; a window at least 600dp wide outside that compact-landscape case uses the expanded side-by-side layout; other windows use portrait stacking.
- Compact landscape overlays the PlayerView with controls. Controls fade out after five seconds only while playback is ready and active. A non-consuming pointer observer reveals them on touch; pause, loading, lookup, and errors keep controls available. The transcript is opened in a translucent side drawer with an explicit close action.
- Landscape or explicit full-screen mode hides system bars using transient swipe reveal. The Activity already handles orientation and screen-size configuration changes, so rotation does not intentionally recreate the player route or playback engine.
- Keep one transcript LazyListState above the adaptive branches so orientation/drawer changes preserve scroll position. The PlayerView continues using FIT resize mode for letterbox/pillarbox behavior.
- Player actions expose Chinese content and state descriptions; playback status and the active transcript cue are announced in words, not color alone. Touch controls use 48dp minimum dimensions. Token labels may wrap at large font scales; dictionary content remains vertically scrollable with a window-relative height cap.

## Verification

- `PlayerScreenTest.responsiveLayoutClassifiesPhonePortraitLandscapeAndExpandedWindows` covers portrait, compact-landscape, and expanded layout classification.
- `PlayerScreenTest.compactLandscapeControlsAutoHideAndReturnOnScreenTap` requests landscape on the Xiaomi 10S and uses the Compose test clock to verify the five-second hide and touch-to-reveal behavior.
- `PlayerScreenTest.playerActionsExposeLocalizedSemanticsAndAtLeast48DpTargets` checks localized descriptions and measures key action semantics bounds; each checked action is at least 48dp in both dimensions.
- `PlayerScreenTest.longSubtitleRemainsAvailableAtTwoTimesFontScale` renders a long bilingual cue at 2.0x font scale and verifies the cue, a word token, and playback action remain available.
- On Xiaomi 10S (`52072092`), manually installed the test APK, granted MIUI AppOps `10021` and `10008` to both app/test packages, foregrounded `MainActivity` with `adb shell am start -W`, then ran `adb shell am instrument -w -r -e class com.lazyeng.family.PlayerScreenTest com.lazyeng.family.test/androidx.test.runner.AndroidJUnitRunner`: **15 tests passed**. The focused 2.0x font test also passed independently.
- Device screenshots: [portrait](evidence/e4-t4/player-portrait.png), [landscape controls visible](evidence/e4-t4/player-compact-landscape-controls-visible.png), [landscape controls restored after touch](evidence/e4-t4/player-compact-landscape-controls-restored.png).
- `testDebugUnitTest` and `lintDebug` both completed successfully. The final test APK compiled and assembled; no Gradle connected-test task was used.

## Assumptions and limitations

- The 600dp compact-height threshold is a practical distinction for phone landscape; unusual windowing modes may classify differently, but remain usable through the same responsive constraints.
- TalkBack traversal order is the Compose tree order; dedicated hardware-keyboard navigation and fold posture APIs are outside this task's scope.
- The screenshot tests use deterministic UI fixtures rather than an imported household video. Media3 playback itself is covered by the local synthetic-video instrumentation test, but uninterrupted real-media playback through a physical device rotation was not separately measured.
