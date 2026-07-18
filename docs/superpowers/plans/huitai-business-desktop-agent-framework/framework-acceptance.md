# 汇泰业务桌面 Agent 通用框架验收报告

## 1. 验收结论

结论：**通过**。

Task 29–37 已闭环，纯 Kotlin Compose 业务桌面通用框架满足设计文档的 17 条验收条件。当前阶段没有迁移客户、案件、文书等具体 OA 业务；下一阶段可以基于本框架另行编写业务迁移计划。

## 2. 验收矩阵

| # | 验收条件 | 结果 | 主要证据 |
|---:|---|---|---|
| 1 | `business-desktop` 可构建、测试、启动 | PASS | 78 套件 / 694 tests；最终 MSI/EXE；人工启动成功 |
| 2 | 三个核心与两个底座均为真实实现 | PASS | `presentation-core`、`application-action-core`、`agent-client-core`、`huitai-integration-core`、`security-audit-core` 全量测试 |
| 3 | Catalog、Context、Action Result 端到端 | PASS | `BusinessDesktopFrameworkIT`、`BusinessDesktopBackendCompatibilityIT`、实模页面上下文回复 |
| 4 | 不依赖 `huitai_cloud` | PASS | 模块和生产源码扫描无依赖 |
| 5 | 用户与 Agent 共用同一 ActionBus | PASS | Composition root 同一实例断言、框架 IT、`DemoActionBusIntegrationTest` |
| 6 | 写动作预览，高风险独立审批 | PASS | 人工保存预览、提交批准/拒绝；`ApplicationActionBusWriteTest` |
| 7 | 幂等、权限、revision、断线、超时、迟到响应 | PASS | `ActionIdempotencyTest`、`ActionReconciliationTest`、`ApplicationProtocolReconnectIT` 等 |
| 8 | Agent 不读取 Token/Secret | PASS | 业务工具 allowlist、上下文脱敏、JCEKS、默认密钥防回归测试 |
| 9 | 用户与 Agent 动作均有关联审计 | PASS | 人工 SQLite 审计、`SQLiteActionAuditPortTest`、框架 IT |
| 10 | 安装包自动启动并关闭本地 Agent | PASS | canonical MSI smoke；最终人工关窗 `OWNED_REMAINING=0` |
| 11 | 不包含具体 OA 生产实现 | PASS | 客户/案件/文书/律师生产源码扫描为空 |
| 12 | Backend、旧 desktop、新 business-desktop 全绿 | PASS | 933 / 337 / 694 tests，全部 0 失败、0 错误 |
| 13 | 人工烟测和验收报告完成 | PASS | `manual-smoke.md` 与本报告 |
| 14 | 数据库、KeyStore、日志、记忆、锁、恢复隔离 | PASS | 安装包 smoke、`BusinessProfileParallelIsolationIT`、运行路径人工检查 |
| 15 | 业务工具 allowlist 不可绕过 | PASS | `BusinessToolAllowlistIT` 禁止文件、Shell 和未审核 MCP |
| 16 | identity scope 覆盖全链路，长期记忆关闭 | PASS | identity scope 测试、business profile、框架 IT |
| 17 | replay、`OUTCOME_UNKNOWN`、reconciliation 安全 | PASS | `ActionReconciliationTest`、`ApplicationActionReconciliationServiceTest`、终态持久化测试 |

## 3. 新鲜验证结果

```text
backend:          203 suites / 933 tests / 0 failures / 0 errors
desktop:           53 suites / 337 tests / 0 failures / 0 errors
business-desktop:  78 suites / 694 tests / 0 failures / 0 errors
package smoke:     PASS (canonical compose/binaries/main/msi)
real model turn:   COMPLETED (oneapi-relay / claude-sonnet-cli)
normal close:      OWNED_REMAINING=0
```

Windows 中文用户名路径下，Gradle 使用 `E:\gradle-home-ascii`，并以 `--max-workers=1 --no-parallel --no-build-cache` 执行，避免宿主机 worker 路径与并发资源干扰。

## 4. 安全与范围结论

- DeepSeek、DashScope、OneAPI 的显式引导变量采用固定白名单；任意 `API_TOKEN`、数据库密码和桌面 KeyStore 主密码仍不会传给 Agent 子进程。
- Provider 引导 Key 只传给后端子进程做 Provider 初始化并写入 Agent JCEKS；父进程的 ProcessBuilder 在启动后删除本地敏感引导项。
- `application.yml` 不再包含默认真实 Key，`ApplicationYamlSecretDefaultTest` 防止回归。
- 业务 profile 保持长期记忆、MCP 和 Skills 关闭，模型可见工具继续受固定 allowlist 约束。
- 最终 smoke 只接受 canonical Compose MSI，避免使用陈旧或人工解包产物产生假绿。

## 5. 产物

- MSI：`business-desktop/app/build/compose/binaries/main/msi/HuitaiBusinessDesktop-0.1.0.msi`
- EXE：`business-desktop/app/build/compose/binaries/main/exe/HuitaiBusinessDesktop-0.1.0.exe`
- 人工烟测：`docs/superpowers/plans/huitai-business-desktop-agent-framework/manual-smoke.md`
- 交接：`docs/superpowers/plans/huitai-business-desktop-agent-framework/codex-handoff.md`

## 6. 阶段边界

本报告只批准“通用框架完成”。客户、案件、文书、审批等具体 OA 领域模型、页面、动作和远程接口仍需单独分析、设计和验收，不能把本报告当作具体业务已经迁移完成。
