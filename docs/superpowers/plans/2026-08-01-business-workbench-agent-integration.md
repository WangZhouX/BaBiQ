# Business Workbench Agent Integration Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已登录业务桌面的真实工作台状态接入 Agent 上下文，并让 Agent 通过现有安全工具链执行允许的工作台读写动作。

**Architecture:** 后端在 OA READY 安装阶段生成并发布稳定、脱敏、绑定身份代次的工作台 catalog/context 投影；`ApplicationContextModelContributor` 继续只读取服务端 registry 的不可信快照。工作台读取能力通过带当前 `BusinessIdentityScope` 的窄工具调用 BFF，写操作复用 `BusinessScheduleService` 与现有审批、沙箱、观测和 SQLite 工具调用审计，不让 OA token、密码或远程 DTO 进入 Agent 上下文。

**Tech Stack:** Java 21, Spring Boot, Spring AI ToolCallback, SQLite audit, Jackson, JUnit 5/Mockito.

---

## Chunk 1: Define the trusted workbench projection

### Task 1: Add the failing contract tests

**Files:**
- Modify: `backend/src/test/java/com/wzx/babiq/server/business/identity/BusinessOaReadyInstallerTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/application/policy/BusinessAgentModePolicyTest.java`
- Reuse: `backend/src/test/java/com/wzx/babiq/server/application/context/ApplicationContextModelContributorTest.java`

- [x] **Step 1: Write the failing tests**
  - Assert READY installation publishes non-empty `actions` and a `pageContext` containing the workbench page id, current identity epoch, workbench navigation and safe section summaries.
  - Assert a second published workbench projection has a strictly larger context sequence and preserves catalog/context epoch alignment.
  - Assert the contributor renders the real workbench projection while omitting password/token/remote URL fields.
  - Assert business mode exposes the workbench read/action tool names in a fixed allowlist while retaining `application_action` and `update_plan`.

- [x] **Step 2: Run the RED tests**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessOaReadyInstallerTest,BusinessAgentModePolicyTest,ApplicationContextModelContributorTest" test
```

Expected: the new projection test fails because READY currently installs empty `actions` and `{}` context; the policy test fails because only `application_action` and `update_plan` are visible.

## Chunk 2: Publish and consume the workbench projection

### Task 2: Implement the server-owned projection

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/business/identity/BusinessOaReadyInstaller.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/policy/BusinessAgentModePolicy.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/context/ApplicationContextModelContributor.java` (only if required by the new projection shape)

- [x] **Step 1: Build a bounded action catalog**
  - Add fixed, versioned read actions for workbench snapshot, navigation, page, team roles, schedule month/day, form and relation options.
  - Add fixed, versioned reversible-write actions for schedule completion, sorting and schedule creation.
  - Every action declares required business permissions, risk, bounded input schema, and a stable title/description.

- [x] **Step 2: Build the initial page context**
  - Publish `pageId=business.workbench`, a positive `contextRevision`, identity epoch/generation, allowlisted navigation, selected workbench scope/kind, and only sanitized summary facts.
  - Keep context under the existing registry byte budget and never include access tokens, passwords, raw OA URLs or unbounded remote payloads.

- [x] **Step 3: Make business tool exposure deterministic**
  - Keep `application_action` and `update_plan`.
  - Add the dedicated workbench read and schedule mutation tool names to the business fixed allowlist.
  - Do not expose generic filesystem, shell, MCP, flow or team tools through business mode.

- [x] **Step 4: Run GREEN**

```powershell
.\mvnw.cmd "-Dtest=BusinessOaReadyInstallerTest,BusinessAgentModePolicyTest,ApplicationContextModelContributorTest" test
```

Expected: all new tests pass and existing contributor/policy tests remain green.

## Chunk 3: Add Agent workbench tools through the existing security chain

