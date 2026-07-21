# 翔鸟律智桌面端壳层与小律助手 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将业务桌面改为默认最大化的“翔鸟律智桌面端”，用顶部导航替代左侧栏，并通过右下角吉祥物控制不遮挡业务内容、可拖拽宽度的“小律智能助手”停靠分栏。

**Architecture:** 窗口与品牌使用集中规格和打包资源；顶部导航继续消费唯一 `BusinessDesktopDestination`。助手由顶层持有展开状态和请求宽度，纯 `BusinessDesktopLayoutPolicy` 负责 640dp 业务区、360–720dp 助手和 1008dp 展开门槛，Shell 只按结果渲染“业务区 + 分隔条 + 助手区”，不使用覆盖式对话框。

**Tech Stack:** Kotlin/JVM 21、Compose Desktop 1.11、Material 3、Compose UI Test、Gradle/JPackage、PNG/ICO。

---

## Chunk 1: 品牌资源与窗口行为

### Task 1: 打包 Web Logo 和透明高清吉祥物

**Files:**
- Create: `business-desktop/app/src/main/resources/brand/xiangniao-logo.png`
- Create: `business-desktop/app/src/main/resources/brand/xiaolv-mascot.png`
- Create: `business-desktop/app/src/main/resources/brand/xiangniao.ico`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/brand/BusinessBrandResources.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/brand/BusinessBrandResourcesTest.kt`

- [ ] 写资源失败测试：三个品牌资源均能从 classpath/仓库读取；Logo 为 PNG，吉祥物至少 512×512 且包含 Alpha，ICO 存在多个尺寸；资源加载器不引用 Web 工程或 Temp 绝对路径。
- [ ] 运行 `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessBrandResourcesTest"`，确认因资源/加载器缺失而失败。
- [ ] 原样复制 Web `src/assets/imgs/logo.png`；使用 `imagegen` 以用户附图为参考移除背景、居中并高清化到至少 512×512，保持角色形象、挥手动作、服装、法槌和配色不变；从 Web Logo 生成多尺寸 Windows ICO。
- [ ] 实现集中资源路径和 classpath 解码；生产 UI 只通过该入口加载资源。
- [ ] 重跑测试确认通过，并人工查看 PNG/ICO 透明度、边缘和清晰度。
- [ ] 使用中文提交：`feat(桌面): 加入翔鸟律智品牌资源`。

### Task 2: 默认最大化窗口和原生安装品牌

**Files:**
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/window/BusinessDesktopWindowSpec.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/window/BusinessDesktopWindowSpecTest.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/window/BusinessDesktopWindowBindingContractTest.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/NativeDistributionBrandingContractTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt`
- Modify: `business-desktop/app/build.gradle.kts`

- [ ] 写失败测试：产品标题为“翔鸟律智桌面端”、默认 placement 为 maximized、还原尺寸 1440×900、最小尺寸 1100×720、窗口图标资源为正式 Logo；窗口绑定契约验证 `Main` 实际消费规格对象并设置 AWT minimum size；Gradle 契约验证产品名、快捷方式、开始菜单和 ICO 均已配置且不再硬编码 `HuitaiBusinessDesktop`。
- [ ] 运行定向测试，确认缺少规格对象时失败。
- [ ] 实现 `BusinessDesktopWindowSpec`，让 `Main` 使用 `rememberWindowState(placement = Maximized, width = 1440.dp, height = 900.dp)`、Logo Painter 和 AWT 最小尺寸；保留系统标题栏。
- [ ] 更新 JPackage 的用户可见产品名、快捷方式、菜单项和 Windows ICO；内部必要标识与显示名分离。
- [ ] 重跑定向测试和 `cd business-desktop; .\gradlew.bat :app:compileKotlin`。
- [ ] 使用中文提交：`feat(桌面): 统一窗口与安装品牌`。

---

## Chunk 2: 顶部导航与停靠布局

### Task 3: 用纯策略固定助手分栏边界

**Files:**
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicy.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicyTest.kt`

