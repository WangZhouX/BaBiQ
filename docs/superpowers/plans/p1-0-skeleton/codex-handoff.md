# P1-0 任务交接给 OpenAI Codex(本地 CLI 版)

> **使用方式**:把本文件**整篇粘贴**给 Codex(或让 Codex `read_file` 这个文件作为任务起点)。所有上下文、约束、验收标准都已自包含,Codex 无需再问其他人。

---

## 0. 你是谁,要做什么

你是 OpenAI Codex,在 **Windows 11 + PowerShell** 环境运行,**cwd 应当是 `F:\wwwxxxx\BaBiQ\`**(如果不是,**第一步切过去**)。

你的任务:执行 **BaBiQ 项目 P1-0(Monorepo 骨架)** 的实施计划。

完整的步骤级 plan 在:
**[F:\wwwxxxx\BaBiQ\docs\superpowers\plans\p1-0-skeleton\plan.md](F:\wwwxxxx\BaBiQ\docs\superpowers\plans\p1-0-skeleton\plan.md)** (~867 行 / ~50 个 step)

**第一件事:`read_file` 上面这个 plan.md 完整读一遍,理解任务全貌后再动手**。

---

## 1. 项目背景(60 秒读完)

- **项目目标**:实现一个对标 OpenAI Codex 桌面端的 AI Agent 学习项目 (BaBiQ),由两部分组成:
  - `backend/` — Java 21 + Spring Boot Agent Server
  - `desktop/` — Kotlin + Compose Multiplatform 桌面客户端
- **P1-0 的范围**:**只做 Monorepo 骨架重构**,不写任何业务代码。
  - 把当前单 module Spring Boot 项目拆成 `backend/` + `desktop/`
  - backend package 重命名为 `com.wzx.babiq.server`
  - 新建 desktop 模块(Compose Desktop Hello World 窗口)
  - 两者各自能独立构建运行
- **完整架构上下文**(可选阅读,深入理解再看):
  - `docs/ARCHITECTURE.md` — 系统架构(737 行)
  - `docs/superpowers/plans/2026-05-21-p1-master.md` — P1 总规划(含 17 条全局技术决策)

---

## 2. 硬约束(必须遵守,不准 "smart" 改)

| 约束 | 值 | 不能改的原因 |
|---|---|---|
| **JDK** | Java 21 LTS | Spring Boot 3.5.x 官方支持矩阵,Java 25 不在范围 |
| **Spring Boot** | 3.5.14 | 项目原版,2026-04-23 发布的 3.5 线最新 |
| **Kotlin** | 2.3.21 | GA stable,Gradle 兼容范围广 |
| **Compose Multiplatform** | 1.11.0 | 官方配对 Kotlin 2.3.21 |
| **Gradle** | 8.13(wrapper 锁定) | Kotlin 2.3 完全支持,**绝不能用全局 gradle 9.x 直接 run** |
| **backend package** | `com.wzx.babiq.server` | 用户明确决策 |
| **desktop package** | `com.wzx.babiq.desktop` | 用户明确决策 |
| **后端端口** | 8080 | P1-1 WebSocket 协议依赖 |
| **shell** | PowerShell(Windows) | 用户机器是 Win 11 |
| **commit 粒度** | 每个 Task 末尾 commit 一次 | frequent commits 原则 |
| **commit message 前缀** | `chore(p1-0):` / `refactor(p1-0):` / `feat(p1-0):` / `docs(p1-0):` | Conventional Commits |
| **不要引入** | spring-boot-starter-websocket、spring-ai-alibaba、JSON-RPC 库 | P1-0 只做骨架,这些是 P1-1+ 的工作 |

---

## 3. 任务清单总览(详细步骤在 plan.md 里)

按顺序执行,**不准跳跃**,**不准合并 Task**:

| # | Task | 目的 | 关键产出 |
|:-:|---|---|---|
| Pre-flight | 环境检查 | 确认 cwd / git / JDK 21 / Gradle 状态 | 不修改文件 |
| 1 | Git 初始化 + 修 `.gitignore` | **删除 .gitignore 首行 `README.md`**(防止 baseline 丢 README) | baseline commit |
| 2 | 把 `src/`、`pom.xml`、`mvnw`、`.mvn/` 用 `git mv` 移到 `backend/` | 保留 git 历史 | commit |
| 3 | backend package `com.wzx.babiq` → `com.wzx.babiq.server` | 移动 .java 文件 + 改 `package` 声明 + 文本校验。**不要在此步编译验证**,留给 Task 4 | commit(无编译) |
| 4 | `backend/pom.xml` 修复:`<java.version>` 25→21、`spring-boot-starter` → `spring-boot-starter-web`、groupId/artifactId 重命名 | **缺 web starter 会让 Spring Boot 不起 Tomcat,验收必失败** | 编译+测试+打包通过,commit |
| 5 | `application.properties` → `application.yml`,固定端口 8080 | 端口需为 P1-1 协议层使用 | 启动验证 `Started BaBiQApplication`,commit |
| 6 | 创建 `desktop/` Compose Multiplatform 骨架 | **必须先生成 gradlew wrapper 8.13 再跑任何 gradle 命令**(系统可能是 Gradle 9.x,与 Kotlin 2.3.21 兼容但不稳) | gradlew run 弹出窗口显示 `BaBiQ Desktop — P1-0 skeleton OK ✅`,commit |
| 7 | 更新 `.gitignore` 加 desktop build 产物 | 不要再追加 README.md | commit |
| 8 | 更新根 README.md 反映 Monorepo | 给后续读者 | commit |
| 9 | 同步 `master plan` + `ARCHITECTURE.md` 的版本号 + M0 验收 | 13 项验收清单全过 + git tag `p1-0-skeleton` | 最终 commit + tag |

---

## 4. 三个**已知陷阱**(plan.md 里有详细说明,但提前提醒你)

1. **`.gitignore` 第 1 行就是 `README.md`** —— 这是项目初始化时的错配置。Task 1 Step 1.2 必须删掉这一行,否则后续所有 `git add .` 都会跳过 README,Task 8 的 README 修改也跟踪不到。
2. **`pom.xml` 写的 `<java.version>25</java.version>`** —— 当前 JDK 是 21,直接编译会失败。Task 4 Step 4.1 必须改为 21。
3. **`pom.xml` 只有 `spring-boot-starter`** —— 这个 starter **不含内嵌 Tomcat**,Spring Boot 启动后会立即退出,端口 8080 不会绑定。Task 4 Step 4.2 必须改为 `spring-boot-starter-web`。

---

## 5. 完成标准(Done Criteria,13 项,全部机器可验证)

每一项都对应 plan.md 里的具体步骤。**全部达标才算 P1-0 完成**:

- [ ] 根目录只有: `README.md`, `.gitignore`, `.gitattributes`, `docs/`, `backend/`, `desktop/`(+ `.git/`, `.idea/`)
- [ ] `.gitignore` 首行**不是** `README.md`
- [ ] `cd backend && .\mvnw.cmd clean package` 成功,产出 `backend\target\babiq-server-0.0.1-SNAPSHOT.jar`
- [ ] `cd backend && .\mvnw.cmd spring-boot:run` 看到 `Started BaBiQApplication`,Tomcat 监听 8080
- [ ] `backend/pom.xml` 的 `<java.version>` 是 **21**
- [ ] `backend/pom.xml` 含 `spring-boot-starter-web` 依赖
- [ ] `cd desktop && .\gradlew.bat assemble` 成功
- [ ] `cd desktop && .\gradlew.bat run` 弹窗显示 `BaBiQ Desktop — P1-0 skeleton OK ✅`
- [ ] `desktop/gradle/wrapper/gradle-wrapper.properties` 指向 `gradle-8.13`
- [ ] backend 主类位于 `com.wzx.babiq.server` 包下
- [ ] desktop 主类位于 `com.wzx.babiq.desktop` 包下
- [ ] git 历史保留(`git log --follow backend/pom.xml` 能看到旧 `pom.xml` 的历史)
- [ ] `git tag p1-0-skeleton` 已打

---

## 6. 工作流约定(给 Codex 的执行规则)

1. **先读后做**:每个 Task 开始前,`read_file` 该 Task 在 plan.md 中的完整段落
2. **每步独立 commit**:Task 末尾必须 commit,**不能多个 Task 攒一起**
3. **遇到失败**:**先 read plan.md 看是不是已知陷阱**,不要瞎改
4. **不要超出范围**:plan 里没写的事不要做。例如不要顺手加 Spring AI 依赖,不要"优化"目录结构,不要改版本号
5. **下载大文件时给提示**:Task 6 首次跑 `gradlew run` 会下载 300-500MB(Gradle distribution + JDK 21 toolchain + Compose 依赖),需要 3-5 分钟,这是正常的
6. **审批策略**:Codex 默认 `on-request`,以下命令必须征求用户同意(它们 plan 里都有):
   - `git init`、`git mv`、`git commit`、`git tag`
   - `mvnw clean package`、`spring-boot:run`
   - `gradle wrapper`、`gradlew run`
   - 任何 `Remove-Item`、`mkdir`、`Move-Item`

---

## 7. 完成后请给用户一份汇报

完成所有 Task 后,输出:

```
## ✅ P1-0 完成报告

### 验收清单
[逐条勾选 §5 的 13 项,失败的标 ❌ 并说明]

### git 历史
[git log --oneline -15 输出]

### 关键产物路径
- backend jar: F:\wwwxxxx\BaBiQ\backend\target\babiq-server-0.0.1-SNAPSHOT.jar
- desktop build: F:\wwwxxxx\BaBiQ\desktop\build\
- git tag: p1-0-skeleton

### 遇到的偏差或问题
[列出执行过程中和 plan 不一致的地方;若无,写"无"]

### 下一步建议
P1-0 完成。可以让 Claude 继续写 P1-1(协议层)的详细 plan。
```

---

## 8. 应急情况

如果你完全卡住(连续 3 次失败,且 plan.md 没有对应说明):
- **不要破坏性回滚**(不要 `git reset --hard`、`Remove-Item -Recurse`)
- 停下来,把当前状态和错误信息汇报给用户
- 等用户(或他召唤的 Claude)给指示

---

**好了,开始吧。第一步:`read_file` plan.md,然后从 Pre-flight 走起。**
