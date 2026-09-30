# ADR 0032: Visual, Accessibility, and Device-Matrix Acceptance

## Status

Accepted for the INT-2 implementation scope. Physical-device matrix evidence is recorded separately below and must not be inferred from compilation or JVM tests.

## Context

The INT-2 audit found that `LazyEngTheme` always installed a light Material color scheme while 147 UI call sites consumed fixed light-palette colors directly. The player controls also mixed hard-coded black/white values with theme surfaces. This could produce unreadable combinations when the system was dark. Existing E4-T4 tests exercised player behavior, but did not provide an automated light/dark contrast assertion or an INT-2 device-matrix suite.

## Decisions

- `LazyEngTheme` follows the system dark-mode setting and provides a semantic light or dark palette through a CompositionLocal. Existing `LazyEngColors` access points remain the feature-facing token API.
- Player video pixels remain black-backed as required by video presentation. Headers, subtitle overlays, and controls use Material inverse-surface roles; top/bottom controls respect status/navigation bar insets.
- Flashcard content is vertically scrollable and its card grows with content instead of clipping scaled text. The card exposes a button role and a localized action description.
- Contrast tests use the WCAG relative-luminance ratio for primary text/background pairs and require at least 4.5:1. Instrumentation covers adaptive layout under portrait-/landscape-shaped window sizes, primary touch targets, dictionary content at 2x font scale, and flashcard content at 2x font scale.
- Test fixtures are synthetic. Device tests use the Android test Activity and do not clear app data, create family records, or enter onboarding.

## Verification Record

- Host verification (JDK 17, offline): `:app:assembleDebug :app:assembleDebugAndroidTest`, `testDebugUnitTest`, and `lintDebug` all passed. A first aggregate build hit D8 Java heap exhaustion while dexing `:feature:parent:mergeExtDexDebugAndroidTest`; splitting build/test/lint invocations, setting `-Xmx4g -XX:MaxMetaspaceSize=1g`, and limiting to one worker completed successfully.
- Xiaomi 10S (`52072092`, Android device model `M2102J2SC`): installed APKs with `adb install -r`, allowed AppOps 10021/10008 for both app packages, foregrounded `MainActivity` with `am start -W`, and invoked tests directly with `am instrument`. Final `DeviceMatrixVisualTest`: 4 tests passed; `FlashcardReviewScreenTest#longFlashcardContentRemainsScrollableAtTwoTimesFontScale`: 1 test passed. An initial run exposed missing/mismatched test tags in the dictionary/player surfaces; the runner was stopped without touching app data, tags/assertions were corrected, and the final runs passed.
- The window-size test changes the Compose test window between 360x820dp and 820x360dp and verifies adaptive layout plus retained playback position. It does **not** rotate the physical device or verify Activity recreation.
- Automated contrast coverage checks primary text/background pairs in both themes at >=4.5:1. The controls test verifies content descriptions and >=48dp bounds for the exercised player/dictionary actions. These are targeted assertions, not exhaustive audits of every screen/control/state.
- At 2.0x font scale, the dictionary long sentence and flashcard content remain scrollable and visible without a fixed-height clip in the captured test surfaces.
- Screenshot evidence: `docs/adr/evidence/int-2/dark-dictionary-font-2x.png` and `docs/adr/evidence/int-2/dark-flashcard-font-2x.png`. Both contain synthetic test content.
- No app data clear, backup, or restore was performed; device tests use isolated test content.

## Scope and Limitations

The automated contrast assertion currently verifies the primary text/background role pair in both themes; it is not a claim that every possible state/color pairing across all screens has been formally measured. TalkBack was not enabled or manually traversed as part of this automated suite. Physical rotation/recreation, API 26 hardware, tablet, foldable, and split-screen coverage require additional runs/devices and are not represented by this Xiaomi 10S run.

## 实体旋转验证

