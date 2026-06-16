# P6-3a 团队真协调 + 团队面板 — Codex 交接

> 本目录 `p6-3a-team-real-coordination/` 是对**已落地但空心**的 P6-3 团队协作的强化补做，**独立于原 `p6-3-team-collaboration/`（原 P6-3 文档不改动）**。
> 同目录：`design.md`（设计 spec，语义来源）· `plan.md`（逐任务实现计划）· 本文件（Codex 交接）。
> 配套高保真原型：Figma 文件 `frTp55zgrKf4NAWxn6LdI7` 页面「团队协作」（6 帧）。

## 给 Codex 的指令

```
codex "严格执行 docs/superpowers/plans/p6-3a-team-real-coordination/plan.md 的 P6-3a 团队真协调 + 团队面板补做。开工前先读同目录 design.md(设计 spec,语义一律以它为准)。先确认 git 工作区干净,建议在 p6-3a 补做分支/worktree 上做。【顺序】T0 执行模型 spike 必须先过(锁定 Path A 自驱循环 / Path B StateGraph+interrupt)再动 T1+;之后按 T1→T2→…→T11 顺序严格 TDD:先写会失败的测试、跑红、再实现到绿,每个任务一笔中文 conventional commit 带 feat(p6-3):/fix(p6-3):/test(p6-3):/docs(p6-3): 前缀,不揉大提交。严守 plan §0 元约束与红线:不升级 Spring AI / Spring AI Alibaba 版本、成员薄封装官方 ReactAgent 不自研引擎、团队继续 approve-once 团队级授权且只冻结结构(成员/工具/写入范围/沙箱)不冻结目标、团队 chatter 不进主对话 messages 流、不加团队 nav tab 也不做独立团队页(团队=主对话右侧可开合面板)、新表必须同步 SQL 中文注释+bq_schema_comments+覆盖测试;现状做对的部分(supervisor 真循环/白名单归一化/maxRounds/approve-once/WorkUnit goalId 闸门/SchemaCommentsCoverageTest/AgentLoopLineCountTest)回归不能破。完成后跑 plan T11 验证:backend `.\mvnw.cmd clean verify`、desktop `.\gradlew.bat test --rerun-tasks` 要求真执行全绿。【最重要】严禁把『未实现』写成『未验证』——每个声称已实现的功能必须在完成报告里附该功能的代码位置 file:line;人工烟测(真实模型多成员协作、supervisor 看得见成员产出、结果聚合回主 Agent、轮次间喊话、失败态、团队面板多团队切换+自带 composer、可改目标)在无头/无真实 Provider/无可操作桌面环境逐项标『未执行+原因』,绝不标『通过』。桌面 UI 对齐 Figma frTp55zgrKf4NAWxn6LdI7 页面「团队协作」(帧 02 团队面板展开 / 帧 03 成员配置)。不要 git push、不要 tag。"
```

要全自动、不中途交互，把 `codex` 换成 `codex exec`（同 prompt）。

## 两点提醒

1. **T0 spike 是闸门**：Path A（BaBiQ 自驱循环 + 成员 `AgentTool.call` 带 toolContext + `bq_tool_calls` 归属）过不了就回退 Path B；spike 没结论不要动 T1+。这是 spec §10 的头号风险。
2. **人工烟测 Codex 做不了**：多成员真协作、轮次间喊话、团队面板交互这些必须真人在桌面 + 真实 Provider 复验。Codex 跑完会给“自动化全绿 + 人工烟测待执行”，届时仍需你或我在可操作环境复验，P6-3a 才算真闭环。

## 跑完后

把 Codex 的完成报告发回，我（Claude）按 **P8 那轮同样的标准做独立审查——对代码不对报告**：逐任务核实 `file:line`、重新跑 `clean verify` / `gradlew test --rerun-tasks` 拿新鲜证据、确认 T0 spike 结论真实、确认没有“全绿但没做全”。
