# 「家庭版 LazyEng」Codex 开发任务清单（完善版）

> 配套文档：《「家庭版 LazyEng」视频学英语 App 产品需求文档》PRD v2.2  
> 视觉与交互参照：GitHub 仓库 `jiebozhang/MyLazyEnglish` 中的 React 高保真原型  
> 目标产物：Android 家庭自用 APK  
> 目标设备：小米 15 Ultra（重点验收）及 Android 8.0（API 26）以上手机/平板  
> 执行方式：一个任务卡对应一次 Codex 会话、一个分支、一个 Pull Request

---

## 0. 这份清单解决什么问题

本清单不是重新定义产品，而是把 PRD v2.2 和现有 Web 原型转换为 Codex 可以稳定执行的工程任务。

Codex 必须同时理解三件事：

1. **PRD 决定业务范围与数据规则**：功能是否存在、何时上线、失败时如何降级，以 PRD 为准。
2. **Web 原型决定视觉和交互意图**：页面层级、组件关系、操作节奏、状态反馈，以原型为参照。
3. **Android 实现决定平台行为**：系统权限、文件访问、Media3 播放、WindowInsets、安全存储必须遵循 Android 原生规范，不能机械翻译 Web 代码。

最终目标是闭合以下真实链路：

> 家长创建家庭并导入视频与字幕 → 孩子逐句观看 → 点词获得本地释义 → AI 异步补充语境解释 → 收藏生词 → 原句闪卡复习 → 跳回原视频时间点。

即使断网、AI 未配置或模型请求失败，上述链路中的本地播放、英文字幕、本地词典、收藏与复习仍必须可用。

---

## 1. 信息源优先级与冲突处理

### 1.1 优先级

若文档、原型和现有代码冲突，按下表处理：

| 优先级 | 信息源 | 决定内容 |
|---|---|---|
| P0 | PRD v2.2 | 产品范围、FR ID、数据边界、安全要求、V1.0/V1.1 划分 |
| P1 | 本开发清单 | 开发顺序、任务边界、依赖、验收方式、Definition of Done |
| P2 | React 高保真原型运行效果与 `frontend/src/index.css` | 页面结构、视觉层级、交互节奏、色彩和间距 |
| P3 | `docs/design_system.json`、`docs/product/features.md` | 设计与功能辅助说明 |
| P4 | Codex 自行推断 | 仅用于无明确规定的实现细节，且必须记录假设 |

发现冲突时不得静默选择：在 PR 描述的“决策与假设”中写明冲突、采用项和理由。涉及业务范围、安全、数据模型的冲突必须停止扩展，回到 PRD。

### 1.2 仓库内参考路径

开发前必须检查以下内容：

```text
frontend/src/App.tsx                         # 路由与页面入口
frontend/src/pages/                          # 首页、视频库、播放器、生词本、进度、家长端
frontend/src/components/lazyeng/             # 设备框、字幕、词典、PIN、模型设置等
frontend/src/data/mock.ts                    # 原型演示实体与样例数据
frontend/src/hooks/use-player.ts             # 原型播放交互状态
frontend/src/index.css                       # 运行时视觉 token，优先于旧设计 JSON

docs/product/features.md                     # 原型功能摘要
docs/design_system.json                      # 设计辅助说明
家庭版LazyEng-Codex开发任务清单-完善版.md       # 本执行清单
```

完整 PRD v2.2 应放入仓库 `docs/specs/` 后再开始功能开发。若仓库内尚无完整 PRD，Codex 不得只凭原型推断业务规则。

### 1.3 原型中不能照搬到 Android 的内容

以下元素只服务于浏览器演示，不属于 Android App 功能：

- 小米机身外壳、固定 9:41 状态栏、打孔前摄、浏览器模拟手势白条。
- 固定 `412×915 px` 容器；Android 应使用响应式布局和系统安全区。
- `setTimeout`、循环计时器、固定 Toast 成功、假播放进度、硬编码统计。
- 演示 PIN `2580`、演示 API Key、固定视频标题和字幕数据。
- CSS 风景封面与视频占位画面。
- Web 路由和浏览器返回行为。

Android 端应还原原型的内容布局和体验，不应在真实 App 内绘制手机外壳。

---

## 2. 已锁定的技术与业务边界

### 2.1 技术基线

- Kotlin 100%。
- Jetpack Compose + Material 3。
- 单向数据流：每个页面显式定义 `UiState`、`UiEvent`，一次性行为使用 `UiEffect`。
- Media3 / ExoPlayer 播放本地媒体。
- Room（SQLite）保存业务数据，所有 Schema 变更必须有 Migration。
- WorkManager 仅处理需要持久化、可恢复的后台任务。
- Android Keystore 封装 API Key；家长 PIN 使用带盐 KDF/哈希，不以明文或可逆形式入库。
- Kotlin Coroutines + Flow；UI 不直接访问 DAO、Media3 或网络客户端。
- Gradle Version Catalog 锁定依赖版本；禁止 `+` 动态版本。
- 最低 Android 8.0（API 26）；编译和目标 SDK 采用开发时稳定、互相兼容的版本并固定到仓库。
- JDK 17。

### 2.2 V1.0 必须完成

- 家庭与多 Profile、家长 PIN、档案隔离。
- 孩子首页、视频库、视频详情、学习进度。
- 本地视频导入、字幕关联、SRT/VTT 解析和字幕版本。
- 本地播放、逐句同步、逐词点击、单句复读、进度恢复。
- 本地词典、词形还原、查词记录。
- OpenAI 兼容协议与 Anthropic 原生协议的可配置 AI 语境解释。
- 生词本、语境闪卡、简化间隔复习。
- 家长控制台：内容、成员、大模型设置、数据与存储。
- 断网和 AI 失败降级。
- 可安装 Debug APK；完成签名配置说明和家庭侧载说明。

### 2.3 V1.0 明确不做

- YouTube 播放或下载。
- AI 字幕翻译、学习文本、ASR。
- 学习时长强制限制与家长周报。
- 云账号、跨设备同步、服务器后台。
- 支付、订阅、广告、排行榜、社区、教师后台。
- 开放式 AI 聊天。
- 复制竞品代码、内容、截图、品牌资产或文案。

这些能力只能保留稳定接口或禁用入口，不得提前实现“半成品”。

### 2.4 V1.0 原型功能白名单与隐藏规则

为避免把原型演示入口误当成 V1.0 需求，按下表执行：

| 原型内容 | V1.0 处理 |
|---|---|
| 首页“今日护眼时长” | 改为只读的“今日学习 X 分钟”，不提醒、不限时、不提供关怀设置 |
| 英文/中文/双语切换 | 仅英文可用；中文/双语入口禁用并说明“后续版本提供”，或直接隐藏 |
| “本集重点词/摘要” | 隐藏；属于 V1.1 学习文本，不生成假数据 |
| 闪卡“完整翻译” | V1.0 只展示当前语境义与英文原句；无已发布翻译时不生成整句翻译 |
| 学习关怀入口 | 隐藏或显示明确的非交互“后续版本”说明，不保存任何限时设置 |
| AI 费用/请求预算 | 纳入 V1.0 家长设置：支持请求数/估算用量上限与 `AI_BUDGET_REACHED` 降级 |
| 演示搜索、发音、回原句按钮 | 必须实现真实行为，否则从 V1.0 UI 移除，不能保留无响应控件 |

---

## 3. 原型还原标准

### 3.1 页面与导航

