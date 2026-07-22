# 翔鸟律智桌面端壳层改版验收记录

验收日期：2026-07-22

## 结论

通过。翔鸟律智桌面端已使用正式品牌和顶部导航，独立启动时默认最大化；小律智能助手默认收起，展开后使用可调宽停靠分栏推挤业务区，不以浮层覆盖业务表单。

## 独立前后端真实窗口验收

为避免读取或修改用户日常数据，本次使用全新的隔离运行目录，按以下顺序分别启动：

1. `business-desktop\\gradlew.bat :app:runBusinessBackendDevelopment`
2. `business-desktop\\gradlew.bat :app:runBusinessFrontendDevelopment`

验收结果：

- 后端在回环地址启动，前端通过开发会话文件连接；两项服务由两个独立 Gradle 运行配置启动。
- 前端首次出现时窗口截图为 `1920 × 1032`，原生标题栏显示“最小化 / 还原 / 关闭”，证明启动状态为最大化。
- 原生还原状态约为 `1426 × 893`，窗口保留系统标题栏和原生最小化、最大化、关闭控制。
- 顶部左侧展示正式 Logo 和“翔鸟律智桌面端”，中部展示“工作台 / 资料录入 / 运行记录”，右侧独立展示“设置”；不再使用左侧一级导航栏。
- 助手默认收起，业务表单占据全部可用宽度；右下角显示小律吉祥物，未覆盖保存、提交或表单输入区域。
- 点击吉祥物后出现“小律智能助手”右侧停靠栏，业务表单同步缩窄，二者由独立分隔线分开，没有遮挡或层叠。
- 将分隔线向左拖动约 150px 后，助手宽度由约 460px 增至约 610px，业务区同步缩窄且仍完整可操作。
- 再次点击吉祥物后助手收起，业务区恢复全宽；展开、调宽、收起期间表单内容保持不变。
- 隔离目录未配置 Provider，因此助手内的“请求失败，请检查后重试”是预期环境状态，不属于壳层缺陷。
- 验收结束后关闭前端并停止隔离后端，没有删除或修改用户现有密钥库和业务数据库。

## 自动化测试

最终全量命令：

```powershell
cd business-desktop
.\\gradlew.bat test -x :app:packageBusinessBackendJar --rerun-tasks --no-daemon --max-workers=1 "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process"
```

结果：`BUILD SUCCESSFUL`，41 个 Gradle 任务全部执行；102 个 XML 测试套件，共 856 项测试，0 failures、0 errors、0 skipped。

额外回归覆盖包括：

- Compose placement 与 AWT `Frame.MAXIMIZED_BOTH` 双重最大化契约。
- Window、Shell、顶部导航三条真实 composition 信号和已提交帧等待。
- 品牌 Logo、吉祥物和 ICO 资源解码。
- launcher 图标与正式 ICO 的尺寸和归一化像素摘要比对。
- 1×4 与 2×2 位图在像素序列相同时仍必须得到不同摘要。
- 品牌解码失败保留原始异常并写入烟测错误日志。
- 助手默认收起、停靠宽度守恒、最小业务宽度、拖拽与吉祥物安全区。

## MSI / EXE 真实打包烟测

最终命令：

```powershell
cd business-desktop
.\\gradlew.bat :app:smokePackagedDistribution --rerun-tasks --no-daemon --max-workers=1 "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process"
```

结果：`BUILD SUCCESSFUL`，34 个 Gradle 任务全部执行。烟测重新构建后端 JAR、JLink 运行时、可分发目录、EXE 和 MSI，并启动打包后的真实窗口检查品牌及 composition 就绪报告。

产物：

- `business-desktop/app/build/compose/binaries/main/msi/翔鸟律智桌面端-0.1.0.msi`，233,500,217 字节。
- `business-desktop/app/build/compose/binaries/main/exe/翔鸟律智桌面端-0.1.0.exe`，234,105,344 字节。
- 可分发 launcher 的正式品牌图标像素匹配结果：`BRAND_MATCH=True`。
- 烟测结束后的临时 Java 后端残留数：`0`。

## 独立审查

- 规格审查通过：真实 composition 信号、品牌图标、默认收起及吉祥物安全区均符合设计。
- 质量审查提出的图标摘要未包含宽高、烟测异常原因丢失两项问题均已补回归测试并修复。
- 修复后重新执行全量测试和真实安装包烟测，结果均通过。