- [ ] 先改测试定义新契约：收起时业务区占全宽；展开时总宽度严格相等且不重叠；助手宽度钳制 360–720dp；业务区至少 640dp；小于 1008dp 时 `canExpand=false`；拖动分隔条向左增加助手宽度、向右减少；非法宽度归零。
- [ ] 运行定向测试，确认旧三栏/固定 rail 实现按预期失败。
- [ ] 最小实现新布局结果与 `resizeAssistantWidth(current, dragDeltaX, availableWidth)`；删除左导航和 collapsed rail 宽度概念。
- [ ] 重跑定向测试确认通过并清理旧常量。
- [ ] 使用中文提交：`refactor(桌面): 重构小律助手停靠布局策略`。

### Task 4: 新增顶部一级导航

**Files:**
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessTopNavigation.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessTopNavigationTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopDestination.kt`

- [ ] 写 Compose 失败测试：顶部出现 Logo、“翔鸟律智桌面端”、工作台/资料录入/运行记录；“设置”固定为独立右侧入口；点击回传 canonical destination；不出现用户可见 Agent 入口。
- [ ] 分别用 1100dp 最小窗口宽度和小于 1008dp 的测试容器验证品牌、三个主导航、右侧设置、选中状态仍完整可达，并且不恢复左侧栏。
- [ ] 运行定向测试，确认组件缺失而失败。
- [ ] 实现约 64dp 顶栏、品牌区、主导航和右侧设置按钮；增加稳定 test tag/content description；中等宽度缩短间距但不恢复左侧栏。
- [ ] 重跑测试确认通过。
- [ ] 使用中文提交：`feat(桌面): 将一级导航迁移到顶部`。

### Task 5: 吉祥物按钮与可访问拖拽分隔条

**Files:**
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAssistantMascotButton.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAssistantResizeHandle.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAssistantChromeTest.kt`

- [ ] 写 Compose 失败测试：吉祥物至少 112×112dp 热区，收起/展开语义分别为“打开/收回小律智能助手”；点击切换；分隔条语义为“调整小律智能助手宽度”。测试通过真实 pointer drag 覆盖向左增宽、向右减宽，并在 2.0 density 测试中确认像素经 `LocalDensity` 转成 dp；获得焦点后的左右键产生正负 16dp 宽度意图并经过策略钳制。
- [ ] 运行定向测试，确认组件缺失而失败。
- [ ] 实现透明吉祥物、`ContentScale.Fit`、鼠标悬停提示与点击；实现 12dp 命中/1dp 视觉分隔条、水平缩放光标、拖拽和可聚焦方向键微调。
- [ ] 重跑测试，并检查吉祥物展开/收起安全边距参数。
- [ ] 使用中文提交：`feat(桌面): 增加小律吉祥物与宽度调节`。

---

## Chunk 3: Shell 集成与文案统一

### Task 6: 重组 Shell 为顶部导航和不遮挡停靠分栏