### Task 3: Add read-only workbench tool

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/business/agent/BusinessAgentToolSupport.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/agent/BusinessWorkbenchReadTool.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/business/agent/BusinessWorkbenchAgentToolTest.java`

- [x] **Step 1: Write the failing tool test**
  - Install a scoped business identity and a current READY lease.
  - Invoke the read tool with a typed view (`snapshot`, `navigation`, `page`, or `schedule`).
  - Assert it calls the existing BFF service with the current lease/identity, returns sanitized bounded JSON, and rejects missing/stale identity without invoking OA.

- [x] **Step 2: Run RED**

```powershell
.\mvnw.cmd "-Dtest=BusinessWorkbenchAgentToolTest" test
```

- [x] **Step 3: Implement the minimal read tool**
  - Read `BusinessIdentityScope` and `TurnObservationContext` from `ToolContext`.
  - Resolve the active identity through `BusinessIdentityScopeService`.
  - Capture the current READY lease and dispatch only to existing `BusinessWorkbenchService`/`BusinessScheduleService` read methods.
  - Return fixed error codes and bounded sanitized result data; never serialize the lease, identity secrets or OA response body.

- [x] **Step 4: Run GREEN**

```powershell
.\mvnw.cmd "-Dtest=BusinessWorkbenchAgentToolTest" test
```

### Task 4: Add schedule mutation tool with existing approval/audit semantics

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/business/agent/BusinessScheduleMutationTool.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/business/agent/BusinessWorkbenchAgentMutationToolTest.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/policy/BusinessAgentModePolicy.java` if the final mutation tool name differs

- [x] **Step 1: Write the failing mutation tests**
  - Assert unauthenticated, stale-epoch, invalid-team and invalid-assignee requests fail before any OA write.
  - Assert valid schedule completion/sort/create requests delegate to `BusinessScheduleService`.
  - Assert the tool is observed by the existing `ToolObservationInterceptor` and its durable call record is redacted.

- [x] **Step 2: Run RED**

```powershell
.\mvnw.cmd "-Dtest=BusinessWorkbenchAgentMutationToolTest" test
```

- [x] **Step 3: Implement the minimal mutation path**
  - Reuse `BusinessScheduleService` validation and idempotency; do not duplicate team or relation authorization.
  - Mark mutations as reversible/high-risk according to the existing action risk policy so normal approvals, sandbox policy, tool observation, and SQLite audit remain authoritative.
  - Reject arbitrary method names, raw OA payloads and credential-bearing fields.

- [x] **Step 4: Run GREEN**

```powershell
.\mvnw.cmd "-Dtest=BusinessWorkbenchAgentMutationToolTest" test
```

## Chunk 4: Regression verification and delivery

### Task 5: Run focused and full verification

- [x] **Step 1: Run focused backend tests**

```powershell
.\mvnw.cmd "-Dtest=BusinessOaReadyInstallerTest,BusinessAgentModePolicyTest,ApplicationContextModelContributorTest,BusinessWorkbenchAgentToolTest,BusinessWorkbenchAgentMutationToolTest,BusinessToolAllowlistIT,ReActStrategyTest,ToolObservationInterceptorTest,BaBiQSandboxInterceptorTest" test
```

- [x] **Step 2: Run backend full verification**

```powershell
.\mvnw.cmd clean verify
```

- [x] **Step 3: Run desktop regression verification**

```powershell
cd ..\business-desktop
.\gradlew.bat test --no-daemon --max-workers=1 --no-parallel
```

- [x] **Step 4: Check the diff**

```powershell
cd ..\BaBiQ
git diff --check
git status --short
```

Confirm unrelated user changes and all `.tmp-*` directories remain untouched.

Verification result (2026-08-01):

- Focused backend suite: 73 tests, 0 failures, 0 errors, 0 skipped.
- Backend `clean verify`: Java 21, isolated random port and process-only non-default
  KeyStore password; Surefire 244 suites / 1476 tests / 0 failures / 0 errors /
  3 skipped, Failsafe 36 suites / 161 tests / 0 failures / 0 errors.
- Business desktop regression: `BUILD SUCCESSFUL`, 38 actionable tasks
  (2 executed, 36 up-to-date).
- `git diff --check`: exit 0. User-owned YAML, desktop startup-contract tests,
  unrelated design notes and all `.tmp-*` / `tmp/` paths remain outside this task's
  staging boundary.

### Task 6: Commit and close the goal

- [ ] **Step 1: Stage only task files**

```powershell
git add <reviewed task files only>
```

- [ ] **Step 2: Create the Chinese conventional commit**

```powershell
git commit -m "feat(工作台): 接入Agent上下文与安全动作"
```

- [ ] **Step 3: Update the goal only after fresh verification**
  - Mark the goal complete only when all focused tests, backend verification, desktop regression tests, and diff checks have fresh successful output.