| 原型路由 | Android 页面 | V1.0 要求 |
|---|---|---|
| `/` | HomeScreen | 继续观看、今日成就、精选/最近导入、Profile 与护眼提示 |
| `/library` | LibraryScreen + VideoDetailScreen | 搜索、等级/主题/状态筛选、视频详情、不可播放说明 |
| `/player/:videoId` | PlayerScreen | Media3 视频、逐句字幕、点词、倍速、复读、进度保存 |
| BottomSheet | ContextDictionarySheet | 本地释义、AI 解释、原句回溯、收藏与已掌握 |
| `/vocabulary` | VocabularyScreen + ReviewScreen | 状态筛选、词条列表、翻卡、三档评分、回原句 |
| `/me` | ProgressScreen / SettingsScreen | 今日/本周统计、词汇状态、家长入口 |
| `/parent` | PinGate + ParentConsoleScreen | PIN 门禁、内容、成员、模型配置、存储管理 |

以下生产页面在 Web 原型中没有完整页面，必须按 PRD 补齐，不得因原型缺失而省略：

| Android 页面 | 布局与交互基线 |
|---|---|
| OnboardingScreen | ≤3 步：创建家庭与 PIN → 创建孩子 Profile → 进入首页；每步可恢复 |
| ProfilePicker/EditScreen | 大头像卡片、昵称/角色/等级；孩子可切换，新增/删除仅家长可操作 |
| ImportVideoScreen | 文件选择、空间预估、处理状态和可执行错误；不向孩子展示 |
| SubtitlePreviewScreen | 前 10 句时间轴预览、乱码/错误提示、整体偏移、确认发布与一次回滚 |
| VideoDetailScreen | 孩子信息简洁；家长增加来源、版本、状态、权利备注和修复入口 |
| ParentContentScreen | 导入任务、字幕状态、归档和存储占用 |
| ParentMembersScreen | 成员列表、汇总、创建/编辑/删除；不显示模型密钥 |
| ParentDataScreen | 分类清缓存、导出、删除及影响范围确认 |

每个 UI 任务 PR 都必须附一张“原型映射表”：`React 文件/组件 → Compose Route/Screen → UiState → UiEvent/UiEffect → 导航/返回行为 → Preview/截图`。无原型页面时引用上表和对应 PRD，并在 PR 中给出简要线框图或布局说明。

Android 导航必须支持：

- 孩子端底部 4 个入口：首页、视频库、生词本、我的。
- 播放器和家长控制台不显示孩子端底栏。
- 词典弹层关闭后回到同一播放位置。
- 从闪卡跳回原句时打开正确视频并 seek 到 `timestamp_ms`。
- 系统返回、进程重建、旋转和深链不能绕过 PIN 或丢失关键状态。

### 3.2 视觉 Token

以原型运行样式为基线：

| Token | 值 | 用途 |
|---|---:|---|
| Background | `#FAF8F3` | 主背景，温暖护眼 |
| Surface/Card | `#FFFFFF` | 卡片和弹层 |
| Text Primary | `#2D3B36` | 主文字 |
| Text Secondary | `#708079` | 次级说明 |
| Primary Mint | `#34D399` | 主操作、成功、已掌握 |
| Primary Deep | `#138A66` | 深色主操作文字/状态 |
| Dawn Blue | `#38BDFA` | 学习中、辅助高亮 |
| Surface Soft | `#F3F6F3` | 弱背景 |
| Border | `#E5ECE7` | 分隔与描边 |
| Warning | `#F59E0B` | 待复习、模糊 |
| Danger | `#E85D68` | 忘了、错误、危险操作 |
| Subtitle Highlight | `#D1FAE5` | 当前字幕句背景 |

实现要求：

- 卡片圆角主要使用 16/20/24dp；底部弹层顶部可用 28dp。
- 主字幕 18–20sp；正文不因适配而小于可读范围。
- 所有主要触控区域最小 48×48dp，包括字幕词 Token 的命中区域。
- 当前字幕除颜色外还需有背景、边框或字重变化。
- 支持系统字体缩放、TalkBack、深浅状态对比和减少动画设置。
- 动画一般 200–300ms；收藏、翻卡可以使用短促弹性动画，不能影响可操作性。

### 3.3 设备适配

- 小米 15 Ultra 以约 412dp 宽的竖屏布局作为重点截图基线。
- 使用 `WindowInsets.safeDrawing` / `statusBars` / `navigationBars`，不得硬编码 36px 和 20px。
- 竖屏上半区视频保持 16:9；下半区应能显示 4–5 行 18–20sp 字幕。
- 平板采用限制内容最大宽度或双栏，不把手机布局简单拉宽。
- 横屏播放器以视频为主，字幕使用侧栏或底部区域。
- 目标截图至少覆盖：412dp 竖屏、常见小屏、平板、横屏、系统字体 1.3 倍。

---

## 4. 目标工程结构

保留 `frontend/` 作为可运行的设计参照，不得删除、重写为 Android WebView 或当作正式业务实现。

建议在同一仓库新增：

```text
apps/android/
  app/                         # Application、主 Activity、导航、依赖装配
  core/
    model/                     # 跨层纯 Kotlin 数据模型
    common/                    # Result、Dispatcher、时钟、日志抽象
    designsystem/              # 主题、Token、公共组件、Preview catalog
    database/                  # Room、DAO、Migration
    datastore/                 # 非敏感偏好；敏感值不放普通 DataStore
    media/                     # Media3 封装
    network/                   # Provider 客户端与脱敏拦截器
    testing/                   # Fixture、Fake、测试规则
  feature/
    onboarding/
    profiles/
    home/
    library/
    importmedia/
    player/
    dictionary/
    vocabulary/
    progress/
    parent/
  benchmark/                   # 可选；性能基准
  gradle/libs.versions.toml
  README.md

docs/
  specs/                       # PRD、任务清单
  adr/                         # 关键架构决策
  test-evidence/               # 目标设备验收记录，不提交敏感数据
```

模块规则：

- `feature/*` 不相互直接依赖，通过 `core:model` 和显式接口通信。
- Room Entity 不直接暴露给 UI；Repository 映射为领域模型。
- Composable 默认无状态；Route 层连接 ViewModel，Screen 层只消费状态和事件。
- `UiState` 应为不可变数据；禁止在 Composable 内启动不可控业务请求。
- 所有时间、网络、播放器和密钥接口可替换为 Fake，保证测试可重复。
- 若 Codex 选择更少的 Gradle 模块，包边界仍必须与上述职责一致，并在 ADR 中说明理由。

### 4.1 标准 UDF 结构

```kotlin
data class XxxUiState(
    val isLoading: Boolean = false,
    val content: XxxContent? = null,
    val error: UiMessage? = null
)

sealed interface XxxUiEvent
sealed interface XxxUiEffect

@Composable
fun XxxRoute(viewModel: XxxViewModel, onNavigate: (...) -> Unit)

@Composable
fun XxxScreen(state: XxxUiState, onEvent: (XxxUiEvent) -> Unit)
```

每个 Screen 至少提供：

- 正常态 `@Preview`。
- Loading、Empty、Error 中该页面实际存在的状态 Preview。
- 412dp 宽手机预览；关键页面补充平板或横屏预览。
- Preview 使用 deterministic fixture，不访问数据库、网络或播放器。

### 4.2 权限与数据作用域

