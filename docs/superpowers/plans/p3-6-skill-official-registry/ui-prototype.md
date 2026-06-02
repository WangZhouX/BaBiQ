# P3-6 技能官方注册表前端原型

> 状态：原型草案，待进入实现计划前确认。
> 来源：用户参考 Codex 桌面端“插件 / 技能”截图，希望 BaBiQ 在 P3-6 官方
> `SkillRegistry` 接入前先明确技能目录 UI。

---

## 0. 一句话结论

P3-6 前端原型建议新增一个独立的 **“插件 / 技能工作台”** 产品页：

- **插件**：先作为未来扩展入口保留空态，不在 P3-6 实现真实市场或安装。
- **技能**：本阶段主页面，展示 `skills/list` 扫描出的 `.agents/skills` 技能目录，
  并在用户点击时通过 `skills/get` 按需读取 `SKILL.md` 正文。

它不应继续藏在“设置 > 能力”里。设置页负责运行策略和能力暴露模式；技能页负责让用户浏览、搜索、理解和检查本机/项目 Skill。

---

## 1. 信息架构

### 1.1 Sidebar 入口

BaBiQ 左侧导航建议变为：

```text
BaBiQ

+ 新对话
搜索
插件          <- 新增真实产品入口，进入后默认选中“技能”页签
本地 MCP
自动化        <- 仍可保持禁用占位

项目
...

最近
...

设置
```

设计原因：

- Codex 截图中“插件”是一级入口，“技能”是插件页内的二级页签。
- BaBiQ 目前已有“本地 MCP”和“设置 > 能力”，如果再把 Skill 只放在设置页里，用户会把“技能目录”和“工具暴露策略”混在一起。
- P3-6 是官方 Skill Registry 接入，天然需要一个可浏览的 registry 页面。

### 1.2 主页面页签

```text
[插件] [技能]                                  [管理] [创建 v] [...]
```

- `插件`：未来放插件市场、MCP server 包、远程安装等能力；P3-6 只做空态。
- `技能`：当前阶段真实接入 `skills/list` 和 `skills/get`。
- `管理`：查看扫描目录、手动刷新、打开目录。
- `创建`：未来创建 `SKILL.md`，P3-6 可先禁用或只展示菜单空态。

---

## 2. 技能页默认态

### 2.1 视觉结构

```text
              让 BaBiQ 按你的方式工作

[ 搜索技能................................................ ] [全部 v]

推荐
------------------------------------------------------------
找不到技能

系统
------------------------------------------------------------
[图标] Image Gen          Generate or edit images...       ✓
[图标] OpenAI Docs        Reference OpenAI docs...         ✓
[图标] Skill Creator      Create or update a skill         ✓

项目
------------------------------------------------------------
[图标] 项目代码审查       当前工作区 .agents/skills        ✓
[图标] 后端排查助手       当前工作区 .agents/skills        ✓

个人
------------------------------------------------------------
[图标] Ask Claude         本机用户技能                     ✓
[图标] Ai Slop Cleaner    本机用户技能                     ✓
```

### 2.2 分组规则

| 分组 | 来源判断 | 说明 |
| --- | --- | --- |
| 推荐 | 暂无真实推荐算法 | P3-6 可显示空态“找不到技能” |
| 系统 | 后续 bundled/classpath skill | P3-6 后端若暂未提供系统来源，可隐藏该分组 |
| 项目 | `<cwd>/.agents/skills` | 随当前工作区切换而变化 |
| 个人 | `~/.agents/skills` | 当前用户所有项目共享 |
| 额外目录 | `additional-directories` | 可作为单独分组或归入“个人”并显示路径来源 |

### 2.3 技能卡片字段

每个技能卡片保持轻量，不展示大段正文：

```text
[图标] 技能名称
       一行描述，最多两行截断
       来源: 项目 · namespace: review · 允许工具 3
                                                        ✓
```

字段映射：

| UI 字段 | 后端来源 | 说明 |
| --- | --- | --- |
| 技能名称 | `SkillInfo.name` | `SKILL.md` front matter 的 `name` |
| 描述 | `SkillInfo.description` | 最多两行截断 |
| 来源 | `sourceDirectory` 推导 | 用户级、项目级、系统、额外目录 |
| namespace | `SkillInfo.namespace` | 便于调试同名技能来源 |
| 允许工具 | `allowedTools.size` | P3-6 后端新增后展示；为空显示“未声明工具限制” |
| 勾选状态 | `enabled` 或未来配置 | P3-6 若无启用字段，显示“可用”只读态 |

---

## 3. 搜索和筛选状态

### 3.1 搜索输入

搜索框占据主区域顶部，行为先做前端过滤：

- 匹配 `name`
- 匹配 `description`
- 匹配 `namespace`
- 匹配 `sourceDirectory`
- 未来可接后端 `SkillRegistry.search()` 或 BaBiQ capability/Lucene 搜索

### 3.2 筛选器

```text
[全部 v]
```

菜单项：

- 全部
- 系统
- 项目
- 个人
- 已启用
- 已停用

P3-6 如果后端暂时没有启用/停用字段，`已启用 / 已停用` 可先隐藏，避免 UI 暗示不存在的能力。

### 3.3 无结果

```text
推荐
------------------------------------------------------------
找不到技能

系统 / 项目 / 个人分组隐藏
```

无结果文案避免长说明，只保留一个短句；详细原因放到管理弹窗或错误提示。

---

## 4. 技能详情抽屉

点击技能卡片后，从右侧打开详情抽屉。抽屉不阻塞主列表滚动。

