# 0035 — 月牙猫头鹰启动图标

日期：2026-09-30。补充设计需求，不推进正式任务卡。
基线：`a285b24`，分支：`codex/app-icon-design`。仅本地提交，不推送。

## 设计依据与决定

完整阅读 `docs/specs/「家庭版 LazyEng」视频学英语App 产品需求文档PRD v2.2（最终对齐版）.md`。
主要用户是约 8 岁儿童，家长负责内容与设置；图标应呈现温和的学习伙伴。
使用简化猫头鹰、大眼睛和月牙胸章。月牙延续 `ProfileComponents.kt` 的
`ProfileAvatar("moon")`，不更改现有头像。省略学士帽、文字、字幕气泡和播放按钮，
减少 48px 下的视觉噪声；圆润主体、大面积留白与有限颜色兼顾孩子亲和力和学习工具气质。

脚本直接读取 `core/designsystem` 的 `Color.kt` 浅色 token：
primaryMint `#34D399`、primaryDeep `#138A66`、background `#FAF8F3`、surface `#FFFFFF`。
唯一暖色为 warning `#F5B83D`，只用于小喙。圆角方形预览使用 108dp 上的 20dp 圆角比例，
与 T0-4 prominentCard token 呼应；真实自适应遮罩由启动器决定。

## 生成与资源

运行 `python tools/generate_app_icon.py`（Python + Pillow，无网络素材/生成 API）。
1296px 主画布，108dp 坐标，Lanczos 降采样。
前景透明，背景纯薄荷绿；导出 432×432 分层图、两份 adaptive XML、五密度传统图标。
传统合成图使用中心 72dp 可见视口，再套圆角方形/圆形遮罩，避免传统启动器中主体过小。
预览展示方形/圆角方形/圆形及真实 48px；另存完整 108dp 安全区导览图。

脚本检查全部非透明像素到中心距离：主画布 32.64dp，432px 导出包含滤波边缘 32.96dp，
均小于 66dp 安全圆的 33dp 半径。

## 接线例外

原 Manifest 引用 `@android:drawable/sym_def_app_icon`，新增资源无法替换系统机器人。
用户在本次对话明确授权仅修改 android:icon 为 `@mipmap/ic_launcher`，并添加
android:roundIcon 为 `@mipmap/ic_launcher_round`。未改动其他 Manifest 内容。

## 设备与验收

小米 10S 未连接，用户明确授权改用当前小米 15 Ultra（25019PNF3C）。
构建工具使用本机既有 `<jdk17>`，SDK 为 `<android-sdk>`。
ENV_NOTES 记录的 JDK 21 能启动 Gradle，但不能满足此工程 JVM 17 toolchain；没有下载新 JDK。

`:app:assembleDebug :app:lintDebug` 通过。Lint 为 0 errors、26 warnings；
本需求指定的目录带来 2 条 IconLocation（drawable 中无密度位图）及 1 条 ObsoleteSdkInt
（minSdk 26 时 v26 限定冗余）。保留规定目录，不隐藏警告；其余是版本更新提示、
现有固定方向及 UseKtx 提示。报告在 `evidence/app-icon-design/lint-results-debug.txt`。

在线 Lint 首次失败于 Google Maven TLS 握手；直接 offline 也无法解析缓存的仓库元数据。
将本机 Gradle 已有的原版本 pom/aar/jar/module 用硬链接整理到本地 Maven 目录，
通过临时 init script 添加缓存源后，offline 完整构建与 Lint 成功。
没有更换依赖、关闭 Lint 检查或修改项目仓库配置。
最终日志：`evidence/app-icon-design/build-lint-local-cache.log`。

设备原安装包为家庭发布签名，普通 Debug 覆盖报 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
使用现有家庭密钥通过 apksigner 给 assembleDebug 产物签名，再 adb install -r：Success。
没有卸载或清除数据；密码通过 env 参数读取，不写入文件或日志。
设备当前安装的是同发布密钥签名的 Debug 验收包，不是新的正式 Release 交付包。
启动 `.MainActivity` 返回 Status: ok。

重新采集并人工查看 `evidence/app-icon-design/xiaomi-15-ultra-launcher.png`：
真实 MIUI 桌面的 LazyEng 家庭版入口显示新薄荷绿猫头鹰，大眼睛、暖黄喙和月牙完整，
不再显示系统机器人。三种遮罩与 48px 预览为 `icon-mask-preview.png`，
安全区图为 `adaptive-safe-zone.png`。

## 限制

48px 下大眼睛和主体轮廓清楚，月牙作为辅助品牌细节，小于该尺寸可能不易辨认。
三种标准遮罩无主体裁切；OEM 主题可能自行改变图标效果。
不提供 Android 13 单色 themed-icon（本需求未要求）。