| 调用方 | 允许范围 | 禁止行为 |
|---|---|---|
| 孩子页面 | 当前 `profileId` 的视频进度、查词、生词、复习和统计 | 读取其他 Profile、密钥、PIN、任务诊断 |
| 家长聚合 UseCase | 仅在有效 PIN 会话内读取指定 `familyId` 的成员汇总 | 通过普通 Repository 暴露全库查询、返回 API Key |
| 内容管理 | 家庭共享 Video/Subtitle 状态和修复信息 | 把内部错误和路径展示给孩子 |
| AI 缓存 | 同一 family、同一净化输入、同一学习等级/语言/版本可复用 | 跨 family 复用或缓存昵称、历史和敏感配置 |
| 导出 | 经 PIN 二次确认的业务数据 | 导出 API Key、PIN 哈希/盐、Authorization、内部文件绝对路径 |

家长汇总和导出必须使用独立的 `ParentAuthorizedScope`/UseCase，由有效 PIN 会话签发短生命周期授权；不得为了家长功能放宽所有 Repository 的 Profile 过滤约束。

---

## 5. Codex 全局执行协议

每次启动新会话时，把下面的“固定前置指令”与一个任务卡一起提供给 Codex。

### 5.1 可直接复制的固定前置指令

```text
你正在实现「家庭版 LazyEng」Android App。本次只完成我提供的一个任务卡。

开始前必须：
1. 阅读 docs/specs 中的 PRD v2.2、本开发清单，以及任务引用的 FR ID。
2. 检查当前分支、已有模块、测试和未提交改动；不得覆盖或回退他人的工作。
3. 查看 frontend 原型中对应页面和组件，但只把它当作视觉/交互参照；不得使用 WebView，不得移植 React 运行时。
4. 列出本任务的依赖是否已满足。依赖不满足时停止实现并明确报告，不得用假接口掩盖。
5. 先给出不超过 5 步的实现计划，再开始修改。

实现时必须：
- 仅修改本任务所需范围，不顺手实现相邻 Epic。
- 遵守 UiState / UiEvent / UiEffect 单向数据流和无状态 Screen 约定。
- 不硬编码真实 API Key、PIN、用户数据、绝对路径或目标设备像素尺寸。
- 所有 Profile 范围数据必须显式传 profileId；不得提供无条件读取全部成员私有数据的 Repository 方法。
- 网络和 AI 失败不得阻断本地学习主链路。
- 新增持久化字段时必须提供 Room Migration 和迁移测试。
- 处理日志时对 Authorization、API Key、PIN、字幕原文和本地文件名脱敏。
- 不下载 YouTube，不绕过系统权限，不复制竞品资源。

完成前必须执行适用于本任务的构建、单元测试、Lint 和 UI 测试，并修复本次引入的问题。
最终回复必须包含：
1. 实现摘要；2. 修改文件；3. 执行过的命令及结果；4. 验收证据；
5. 未完成项/风险；6. 建议的下一任务 ID。
不得把“代码已写”视为完成；验收项和测试未通过就明确标记未完成。
```

### 5.2 分支与提交约定

- 分支：`codex/<task-id>-<short-name>`，例如 `codex/e4-t2-subtitle-sync`。
- 一个任务卡一个 PR；禁止把多个 Epic 混进同一 PR。
- Commit 使用清晰前缀：`feat:`、`fix:`、`test:`、`docs:`、`refactor:`。
- PR 描述必须包含：FR ID、范围、非范围、测试结果、界面截图、数据迁移、风险、回滚方式。
- 不提交 APK、密钥、真实家庭数据、大体积词典源文件或本地媒体。
- 词典数据包应通过受控导入脚本或 Git LFS/Release 方案管理，先完成许可审查。

---

## 6. 全局 Definition of Done

任何任务只有同时满足以下条件才可合并：

- 需求引用和任务边界明确，没有实现 V1.1/V2.0 范围。
- 代码可编译，未新增 Lint 错误。
- 新逻辑有单元测试；关键 UI 有 Compose 测试或稳定截图证据。
- Loading、Empty、Error、Success 中适用状态均有处理。
- 关键控件有无障碍语义，触控目标不小于 48dp。
- 不依赖网络也能完成该功能中约定的本地路径。
- 敏感信息未进入代码、日志、数据库明文字段、截图和测试夹具。
- Profile 数据查询有隔离测试。
- Room Schema 变化有 Migration 和 migration test。
- Composable Preview 不依赖运行时服务。
- PR 中附目标设备或等效 412dp 视口截图；视觉差异有说明。
- `assembleDebug`、单元测试和 Lint 通过；适用时运行设备测试。
- 文档只更新本任务相关部分，未用大段生成内容掩盖缺失实现。

建议最低命令集（以实际模块路径为准）：

```bash
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedDebugAndroidTest   # 有模拟器/真机时
```

---

## 7. 开发顺序与依赖

```text
T0-1 → T0-2 → T0-3
                  ├→ T0-4
                  └→ T0-5

数据库迁移与功能主线（必须按箭头顺序合并）：
E1-T1 → E2-T1 → E3-T1 → E3-T2 → E2-T2 → E2-T3 → E2-T4
      → EH-T1 → EH-T2 → EH-T3 → E4-T1 → E4-T2 → E4-T3 → E4-T4
      → E5-T1 → E5-T2 → E5-T3 → E5-T4 → E6-T1 → E6-T2 → E6-T3
      → E7-T1 → E7-T2 → E7-T3 → EP-T1 → E1-T4
      → E8-T1 → E8-T2 → E8-T3
      → INT-1 → INT-2 → INT-3 → REL-1

Profile 交互支线：
E1-T1 → E1-T2 → E1-T3 → EH-T1
```

并行规则：

- T0-4 与 T0-5 可在 T0-3 合并后并行。
- 只有不修改同一 Room Schema、导航图或公共 API 的任务才可并行。
- E1-T1、E2-T1、E3-T1、E5-T4、E6-T2、E7-T1、EP-T1 必须按顺序合并，避免 Migration 版本冲突。
- INT-1 的精确依赖为：E1-T4、E2-T4、EH-T3、E4-T4、E5-T4、E6-T3、E7-T3、EP-T1、E8-T3。

### 7.1 Room Schema 版本所有权

| 版本 | 责任任务 | 新增/变化 |
|---|---|---|
| v1 | E1-T1 | Family、Profile、当前 Profile 引用；初始 Schema，无 Migration |
| v2 | E2-T1 | Video、VideoAsset、ImportJob、WatchProgress |
| v3 | E3-T1 | SubtitleTrack、SubtitleVersion、SubtitleLine；外键引用 v2 Video |
| v4 | E5-T4 | LookupEvent 及去重索引 |
| v5 | E6-T2 | AIGeneratedContent、AIJob、预算/使用量记录 |
| v6 | E7-T1 | VocabularyItem、VocabularyContext、ReviewAttempt |
| v7 | EP-T1 | LearningSession 与必要聚合索引 |

ECDICT/NGSL 建议使用独立的只读、带版本词典数据库，不与家庭业务库 Migration 混合。v1 任务只需导出初始 Schema；从 v2 开始，每个责任任务必须提交 `fromVersion → toVersion` Migration、上一版本 Schema fixture 和迁移测试。若前序任务调整版本，后续任务先 rebase 并更新本表，禁止两个 PR 占用同一版本号。禁止在 Release 使用 destructive migration；迁移失败时保留原数据库并给出可恢复提示，不得自动清空家庭数据。REL-1 必须从每个已发布 Schema 版本逐级升级到当前版本。

禁止提前：