**Files:**
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanel.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanelTest.kt`
- Delete: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessSidebar.kt`
- Delete: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentCollapsedRail.kt`

- [ ] 先重写 Shell 测试：默认收起时顶部导航、业务区全宽和吉祥物存在；不出现 sidebar/52dp rail；点击吉祥物后出现助手且 `businessWidth + dividerWidth + assistantWidth == contentWidth`；业务区至少 640dp；真实拖拽与键盘微调改变助手宽度；再次点击/头部收回后业务区恢复全宽；不足 1008dp 时不展开并显示稳定提示。
- [ ] 为 `BusinessAgentPanel` 增加明确的 mascot slot/底部安全区契约。使用 Compose bounds 测试分别断言展开态吉祥物与输入框、附件按钮、发送按钮不相交，收起态吉祥物与“保存草稿”“提交资料”不相交；附件错误、附件 chip 和多行输入状态也必须满足。
- [ ] 运行定向测试，确认旧 Shell 行为失败。
- [ ] Shell 改为 `Column(topNavigation, content)`；内容使用纯布局策略和 `Row` 渲染业务区/分隔条/助手区；吉祥物在收起时位于业务区右下角、展开时位于助手输入区上方安全位置。
- [ ] `Main` 默认 `assistantExpanded=false`，持有请求宽度 460dp 并消费展开、拖拽和不足宽度提示回调；导航切换与收起不丢失会话和宽度。
- [ ] 删除不再引用的左侧栏与 collapsed rail，重跑 Shell、导航、布局和 Agent 聚焦测试。
- [ ] 使用中文提交：`feat(桌面): 接通不遮挡的小律助手分栏`。

### Task 7: 统一小律用户文案

**Files:**
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanel.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/AgentComposer.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/action/ActionPreviewDialog.kt`
- Modify: other `business-desktop/app/src/main/kotlin/**` files found by the audit only when the text is user-visible
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanelTest.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/brand/BusinessVisibleCopyAuditTest.kt`

- [ ] 写失败测试：标题“小律智能助手”、收回语义、助手消息署名“小律”、输入提示和动作建议文案；源码/资源审计禁止用户可见“汇泰业务桌面 Agent”“业务 Agent”，并对允许的内部 Agent 类名/test tag 建立窄白名单。
- [ ] 运行测试确认旧文案导致失败。
- [ ] 修改所有用户可见文案；不改协议字段、内部类名和稳定 test tag。
- [ ] 重跑文案和助手测试，使用 `rg` 人工复核剩余 Agent 字符串均为内部标识或历史文档。
- [ ] 使用中文提交：`refactor(桌面): 统一小律智能助手文案`。

---

## Chunk 4: 回归、安装包与真实视觉验收

### Task 8: 自动化、打包和视觉验收

**Files:**
- Modify: `docs/superpowers/plans/2026-07-21-xiangniao-desktop-shell.md`（勾选证据）
- Create if needed: `docs/superpowers/plans/2026-07-21-xiangniao-desktop-shell-visual-qa.md`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeProbe.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeProbeTest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagingScriptContractTest.kt`
- Modify: `business-desktop/scripts/smoke-packaged-distribution.ps1`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt`
- Modify: `business-desktop/app/build.gradle.kts`

- [ ] 运行所有新增/修改聚焦测试并记录测试数与失败数。
- [ ] 运行 `cd business-desktop; .\gradlew.bat test --rerun-tasks --no-daemon --max-workers=2 --console=plain`，确认 0 failures/errors。
- [ ] 独立启动 Business Backend 和 Business Frontend，真实检查默认最大化、系统最小化/还原/最大化、顶部设置、Logo、吉祥物、展开/收回、拖拽、业务区不遮挡与会话状态保留；保存截图证据。
- [ ] 先扩展 smoke/脚本失败测试：旧 `HuitaiBusinessDesktop.exe` 硬编码必须消失；脚本必须定位“翔鸟律智桌面端”产物并验证 MSI/EXE 产品名和可提取的 Windows 图标；packaged probe 只有在 Compose Window 已创建且品牌资源成功解码、顶部导航和默认收起布局已组成后才允许写报告。
- [ ] 修改 `PackagedSmokeProbe`/Main 的触发时机和非敏感证据字段，让安装包烟测经过真实 Window composition 后再退出；修改 PowerShell 验证新 exe/产品元数据、品牌资源、图标、后端 jar 和运行时安全证据。
- [ ] 运行 `cd business-desktop; .\gradlew.bat :app:createDistributable :app:packageMsi :app:packageExe`，再运行 `cd business-desktop; .\gradlew.bat :app:smokePackagedDistribution`；让 smoke 同时检查 MSI 与 EXE 两类产物，并验证安装品牌、Windows 图标、classpath 资源和窗口组成路径。只有环境明确不支持某种 JPackage 产物时才记录原因并运行等价可分发目录烟测。
- [ ] 运行 `git diff --check`，保留并记录用户原有无关脏工作区改动。
- [ ] 分派规格和代码质量只读复审，修复全部 Critical/Important 后重新验证。
- [ ] 用中文提交验收文档：`docs(桌面): 记录翔鸟律智桌面改版验收`。
