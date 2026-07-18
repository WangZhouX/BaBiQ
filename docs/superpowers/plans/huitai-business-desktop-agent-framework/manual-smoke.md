# 汇泰业务桌面 Agent 框架人工烟测记录

## 1. 本次烟测基线

- 时间：2026-07-19（Asia/Shanghai）。
- 分支：`codex/lawyer-oa-desktop`。
- 最终代码提交：`893e169 fix: 闭环业务桌面最终人工烟测`。
- 正式 MSI：`business-desktop/app/build/compose/binaries/main/msi/HuitaiBusinessDesktop-0.1.0.msi`。
- 正式 EXE：`business-desktop/app/build/compose/binaries/main/exe/HuitaiBusinessDesktop-0.1.0.exe`。
- 最终隔离运行目录：`business-desktop/app/build/manual-smoke-final5/home/.huitai-agent-desktop`。
- 最终实模截图：`C:\Users\王校长\.codex\visualizations\2026\07\18\019f74f9-ab53-7f71-bc6c-3796fcd39b3b\manual-smoke-final-pass.png`。

实模验证通过本机已认证的 Claude Code CLI 和临时 loopback OpenAI-compatible 网关完成。网关只用于验收，没有进入仓库或安装包；业务桌面仍通过正式 OneAPI Provider、内置 Agent、JSON-RPC 和上下文装配链路发起请求。`ONEAPI_BASE_URL` 刻意使用带 `/v1` 的地址，验证最终包不会生成重复的 `/v1/v1/chat/completions`。

DeepSeek 官方端点也已实际到达，但账户返回 `402 Insufficient Balance`。该结果证明网络和官方适配器到达端点，不作为最终 Provider PASS；最终 PASS 来自后续实际模型成功回复。

## 2. 自动化基线

| 范围 | 结果 |
|---|---|
| Backend `clean verify` | 203 个套件，933 tests，0 失败、0 错误 |
| 现有 `desktop` 全量测试 | 53 个套件，337 tests，0 失败、0 错误 |
| 新 `business-desktop` 全量测试 | 78 个套件，694 tests，0 失败、0 错误 |
| 正式安装包 smoke | PASS，且明确使用 canonical MSI 路径 |
| 生产未完成标记扫描 | `TODO/FIXME/TBD/待实现`：无 |
| 具体 OA 领域扫描 | 客户、案件、文书、律师生产实现：无 |
| 默认密钥扫描 | 硬编码 DeepSeek 默认 Key：无 |

## 3. 人工与替代证据清单

| 检查项 | 结果 | 证据 |
|---|---|---|
| 首次启动 | PASS | 最终 MSI 解包后真实窗口启动，内置 Agent 完成认证连接 |
| 单实例 | PASS | 同一隔离 home 第二次启动退出码 0，主窗口继续存活 |
| 1440×900、1100×760、820×700 三档宽度 | PASS | `manual-smoke-wide.png`、`manual-smoke-medium.png`、`manual-smoke-narrow.png`；布局代码随后未回退 |
| 通用七字段表单 | PASS | 名称、类型、联系人、金额、日期、状态、详细说明均可见 |
| 用户编辑与 revision | PASS | 名称改为“手工烟测资料”，revision 由 1 递增到 2 |
| Agent 读取页面上下文 | PASS | 最终实模回复“资料名称：未命名资料；页面版本：revision 1” |
| 可用 Provider 非结构化聊天 | PASS | `oneapi-relay / claude-sonnet-cli` 实际 Turn `COMPLETED`，Tokens 2 |
| 字段来源与置信度 | PASS（自动化替代） | `BusinessDesktopShellTest`、`FormPatchValidatorTest` 覆盖来源/置信度展示和边界 |
| 单字段接受、全部接受 | PASS（自动化替代） | `BusinessDesktopShellTest`、`DemoScreenModelTest` 覆盖 fieldId、baseRevision 和接受回调 |
| stale revision | PASS（自动化替代） | `DemoScreenModelTest`、`DemoActionBusIntegrationTest` 拒绝旧 revision |
| 普通保存预览 | PASS | 人工出现“确认动作预览”，取消后执行状态为 `CANCELED` |
| 高风险提交批准 | PASS | 人工出现独立高风险审批，勾选绑定确认后执行状态为 `SUCCEEDED` |
| 高风险提交拒绝 | PASS | 人工拒绝后执行状态为 `CANCELED`，审计事件为 `approval_denied` |
| 响应丢失与 reconciliation | PASS（自动化替代） | `ActionReconciliationTest`、`ApplicationActionReconciliationServiceTest`、`SQLiteApplicationActionTerminalStoreTest` |
| Agent 断开与重连 | PASS（自动化替代） | `ApplicationProtocolReconnectIT`、`BusinessDesktopFrameworkIT` |
| 认证与 membership 错误展示 | PASS（自动化替代） | `BusinessDesktopReducerTest`、`BusinessAgentPanelTest` |
| 审计记录 | PASS | 人工只读检查 SQLite：批准、拒绝、取消均带 identity scope 和相关时间；`SQLiteActionAuditPortTest` 全绿 |
| 正常关窗与子进程退出 | PASS | 最终包关窗后 1 秒左右 `OWNED_REMAINING=0` |

人工动作预览、批准、拒绝和 SQLite 审计检查在同一 Task 37 会话的前序安装包上完成；后续仅修复首次对话、Provider 引导、Base URL、错误清理、关窗和 smoke 产物选择。相同动作核心随后重新通过 694 项全量测试和最终安装包 smoke。

## 4. 烟测中发现并闭环的问题

1. 首次 Thread 尚未创建时 composer 被错误禁用。
2. 正常关闭窗口后内置 Agent 子进程未退出。
3. Provider 引导环境被过度清理，安装包无法接收显式配置的 Provider。
4. OpenAI-compatible Base URL 以 `/v1` 结尾时形成重复版本路径。
5. 成功 Turn 后旧 `turn_failed` 仍显示。
6. 安装包 smoke 会误选 `app/build` 下人工解包产生的 MSI。
7. `application.yml` 含不应存在的默认 API Key。
8. SQLite 终态采纳测试的 20ms 时限低于真实持久化抖动。

以上问题均先由失败测试或人工失败复现，再完成修复、全量回归和最终安装包复验。

## 5. 结论

人工烟测结论：**PASS**。最终安装包能启动、连接隔离的内置 Agent、使用实际模型读取页面上下文、保持错误状态正确、拒绝第二实例，并在正常关窗后结束全部所属进程。