- E4 不得在 E2/E3 数据契约未稳定时硬编码字幕数组。
- E6 不得在 E5 本地释义路径未完成时让 AI 成为唯一解释来源。
- E8 不得只做一个可绕过的 UI 弹窗。
- REL-1 不得在 E2E 和安全检查失败时生成“可交付”声明。

---

# 阶段 0：工程、设计与风险基线

## T0-1 初始化 Android 工程与持续验证

**需求引用**：PRD 13、18、21  
**依赖**：无

**范围**：

- 在 `apps/android/` 创建 Kotlin + Compose Material 3 工程，minSdk 26，JDK 17。
- 创建前述模块或等价职责边界，配置 Version Catalog、Gradle Wrapper 和基础测试。
- 保留 `frontend/`；Android 构建不得依赖 Node 或浏览器运行时。
- 首页先显示可识别的工程壳和构建版本，不实现业务。

**交付物**：工程、模块说明、构建说明、基础 CI 工作流或可重复命令。

**验收**：

- 全新 clone 后无需私有密钥即可执行 `assembleDebug`、单元测试和 Lint。
- Debug APK 可安装到 API 26 和当前目标 API 模拟器。
- 仓库不存在机器绝对路径、动态依赖版本和提交的签名私钥。

**非范围**：业务表、真实导航、原型页面。

---

## T0-2 建立测试夹具与质量门禁

**需求引用**：PRD 18、20、21  
**依赖**：T0-1

**范围**：

- 创建 `core/testing` deterministic fixture：Profile、Video、SubtitleLine、DictionaryEntry、VocabularyItem。
- 统一 TestDispatcher、FakeClock、FakeIdGenerator、FakeNetworkMonitor。
- 配置单元测试、Compose UI 测试、Room migration test 基础设施。
- 建立 PR 检查：编译、测试、Lint；不要求每次下载大词典或真实媒体。

**验收**：

- 测试重复运行结果一致，不依赖真实时间、网络和系统媒体库。
- 故意制造失败测试时 CI 会阻止合并。
- Fixture 中无真实姓名、密钥、家庭媒体路径。

---

## T0-3 定义核心模型、接口与错误模型

**需求引用**：PRD 9、11、12、15  
**依赖**：T0-1、T0-2

**范围**：

- 定义 PRD 数据模型 I/II 的领域模型、ID 类型、状态机和 Repository 接口。
- 定义统一 `AppResult`/错误分类；用户文案与诊断信息分离。
- 定义 Clock、Dispatcher、FileAccess、MediaSession、SecureSecretStore、AI Provider 抽象。
- 用 ADR 记录：模块边界、UDF、删除语义、版本不可变、Profile 隔离。

**验收**：

- UI 层不依赖 Room Entity 或 HTTP DTO。
- 错误码覆盖 PRD 第 15 节并允许扩展。
- `SubtitleVersion` 发布后不可更新；事件类模型只追加。
- 所有 Profile 范围接口显式要求 `profileId`。

---

## T0-4 将 Web 原型落成 Compose 设计系统

**需求引用**：PRD 4、6、18；原型 `index.css` 与 `components/lazyeng`  
**依赖**：T0-1、T0-2

**范围**：

- 创建 LazyEng Material 3 ColorScheme、Typography、Shape、Spacing、Elevation 和 Motion token。
- 创建公共组件：主要/次要按钮、状态 Chip、视频卡、数据卡、SectionHeader、EmptyState、ErrorState、LoadingSkeleton。
- 创建字幕行、词 Token、底部弹层把手、PIN 圆点、评分按钮的可复用视觉组件。
- 创建 Preview catalog，使用 fixture 展示正常、禁用、加载、错误和大字体状态。

**验收**：

- 412dp 宽截图与原型在内容层级、配色、圆角、字号、间距上可识别一致。
- 所有主要触控目标至少 48dp；TalkBack 能读出用途和状态。
- 字体 1.3 倍不裁切关键操作。
- 系统减少动画时不依赖翻转/位移动画表达状态。
- 不在 App 内绘制手机机身、打孔和假手势条。

---

## T0-5 完成三项阻塞性技术预研

**需求引用**：PRD 0、17、19、22  
**依赖**：T0-1、T0-3

**范围**：每项给出可运行 Spike、结论和 ADR，不进入生产 UI。

1. **SAF + Media3**：选择本地视频、持久化 URI 权限、提取元数据、播放、杀进程后恢复访问。
2. **安全存储**：Keystore 封装 API Key；PIN KDF/盐、失败限速、不可用时禁止明文降级。
3. **词典许可与体积**：确认 ECDICT 与 NGSL 的许可证、署名、分发和更新方式；测量数据体积与随机查词性能。

**验收**：

- 小米 15 Ultra 或等效 Android 设备可播放 SAF 选择的媒体，重启后仍可访问。
- 日志与数据库中查不到明文 Key/PIN。
- 词典许可结论为“可采用”后才能进入 E5；若不可采用，ADR 指定等效替代源。
- 未通过的 Spike 必须给出替代方案，不得用 Mock 宣称风险关闭。

---

# 阶段 1：核心数据与内容准备

## Epic E1：Profiles（家庭多档案）

### E1-T1 Family/Profile 持久化与隔离

**需求引用**：PRO-01~04；数据模型 I  
**依赖**：T0-3

**交付物**：Room v1 初始 Schema、Entity、DAO、Repository、当前 Profile Store 和 Schema 导出（v1 无 Migration）。

**验收与测试**：

- 字段与 PRD 对齐；删除采用可审计语义。
- 两个 Family/Profile 的增删改查相互隔离；跨 family/profile ID 查询返回空。
- Repository 不暴露“读取所有 Profile 私有数据”的便捷方法。
- 进程重建后恢复最后使用的 Profile；不存在时进入安全选择流程。
- 下游进度、查词、生词和任务隔离不在本任务伪造，统一由 E1-T4 验收。

### E1-T2 首次启动与 PIN 设置

**需求引用**：PRO-01  
**依赖**：E1-T1、T0-5 安全预研

**交付物**：不超过 3 步的首次启动流程、PIN 设置/确认、失败限速。

**验收与测试**：

- 新安装必须先创建家庭、设置 PIN、创建至少一个孩子档案。
- PIN 使用 PBKDF2-HMAC-SHA256（随机盐至少 16 字节、参数随记录保存）；迭代数以目标设备校准到约 250ms 且不得低于 210,000 次，禁止快速哈希或可逆加密。
- 连续输错 5 次锁定 30 秒；之后失败等待时间翻倍，最长 15 分钟。失败次数和 `lockUntil` 持久化，重启不能绕过；验证成功后重置。
- 无云账号阶段不设置后门或通用恢复码；忘记 PIN 只能通过清除应用数据重新初始化。首次设置时必须明确告知此后果。
- 旋转、后台恢复不会跳过流程或暴露输入值。
- 单元/UI 测试覆盖固定测试向量、两次 PIN 不一致、连续失败、重启仍锁定和成功后重置。

### E1-T3 Profile 创建、切换、编辑与删除

**需求引用**：PRO-02~06  
**依赖**：E1-T1、E1-T2

**交付物**：头像、昵称、角色、age_mode、英语等级设置；切换器；PIN 二次确认删除；成员概览。

**验收与测试**：