- Xiaomi 10S (`M2102J2SC`, serial `52072092`) 已按标准流程安装主 APK/测试 APK，恢复主包和测试包 AppOps `10021`/`10008`，并用 `am start -W -n com.lazyeng.family/.MainActivity` 将应用置于前台。未清除应用数据。
- 方向锁定排查结论为 **2b（没有方向锁定）**：主 Manifest 未声明 `android:screenOrientation`，播放器和目标路由代码没有对 `requestedOrientation` 赋值；全屏只控制系统栏沉浸。另在 MainActivity 前台执行 `settings put system accelerometer_rotation 0`、`settings put system user_rotation 1` 并等待 2 秒后，`dumpsys window` 的 MainActivity `overrideConfig` 显示 `land`、`mRotation=ROTATION_90`，证明系统确实完成旋转；设备窗口焦点仍为 MainActivity。
- 单独直跑 `playerRetainsPositionSubtitleSpeedAndLoopAcrossPhysicalRotation` 仍未通过：首次竖屏内容已渲染并保存 `player-portrait-before.png`，切换到横屏后 10 秒内测试读取的 Compose `LocalConfiguration` 观察值未更新，测试以 `ComposeTimeoutException` 失败。Activity 在断言失败后的测试清理阶段被销毁并返回桌面；这是 teardown 结果，不能据此判断旋转触发时被系统抢占。
- 失败后检查 `accelerometer_rotation=1`、`user_rotation=0`，确认设备旋转设置已由测试 `@After` 恢复自动模式；随后 `am start -W` 恢复 MainActivity 前台焦点。本次虽证明了系统真实旋转，但未证明播放器位置/字幕/倍速/循环在 Compose 实体旋转后的保持情况。
- 查词弹层与闪卡背面实体旋转场景未运行：播放器场景的共享旋转宿主观察失败后停止，且当前设备无可用英文字幕测试视频，因此这三项（播放器、查词弹层、闪卡背面）均标记为“待补测”，不计为通过。
- 本次新增的旋转仪器测试与 debug-only Host Activity 尚未完成真机验收。现有 `DeviceMatrixVisualTest` 的窗口尺寸变化仍不等价于实体旋转或 Activity 重建。
- 用户补充了小米10S 上七个页面的横屏截图，记录为横屏布局外观证据：`landscape-home.jpg`（首页）、`landscape-library.jpg`（视频库）、`landscape-vocabulary.jpg`（生词本）、`landscape-mine.jpg`（我的）、`landscape-parent-dashboard.jpg`（家长看板）、`landscape-profile-create.jpg`（创建孩子档案）、`landscape-onboarding-pin.jpg`（首次设置流程中的家长 PIN 验证）。这些静态截图证明对应页面能以横屏形态显示，但不证明同一页面由竖屏旋转至横屏再返回后局部状态得到保留；播放器、查词弹层、闪卡背面和家长入口 PIN 门禁的旋转状态仍未验收。首次设置流程的 PIN 图也不等同于家长控制台的受保护 PIN 门禁。
- 家长 PIN 页面旋转：根据用户在小米10S 上的人工验证确认，横屏布局完整，PIN 圆点不回显明文，提示文案保留，且未绕过门禁；记为人工验证通过。对应截图 `landscape-onboarding-pin.jpg` 属于首次设置流程的 PIN 页，非家长控制台门禁页面，因此截图仅作横屏外观佐证，行为结论以用户现场人工验证为依据。
- 播放器、查词弹层、闪卡背面实体旋转：因当前设备无英文字幕测试视频，标记为“待补测”，不是通过项。其余页面的横屏截图也不能替代这些交互场景的状态保留验证。
- TalkBack、平板、折叠屏与分屏：家庭自用暂不覆盖。

## 真实字幕视频补测（小米 15 Ultra）

- 使用设备中已有的带双语字幕视频，在不清除家庭数据的前提下，通过 `settings put system accelerometer_rotation 0` 和 `user_rotation 1/0` 做竖屏→横屏→竖屏切换，结束后恢复自动旋转。播放器横屏截图 `evidence/int-2/real-player-landscape-overlay.png` 显示字幕悬浮条位于画面底部；返回竖屏的 `evidence/int-2/real-player-portrait-restored.png` 显示视频与剧本继续呈现。播放进度没有回到起点。倍速和循环状态未在这次现场操作中设置，因此其旋转保持情况仍待验证。
- 查词弹层打开时选择原句约 1:20 的 `room`，横屏、恢复竖屏后词头、词义、原句与时间保持可见；关闭弹层后播放器显示 1:20 并处于暂停态，没有重建到起点。证据为 `evidence/int-2/real-lookup-landscape.png`、`real-lookup-portrait-restored.png`、`real-lookup-dismissed.png`。这证明本次真实数据路径的弹层状态保持，不代表全部词条或播放器状态覆盖。
- `PhysicalRotationAcceptanceTest` 的测试宿主在旋转后读取 `currentConfiguration.orientation` 仍发生 10 秒等待超时；真实 MainActivity 的上述人工/ADB 验证与此失败不可混为一谈。此次已修复测试截图 helper 在弹层多 Root 下的截图方式，但自动化旋转套件尚未通过，不能记为自动验收通过。
- 设备当前生词本无待复习卡，闪卡背面无法沿真实家庭数据路径进入；为了保护现有数据没有制造或删除词条。该项实体旋转仍为**待补测**。因此 INT-2 的实体旋转矩阵尚未全部完成。TalkBack、平板、折叠屏、分屏按家庭自用范围暂不覆盖。