```text
技能详情                                      [关闭]

项目代码审查
项目 · review.code_review

路径
E:\BaBiQ\.agents\skills\code-review\SKILL.md

状态
可用

允许工具
read_file · grep · list_dir

[概览] [正文] [工具] [能力映射]

概览
- 描述: ...
- contentHash: 9f2a...
- sourceDirectory: ...
- namespace: review
- id: review.code_review

[读取正文]
```

### 4.1 概览 Tab

默认展示概览，不读取完整正文。

原因：

- 保持 P3-6 “按需读取 Skill 正文”的后端语义。
- 避免打开页面就把所有 `SKILL.md` 大段内容加载到 UI。
- 用户需要详情时才调用 `skills/get`。

### 4.2 正文 Tab

用户点击 `正文` 或 `读取正文` 后调用：

```text
skills/get { "skillId": "<id>" }
```

正文展示规则：

- Markdown 以等宽/预格式文本展示即可，不要求第一版渲染 Markdown。
- 如果 `truncated=true`，在顶部显示“正文已按后端上限截断”。
- 支持复制选中文本。

### 4.3 工具 Tab

展示 `allowedTools`：

```text
允许工具
- read_file
- grep
- list_dir

说明
这些字段只表示 Skill 声明的工具偏好；实际工具执行仍由 BaBiQ 的 ToolRegistry、审批和沙箱决定。
```

这个说明是必须的，避免用户误以为 `allowedTools` 等于安全授权。

### 4.4 能力映射 Tab

展示该 Skill 对应的 capability：

```text
Capability ID
skill.review.code_review

暴露模式
DEFERRED

搜索文本
来自 Skill metadata + 中文别名字典
```

如果当前 capability 数据还未同步，可显示“等待能力目录同步”。

---

## 5. 管理弹窗

点击顶部 `管理` 打开轻量弹窗：

```text
技能管理

扫描目录
用户级     ~\.agents\skills
项目级     <cwd>\.agents\skills
额外目录   0 个

[重新扫描] [打开用户目录] [打开项目目录]

迁移提示
旧的 ~/.codex/skills 不再默认扫描；如需继续使用，请迁移到 ~/.agents/skills
或加入 additional-directories。
```

P3-6 后端如果暂时没有“重新扫描”方法，按钮可先触发重新调用 `skills/list`；如果 adapter 层每次 list 前 reload，这个行为就已经等价于刷新。

---

## 6. 创建菜单

点击 `创建 v`：

```text
新建用户技能       暂未开放
新建项目技能       暂未开放
从模板创建         暂未开放
导入本地目录       暂未开放
```

P3-6 不实现写文件或安装技能。创建菜单只作为未来阶段入口；如果担心误导，可以第一版完全隐藏 `创建`，只保留 `管理`。

---

## 7. 数据对接边界

### 7.1 第一版真实对接

| 用户动作 | 后端方法 | 说明 |
| --- | --- | --- |
| 打开技能页 | `skills/list` | 拉取 metadata，不读正文 |
| 搜索技能 | 前端过滤 | 不发请求 |
| 点击技能详情 | 无或本地状态 | 先只展示 metadata |
| 点击正文 | `skills/get` | 按需读取正文 |
| 重新扫描 | `skills/list` | 如果后端 list 内部 reload，则等价刷新 |

### 7.2 暂不对接

| 功能 | 原因 |
| --- | --- |
| 启用/停用技能 | 需要类似 Codex `skills/config/write` 的配置接口，P3-6 后端计划尚未实现 |
| 新建技能 | 涉及文件写入、模板和权限提示，独立阶段更稳 |
| 远程安装/市场 | 涉及下载、解压、路径穿越防护和签名校验，不能混入 P3-6 |
| 自动执行 skill 脚本 | 与 BaBiQ 审批/沙箱边界相关，明确不做 |

---

## 8. 与现有页面的关系

### 8.1 搜索工作台

现有搜索工作台里的 `Skill` 卡片只保留摘要：

- Skill 数量
- 最近加载错误
- 能力搜索联动

它不再承担完整技能目录职责。

### 8.2 设置页能力中心

设置页 `能力中心` 继续管理：

- local 工具
- MCP 工具
- Skill 能力 capability
- `VISIBLE / DEFERRED / HIDDEN`
- enabled 状态

技能页只负责 Skill Registry 浏览；真正的工具暴露策略仍在能力中心。

### 8.3 本地 MCP 页

本地 MCP 页继续展示 MCP server 和 tool schema。Skill 页不要混入 MCP server 编辑，避免“技能”和“工具服务”概念重叠。

---

## 9. 原型验收点

进入实现计划前，原型需满足：

1. Sidebar 有清晰的 `插件` 入口，`技能` 是插件页内二级页签。
2. 页面能展示 `skills/list` 的 metadata，不需要 `skills/get` 就能完成首屏。
3. `SKILL.md` 正文必须按需读取，不能首屏全量加载。
4. `allowedTools` 展示为“声明偏好”，不能表现为安全授权。
5. 管理弹窗明确展示 `.agents/skills` 路径和 `~/.codex/skills` 迁移提示。
6. 搜索、空态、详情抽屉、正文截断状态都有明确 UI。

---

## 10. 后续实现建议

如果用户确认该原型，下一步实现计划建议拆成：

1. 桌面端新增 `Screen.Plugins` 和 Sidebar 入口。
2. 新增 `PluginSkillPanel`，内含 `插件 / 技能` 页签。
3. 复用现有 `SkillUiState`，扩展前端展示模型，不急着改后端。
4. 补 `SkillInfo.allowedTools` 兼容字段后，再显示工具摘要。
5. 技能详情抽屉接 `skills/get`，正文按需读取。
6. 管理弹窗先做只读目录展示和刷新按钮，写入配置留到后续。