- 本任务完成 Profile 切换状态、UI 清空旧状态和 `ProfileChanged` 事件契约；播放点/学习事件的实际落盘由下游任务接入，并在 E1-T4 统一验证。
- 本任务完成 PIN 二次确认、Profile 本体删除和 `ProfileDeletionRequested` 清理协调器；下游表和 WorkManager 在各自任务注册清理器，E1-T4 验证全量清理。
- 家长成员概览先只使用 Profile 自身字段；真实学习汇总由 EP-T1 接入，不使用硬编码 Mock。
- 切换后 UI 不出现上一 Profile 的昵称、头像或设置闪现。

### E1-T4 跨功能 Profile 隔离与删除集成

**需求引用**：PRO-03~06、E2E-04、E2E-08  
**依赖**：E2-T4、E4-T4、E5-T4、E7-T3、EP-T1

**交付物**：播放、查词、生词、复习、统计和后台任务接入 Profile 切换/删除协调器；家长汇总接入 `ParentAuthorizedScope`。

**验收与测试**：

- 切换前立即保存播放点和待提交学习事件；切换后所有页面无旧 Profile 数据闪现。
- 两个 Profile 分别写入进度、查词、生词、复习和统计后，任何孩子端查询均不会串数据。
- 删除 Profile 会级联清理其学习数据并取消对应 WorkManager 任务，不影响家庭共享媒体、词典和其他成员。
- 家长汇总仅在有效 PIN 会话内可读取，且不返回 API Key、PIN 或原始敏感内容。

---

## Epic E3：Subtitle Engine（先于导入执行）

> 为满足外键顺序，先完成 E2-T1 的 Video 空间与状态模型；随后 E3 必须在 E2-T2 真实导入流程前完成。文档章节顺序不代表可跳过第 7 节 DAG。

### E3-T1 字幕模型与 SRT/VTT 解析

**需求引用**：PRD 8.6、数据模型 I  
**依赖**：T0-3、E2-T1（数据库 v2 与 Video 外键目标已合并）

**交付物**：SubtitleTrack、SubtitleVersion、SubtitleLine 表；SRT/VTT 解析器；编码检测和错误报告。

**验收与测试**：

- 标准 SRT/VTT、BOM、CRLF、多行字幕、中英混排、空行、HTML 标签和非法时间码都有 fixture。
- 输出统一毫秒时间轴并按序号稳定排序。
- 编码失败提供明确可行动错误，不静默丢行或猜测文本。

### E3-T2 时间轴校验、Token 化和版本发布

**需求引用**：PRD 8.6、8.10  
**依赖**：E3-T1

**交付物**：重叠/倒序/超长校验；整体偏移预览；英文 Token 与字符范围；不可变版本发布。

**验收与测试**：

- 结束时间不得早于开始时间；异常返回行号和原因。
- 连字符、所有格、缩写、标点、人名、数字和多词表达能映射回原句字符范围。
- 已发布版本禁止 UPDATE；校准和替换产生新版本，旧查词引用仍可解析。

---

## Epic E2：Local Import（本地视频导入）

### E2-T1 Video/VideoAsset 模型与状态机

**需求引用**：PRD 8.3、VID-01~05  
**依赖**：T0-3、E1-T1（数据库 v1 已合并）

**交付物**：Video、VideoAsset、ImportJob、WatchProgress；`DRAFT → PROCESSING → READY → ARCHIVED` 与失败分支。

**验收与测试**：

- 非法状态跳转被拒绝并返回错误码。
- `READY` 必须同时满足可访问媒体和已发布英文字幕。
- 归档内容不进推荐，但历史引用仍可解析。

### E2-T2 SAF 文件选择、元数据与重复检测

**需求引用**：IMP-01、IMP-02、IMP-06  
**依赖**：E2-T1、E3-T2、T0-5 SAF 预研

**交付物**：系统文件选择器、持久 URI 权限、媒体元数据、checksum、空间预估、失败清理。

**验收与测试**：

- 用户取消不写脏数据；损坏视频不进入 READY。
- 无额外目录读取权限；删除仅清理应用副本，不删除原文件。
- checksum 命中时允许“复用已有”或“仍然导入”。
- 低空间、权限回收、文件移除均有明确修复动作。

### E2-T3 字幕候选、预览、关联与回滚

**需求引用**：IMP-03~05  
**依赖**：E3-T2、E2-T2

**交付物**：同名候选、前 10 句预览、手动选择、编码选择、版本发布、一次回滚。

**验收与测试**：

- 系统不能静默关联字幕，必须由家长预览确认。
- 预览清晰显示时间、英文文本、乱码和校验错误。
- 替换字幕创建新版本，不修改旧版本。

### E2-T4 视频详情页（孩子/家长双视图）

**需求引用**：VID-01~05；原型视频卡片  
**依赖**：E2-T1、E2-T3、T0-4

**交付物**：孩子详情页和家长详情页、真实状态映射、错误修复入口。

**验收与测试**：

- 孩子端只在 READY 且有英文字幕时显示可用“观看/继续”。
- 系统估算等级必须显示“估算”，不能伪装为官方 CEFR。
- 家长端可见来源、字幕版本、字数、状态、权利备注和归档操作。

---

## Epic EH：Home、Library 与基础导航

### EH-T1 App 导航与孩子端外壳

**需求引用**：PRD 6；原型 `App.tsx`、`BottomNav`  
**依赖**：T0-4、E1-T1

**交付物**：Compose Navigation、四入口底栏、沉浸页面规则、404/非法参数恢复策略。

**验收与测试**：

- 首页/视频库/生词本/我的保留各自滚动和筛选状态。
- 播放器和家长控制台不显示孩子底栏。
- 非法 videoId 不崩溃，返回可理解错误与返回入口。

### EH-T2 孩子首页

**需求引用**：HOME-01~05；原型 `HomePage`  
**依赖**：EH-T1、E1-T1、E2-T1

**交付物**：Profile 区、继续观看、今日成就、精选/最近导入、词典摘要、空/加载/错误态。

**验收与测试**：

- 继续观看按 `last_watched_at`；无历史时回退到首个 READY 视频。
- 今日按本地时区 00:00，本周按周一至周日；改时区不改写历史。
- FAILED/NEEDS_SUBTITLE 内容不进入推荐，孩子端只看到易懂状态。
- 412dp 首屏保持原型层级，主要按钮和卡片可单手点击。

### EH-T3 视频库搜索与筛选

**需求引用**：PRD 6、8.3、8.8；原型 `LibraryPage`  
**依赖**：EH-T1、E2-T4

**交付物**：搜索、等级/主题/来源/字幕状态筛选、列表、空结果和筛选清除。

**验收与测试**：

- 原型中仅展示的搜索框必须变为真实搜索。
- 切换筛选后不丢已输入搜索词；无结果显示原因和清除入口。
- 儿童 Profile 默认过滤 `age_fit = adult/unknown`；只有有效家长授权可覆盖，覆盖行为可审计。
- 处理失败内容不向孩子显示内部错误码。

---

# 阶段 2：播放器与学习闭环

## Epic E4：Player（核心）

### E4-T1 Media3 本地播放与生命周期

**需求引用**：PLY-01、PLY-06  
**依赖**：E2-T2、E2-T4

**交付物**：Media3 Player 封装、播放/暂停/Seek/音量/全屏/倍速、生命周期与音频焦点处理。

**验收与测试**：

- 视频 loading、ready、buffering、error、ended 均有 UI。
- 来电/音频焦点丢失、后台、旋转和进程重建行为明确。
- 每 10 秒、暂停、退出时保存进度；强制关闭后从合理位置恢复。
- 播放器资源按生命周期释放，无后台持续播放泄漏。

### E4-T2 字幕同步、自动滚动与手动控制

**需求引用**：PLY-02、PLY-03  
**依赖**：E3-T2、E4-T1

**交付物**：当前句计算、LazyColumn、高亮、自动跟随、手动滚动 5 秒后恢复。

**验收与测试**：

- 常速与 0.8x 下，本地字幕高亮目标误差小于 300ms。
- 边界重叠、空白间隙、Seek、回退和视频结束不选错句。
- 当前区显示 4–5 行 18–20sp 字幕；大字体可滚动且不遮挡控制。
- 当前句不仅通过颜色表达。

### E4-T3 逐词点击、倍速、单句复读与字幕模式

**需求引用**：PLY-04、PLY-05；PRD 8.10  
**依赖**：E4-T2

**交付物**：词 Token 命中区、0.8x/1.0x、句段循环、英/中/双语模式。

**验收与测试**：

- 每个词视觉上保持自然排版，命中区仍满足 48dp；标点不触发无意义查询。
- 单句复读按 start/end 循环，再次点击取消；Seek 到其他句时状态正确。
- V1.0 无翻译时中文/双语禁用并说明，不显示伪翻译。
- 点词默认按设置短暂停止或继续，关闭词典后不发生意外跳帧。

### E4-T4 播放器响应式和无障碍验收

**需求引用**：PRD 8.9、18；小米 15 Ultra 原型基线  
**依赖**：E4-T1~T3

**交付物**：竖屏、横屏、平板布局；TalkBack 语义；截图证据。

**验收与测试**：

- 412dp 竖屏视频为 16:9，字幕区可显示至少 4 行。
- 横屏视频优先，字幕侧栏/下栏可用；系统导航栏不遮挡操作。
- TalkBack 可读出播放状态、倍速、复读、当前句和可查询单词。

---

## Epic E5：Dictionary（本地词典与点词）

### E5-T1 词典数据导入与随机查词

**需求引用**：PRD 0、8.11、17、18、22  
**依赖**：T0-5 词典许可结论

**交付物**：可重复的数据导入工具、词典表/索引、版本与许可证说明、更新策略。

**验收与测试**：

- 随机 10,000 次查询达到 PRD 本地目标；报告 P50/P95。
- App 仓库不直接提交来源不明的大体积数据库。
- 无可靠词频/难度时不展示，不伪造 CEFR。

### E5-T2 词形还原、特殊输入与本地查询

**需求引用**：DIC-01、DIC-02；PRD 8.10  
**依赖**：E3-T2、E5-T1、E4-T3

**交付物**：surface → lemma；单词/短语选择模型；本地查询 UseCase。

**验收与测试**：

- 覆盖缩写、专名、数字、拼写错误、连字符、所有格、短语动词和多词表达。
- 本地命中目标 300ms 内可展示；未命中仍保留原词并允许后续 AI。
- 不自动把过长字符范围猜成短语。

### E5-T3 语境词典 BottomSheet 与发音

**需求引用**：DIC-01~06；原型 `ContextDictionarySheet`  
**依赖**：E5-T2、T0-4

**交付物**：本地 Skeleton/命中/未命中、音标/词性/释义、原句高亮、时间点、回原句和发音。收藏/掌握操作由 E7-T2 在持久化模型完成后接入，本任务不展示无响应按钮。

**验收与测试**：

- 本地卡先显示，AI 区异步加载且不遮挡本地信息。
- 回原句关闭弹层并 seek 到准确时间点。
- 发音失败只在用户点击后重试，不自动循环。
- E7-T2 合并前收藏/掌握按钮隐藏；合并后同一位置接入真实持久化状态。
- 儿童模式隐藏复制/分享；弹层可通过系统返回和下滑安全关闭。

### E5-T4 LookupEvent 去重与中断处理

**需求引用**：PRD 8.10、16  
**依赖**：E5-T3、E2-T4、E1-T1（数据库 v3 已合并）

**交付物**：追加式 LookupEvent、去重键、取消与 Profile 切换规则。

**验收与测试**：

- 同一 Profile + lemma + subtitle_line 连点 3 次只记一次统计。
- UI 可重复打开，不重复发起同键在途请求。
- 切换 Profile 后旧请求结果不得写入新 Profile。

---

## Epic E6：AI Gateway（V1.0 仅语境解释）

### E6-T1 Provider 契约、网络安全与配置存储

**需求引用**：PRD 8.18、9、15、17  
**依赖**：T0-5 安全预研

**交付物**：统一 Provider 接口、OpenAI 兼容适配器、Anthropic 原生适配器、SecureSecretStore、脱敏日志。

**验收与测试**：

- 切换 Provider 不改上层业务代码。
- API Base 仅接受 HTTPS，开发构建对本地地址的例外必须显式且不进入 Release。
- Key 只存在安全存储；日志、数据库、崩溃信息、导出和截图无明文。
- 认证、模型不存在、超时、配额和响应格式错误映射到稳定错误码。

### E6-T2 语境解释契约、校验与缓存

**需求引用**：AIC-01~05；PRD 9、10  
**依赖**：E6-T1、E5-T4（数据库 v4 已合并）

**交付物**：最小输入 DTO、JSON Schema 校验、字段清洗、AIGeneratedContent、缓存和单飞去重。

**验收与测试**：

- 只发送词、当前句、必要相邻句、学习等级和 UI 语言；不发送昵称和完整历史。
- `meaning_zh ≤ 24 字`，`why_here_zh ≤ 80 字`；非法输出最多修复一次后降级。
- 缓存键至少包含 `familyId + lemma + sentenceHash + lineVer + learnerLevel + uiLanguage + promptVer + provider + model`；仅净化后的内容可在同一家庭共享。
- 同键连续 3 次只产生 1 次真实请求；不同学习等级、语言、字幕版本或家庭不得误命中。
- `safety_flag` 非安全、Schema 不合法或内容越界时不得展示或缓存为 READY，只保留本地释义。
- 新结果失败时保留旧成功版本。

### E6-T3 AI 异步状态与失败降级

**需求引用**：DIC-03、DIC-04、AIC-01~05  
**依赖**：E5-T3、E6-T2

**交付物**：NotConfigured、Loading、Success、Offline、AuthError、Timeout、InvalidOutput、BudgetReached、Retry 状态。

**验收与测试**：

- 任何 AI 状态都不遮住本地释义、收藏和回原句。
- V1.0 前台语境解释在离线时采用“快速失败 + 用户手动重试”，不排入后台队列；本地释义、收藏和复习继续可用。
- 断网或未配置时，孩子看到易懂文案；Provider/HTTP 细节只在家长端显示。
- 超时/临时网络最多自动重试一次；认证和配额错误不自动重试。
- App 进程终止后不恢复未完成的前台解释请求；重开时可命中已成功写入的缓存。
- 用户关闭弹层或切换 Profile 时请求可取消，结果不会写错归属；飞行模式、重启、切换 Profile 与不安全输出均需自动化测试。

---

## Epic E7：Vocabulary（生词本与复习）

### E7-T1 生词与复习数据模型

**需求引用**：VOC-01~06；数据模型 II  
**依赖**：T0-3、E6-T2（数据库 v5 已合并）

**交付物**：VocabularyItem、VocabularyContext、ReviewAttempt、Migration、Repository。

**验收与测试**：

- 同 lemma 可有多个独立语境；列表可合并展示。
- ReviewAttempt 只追加；VocabularyItem 为可重建快照。
- 删除只删 Profile 关联，不删共享词典数据。

### E7-T2 收藏、列表、筛选与已掌握

**需求引用**：VOC-01、VOC-02、VOC-05、VOC-06；原型 `VocabularyPage`  
**依赖**：E5-T3、E7-T1

**交付物**：词典收藏反馈、生词本列表、学习中/待复习/已掌握筛选、最近/来源筛选、空态。

**验收与测试**：

- 收藏保存 source_video_id、line_id、subtitle_version_id、timestamp_ms 和当前语境义。
- 重复收藏幂等；成功动画不代替持久化结果。
- 筛选切换不丢滚动位置；已掌握可恢复为学习中。

### E7-T3 原句闪卡、评分与回视频

**需求引用**：VOC-03、VOC-04；原型翻转闪卡  
**依赖**：E7-T2、E4-T1

**交付物**：正/背面、发音、原句填空、完整释义、忘了/模糊/记得、跳回原视频。

**验收与测试**：

- 忘了 → 10 分钟后；模糊 → 次日；记得 → 3 天后；参数集中配置。
- 评分写 ReviewAttempt 并更新 next_review_at，重复提交幂等。
- 减少动画模式下无需 3D 翻转也能查看背面。
- 媒体不存在时保留原句，回视频入口显示不可用原因。

---

## Epic EP：Progress（学习进度）

### EP-T1 学习事件聚合与孩子进度页

**需求引用**：PRD 5、8.16、16；原型 `MePage`  
**依赖**：E1-T3、E4-T4、E5-T4、E7-T3（数据库 v6 已合并）

**交付物**：今日/本周聚合 UseCase、学习会话、进度页面、无数据状态。

**验收与测试**：

- 观看时长只统计前台实际播放，排除暂停和后台。
- 今日按本地日期，本周按周一开始；修改时区不重写历史。
- 展示观看分钟、完成片段、去重查词、复习完成、学习中/待复习/已掌握。
- 不把“查过的词”宣称为词汇量，不用虚假精确比例。

---

# 阶段 3：家长控制、联调与发布

## Epic E8：Parent Console

### E8-T1 不可绕过的家长门禁

**需求引用**：PRD 8.17、PRO-01  
**依赖**：E1-T2、EH-T1

**交付物**：家长入口、4 位 PIN 键盘、会话超时、返回栈和深链保护。

**验收与测试**：

- 离开应用或 5 分钟无操作后重新验证。
- 深链、通知、返回栈、桌面快捷方式和系统分享入口均不能绕过。
- 高风险操作（Key 修改、删除 Profile/数据）再次验证。
- 失败提示不泄露 PIN 位数或正确部分。

### E8-T2 大模型设置与真实连接测试

**需求引用**：PRD 8.18；原型 `AiSettingsForm`  
**依赖**：E8-T1、E6-T1

**交付物**：启用开关、协议、API Base、Model、Key 显隐、5–120 秒超时、每日/月请求或估算用量上限、保存、最小连接测试、首次启用数据告知。

**验收与测试**：

- 家长首次启用 AI 前看到“会向所选供应商发送词、当前句及必要相邻句”的最小数据范围说明，确认后才可启用。
- 先本地校验，再发最小请求；区分成功、认证失败、模型不存在、超时和格式不兼容。
- 达到配置上限后返回 `AI_BUDGET_REACHED`，停止新请求但保留本地释义和缓存读取；用量只标记为估算，不冒充供应商账单。
- 仅记录耗时和错误类型，不记录 Key、Authorization 或完整响应。
- 原型中的固定 200ms 成功 Toast 替换为真实状态。
- 未配置/禁用 AI 时学习主链路仍可用。

### E8-T3 内容、成员、数据与存储管理

**需求引用**：PRD 8.17  
**依赖**：E1-T3、E2-T4、EP-T1、E8-T1

**交付物**：内容处理状态、字幕修复、成员概览、缓存分类、数据导出/删除、离线占用。

**验收与测试**：

- 孩子看不到内部任务队列、Key 或诊断响应。
- 清缓存分媒体/AI/临时文件，默认不删除已发布内容和学习记录。
- 导出排除 Key/PIN；删除前说明影响范围并二次确认。
- V1.1 学习关怀只显示明确“后续版本”状态或完全隐藏，不能伪装可用。

---

## INT-1 V1.0 端到端核心链路

**需求引用**：E2E-01~04、E2E-08  
**依赖**：E1-T4、E2-T4、EH-T3、E4-T4、E5-T4、E6-T3、E7-T3、EP-T1、E8-T3

必须自动化或形成可重复测试脚本：

1. 新安装 → 创建家庭/PIN/孩子档案 → 进入孩子首页。
2. 家长导入本地视频 + SRT → 预览确认 → READY → 孩子播放。
3. 播放中字幕同步 → 点词 → 300ms 目标内本地释义 → AI 断网降级 → 收藏。
4. 生词本打开该词 → 翻卡 → 评分 → 跳回正确视频时间点。
5. 同词同句连续点击 3 次 → 1 条去重 LookupEvent、1 次同键 AI 调用。
6. 切换两个 Profile → 历史、生词、统计完全隔离。
7. 强杀应用 → 恢复 Profile、视频进度和持久化 ImportJob；未完成的前台 AI 请求不恢复，已成功落盘的缓存不得重复调用或计费。
8. 删除孩子档案 → PIN 二次确认 → 任务取消 → 其他档案无影响。

**退出条件**：所有场景通过；失败项必须关联缺陷，不能用“手动可用”替代。

---

## INT-2 视觉、无障碍与设备矩阵

**需求引用**：PRD 4、18；原型视觉基线  
**依赖**：INT-1

测试矩阵：

- 小米 15 Ultra 或 412dp 等效竖屏。
- API 26 小屏设备。
- 常见 Android 平板。
- 竖屏、横屏、分屏。
- 字体 1.0 / 1.3 / 1.5 倍。
- TalkBack。
- 亮度较低与高对比观察。

重点检查：

- 48dp 命中区、字幕 18–20sp、4–5 行可见、WindowInsets。
- 弹层和键盘不遮挡主操作。
- 当前句、错误、禁用、评分不只依赖颜色。
- 截图对照原型时比较内容层级，不比较 Web 手机外壳。

---

## INT-3 性能、安全、离线和数据完整性

**需求引用**：PRD 14、17、18、22  
**依赖**：INT-1

**性能**：

- 中端设备冷启动目标 <3 秒，报告 P50/P95。
- 本地查词随机 10,000 次，目标 <300ms，报告 P50/P95。
- 字幕同步在常速/0.8x/Seek/复读下目标误差 <300ms。
- 长字幕列表滚动无明显掉帧；播放器无资源泄漏。

**故障注入**：

- 断网、弱网、DNS 错误、AI 超时、401、404 模型、429 配额、非法 JSON。
- 媒体文件移除、URI 权限回收、低存储、杀进程、设备重启。
- Room Migration、损坏字幕、非法编码、两个 Profile 并发切换。

**安全审计**：

- 全仓库搜索 API Key、Authorization、演示 PIN、绝对路径和真实家庭数据。
- 检查数据库、日志、崩溃信息、导出文件、截图和测试报告。
- 验证家长控制台无绕过路径。

---

## REL-1 V1.0 可安装包与家庭交付

**需求引用**：PRD 13、19、21  
**依赖**：INT-1~3 全部通过

**交付物**：

- 可重复构建的 Debug APK。
- Release 签名配置说明（密钥不入库）。
- 安装、升级、备份、恢复、卸载影响说明。
- 支持的媒体/字幕格式、已知限制和隐私说明。
- ECDICT/NGSL 或替代数据源的许可证与署名。
- V1.0 验收报告和已知问题清单。

**发布门槛**：

- 从前一测试版本升级后数据不丢失。
- 全新安装和覆盖安装均通过核心 E2E。
- 无 P0/P1 缺陷；P2 缺陷有可接受绕过和记录。
- Release 构建无 Debug 日志、演示 PIN、演示 Key、Mock 网络成功路径。

---

# 阶段 4：V1.1 待办（V1.0 发布后再拆任务）

| Epic | PRD 引用 | 范围 | 启动前门槛 |
|---|---|---|---|
| E9 YouTube | YT-01~05 | 仅官方播放器、可用性检测、受限内容处理 | 完成官方能力与条款验证；不下载视频 |
| E10 字幕翻译 | TR-01~06 | 批量翻译、行号校验、STALE、断点恢复 | AI Job 与字幕版本契约稳定 |
| E11 学习文本 | TXT-01~05 | 摘要、重点表达、理解题、引用回原句 | 引用校验与预算提示完成 |
| E12 ASR | ASR-01~05 | 授权音频、分片、进度、低置信、校准 | 证明合法且可取得音频输入 |
| E13 学习关怀 | TIME-01~05 | 连续提醒、每日上限、PIN 临时解锁、审计 | LearningSession 统计经家庭试用验证 |

V1.1 不能用 V1.0 的禁用按钮作为“已完成”。启动前按本清单格式另行拆解任务卡。

---

## 8. 页面状态矩阵

Codex 不得只实现原型中的理想状态。每页至少覆盖下表：

| 页面 | 必须状态 |
|---|---|
| 首次启动 | 未初始化、PIN 设置、PIN 不一致、创建档案、恢复中、失败 |
| 首页 | Loading、正常、无观看历史、无 READY 视频、局部统计失败 |
| 视频库 | Loading、正常、空库、筛选无结果、处理准备中、不可播放 |
| 导入 | 未选择、解析中、候选字幕、预览、确认、重复、低空间、权限失效、失败 |
| 视频详情 | READY、NEEDS_SUBTITLE、PROCESSING、FAILED、ARCHIVED、文件丢失 |
| 播放器 | Loading、Ready、Buffering、Paused、Seeking、Error、Ended、字幕缺失 |
| 语境词典 | 本地加载、命中、未命中；AI 未配置/加载/成功/离线/认证失败/超时/非法输出 |
| 生词本 | Loading、正常、各状态筛选、空结果、删除确认 |
| 闪卡 | 正面、背面、评分提交中/成功/失败、原视频不可用 |
| 进度 | 无数据、正常、样本不足、局部聚合失败 |
| PIN | 空、输入中、错误、限速、成功、会话过期 |
| 模型设置 | 未配置、字段错误、测试中、成功、认证失败、模型不存在、超时、格式不兼容 |

---

## 9. 需求追踪矩阵

每个 PR 必须更新或确认对应项，防止页面已完成但业务链路遗漏。

| 能力 | FR/章节 | 实现任务 | 核心测试 |
|---|---|---|---|
| 家庭与 Profile | PRO-01~06 | E1 | 隔离、切换、删除、PIN |
| 首页 | HOME-01~05 | EH-T2 | 继续观看、日期口径、推荐过滤 |
| 视频/导入 | IMP-01~06、VID-01~05 | E2 | 权限、重复、状态机、字幕确认 |
| 字幕引擎 | 8.6 | E3 | 解析、校验、Token、版本不可变 |
| 播放器 | PLY-01~06 | E4 | 同步、复读、保存、生命周期 |
| 点词/词典 | DIC-01~06、8.10 | E5 | 300ms、特殊输入、回原句、去重 |
| AI 语境 | AIC-01~05、8.18 | E6 | Schema、缓存、降级、脱敏 |
| 生词复习 | VOC-01~06 | E7 | 多语境、评分、跳回视频 |
| 学习进度 | 8.16 | EP | 时间口径、事件聚合、样本不足 |
| 家长控制台 | 8.17~8.18 | E8 | 门禁、真实连接、导出排密钥 |
| 质量与发布 | 14、17~22 | INT/REL | 设备、离线、安全、升级 |

---

## 10. 单任务输入模板

复制“固定前置指令”后，再附以下模板：

```text
任务 ID：{例如 E4-T2}
任务名称：{名称}
需求引用：{PRD FR ID / 章节}
依赖任务：{必须已经合并的任务}
原型参考：{对应 frontend 文件、页面和操作路径}

本次必须完成：
- {范围 1}
- {范围 2}

本次明确不做：
- {相邻 Epic 或 V1.1 能力}

数据与接口约束：
- {实体、字段、状态机、profileId、版本规则}

交付物：
- {生产代码}
- {测试}
- {Migration/ADR/截图，如适用}

验收：
- Given ... When ... Then ...
- Given ... When ... Then ...

故障与安全：
- {断网/权限/低空间/敏感信息/取消/幂等}

完成后建议下一任务：{ID}
```

---

## 11. Codex 最终回复模板

```text
状态：完成 / 部分完成 / 阻塞

实现摘要：
- ...

变更文件：
- path: 作用

验证结果：
- ./gradlew ...：通过/失败
- UI/设备验证：...

验收证据：
- FR ID：结果
- 截图/测试：路径或说明

决策与假设：
- ...

未完成与风险：
- 无 / ...

下一建议任务：
- TASK-ID
```

---

## 12. 开工前检查表

在把第一个开发任务交给 Codex 前，由项目负责人确认：

- [ ] PRD v2.2 已放入 `docs/specs/`。
- [ ] 本清单已放入仓库，并作为唯一任务顺序来源。
- [ ] React 原型可运行，用于对照页面与交互。
- [ ] 明确 Android 工程位于 `apps/android/`，不会覆盖 `frontend/`。
- [ ] 目标设备或 412dp 等效模拟器可用于截图。
- [ ] ECDICT/NGSL 许可未确认前不提交词典数据包。
- [ ] 没有把任何真实 API Key、PIN 或家庭视频提交到仓库。
- [ ] 每次只给 Codex 一个任务卡，并要求提交测试证据。
- [ ] V1.0 不包含 YouTube、翻译、学习文本、ASR 和时长限制。
- [ ] 发布前必须完成 INT-1、INT-2、INT-3 和 REL-1。

---

## 13. 建议的第一轮 Codex 会话顺序

严格按以下顺序开始，可降低返工：

1. `T0-1` 初始化 Android 工程与持续验证。
2. `T0-2` 测试夹具与质量门禁。
3. `T0-3` 核心模型、接口与错误模型。
4. `T0-4` Compose 设计系统与 Preview catalog。
5. `T0-5` SAF/Media3、安全存储、词典许可三项预研。
6. `E1-T1` 合并并锁定数据库 v1。
7. `E1-T2`、`E1-T3` 完成 Profile 基础交互。
8. `E2-T1` 从最新主分支创建并升级数据库到 v2。
9. `E3-T1` 从最新主分支创建并升级数据库到 v3。
10. 后续严格按第 7 节精确任务 DAG 推进，最终由 E1-T4 验证跨功能隔离。

不要直接让 Codex“一次完成整个 App”。第一轮只发送 `T0-1`，验收并合并后再发送下一任务。这样每次会话都有稳定上下文、明确测试和可回滚提交。
