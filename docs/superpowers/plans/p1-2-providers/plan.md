# P1-2: Provider 层 + Memory + ModelMetadata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **v1 (2026-05-22)**: 首版,与 master plan v3 与 ARCHITECTURE §8 / §14 对齐。

**Goal:** 实现多模型 Provider 层 + Spring AI Alibaba 短期 Memory 滑窗 + 模型元数据映射。落地:`application.yml` 配置 4 个 provider(DashScope / DeepSeek / OneAPI 中转 / Ollama);REST 测试端点 `GET /api/test/providers` 和 `POST /api/test/chat?providerId=xxx` 跑通真实模型;`MessageChatMemoryAdvisor(20)` 自动挂到每个 ChatClient 上,**多 Turn 跨调用记忆生效**;`ModelMetadata.contextWindowOf(model)` 内置主流模型映射。

**Architecture:** 单 `ChatClientFactory` 入口,内部按 `ProviderType` 分发到 `DashScopeProviderFactory` / `OpenAiCompatibleProviderFactory`(对应 D4 / D5)。**ChatClient 实例按 providerId 缓存**,`MessageWindowChatMemory(20)` 与 `MessageChatMemoryAdvisor` 同 providerId 共享(D18),`conversationId` 用 `threadId` 区分会话,确保多 Turn 跨调用复用同一份历史。

**Tech Stack:**
- Spring Boot 3.5.14(已有)
- Spring AI **1.1.5**(BOM)
- Spring AI Alibaba **1.1.2.x**(BOM)
- 关键 starter:`spring-ai-alibaba-starter-dashscope` / `spring-ai-starter-model-openai` / `spring-ai-advisors-vector-store`(后者提供 `MessageChatMemoryAdvisor`)
- JUnit 5 + AssertJ + Mockito 5(已有)

**Master Plan Reference:** [../2026-05-21-p1-master.md](../2026-05-21-p1-master.md)

**Architecture Reference:** [../../../ARCHITECTURE.md](../../../ARCHITECTURE.md) §8 + §14

**Milestone:** M2(详见 master plan §4)

**Prerequisite Milestone:** M0(P1-0 已完成)。P1-1 与 P1-2 可并行,但本 plan 仅触碰 `model/` 与 `test/` 目录,与 P1-1 无文件冲突。

---

## Files Touched

### Created (生产代码)
- `backend/src/main/java/com/wzx/babiq/server/model/ProviderType.java`
- `backend/src/main/java/com/wzx/babiq/server/model/ModelProviderConfig.java`
- `backend/src/main/java/com/wzx/babiq/server/model/BaBiQProperties.java`
- `backend/src/main/java/com/wzx/babiq/server/model/ModelProviderRegistry.java`
- `backend/src/main/java/com/wzx/babiq/server/model/ModelMetadata.java`
- `backend/src/main/java/com/wzx/babiq/server/model/ChatClientFactory.java`
- `backend/src/main/java/com/wzx/babiq/server/model/provider/ProviderFactory.java`
- `backend/src/main/java/com/wzx/babiq/server/model/provider/DashScopeProviderFactory.java`
- `backend/src/main/java/com/wzx/babiq/server/model/provider/OpenAiCompatibleProviderFactory.java`

### Created (测试端点 — P1-2 验证用,不属于最终架构,P1-3 起可移除)
- `backend/src/main/java/com/wzx/babiq/server/test/ProviderTestController.java`
- `backend/src/main/java/com/wzx/babiq/server/test/dto/ChatRequest.java`
- `backend/src/main/java/com/wzx/babiq/server/test/dto/ChatResponse.java`
- `backend/src/main/java/com/wzx/babiq/server/test/dto/ProviderInfo.java`

### Created (测试)
- `backend/src/test/java/com/wzx/babiq/server/model/ModelMetadataTest.java`
- `backend/src/test/java/com/wzx/babiq/server/model/ModelProviderRegistryTest.java`
- `backend/src/test/java/com/wzx/babiq/server/model/ChatClientFactoryTest.java`
- `backend/src/test/java/com/wzx/babiq/server/model/provider/DashScopeProviderFactoryTest.java`
- `backend/src/test/java/com/wzx/babiq/server/model/provider/OpenAiCompatibleProviderFactoryTest.java`
- `backend/src/test/java/com/wzx/babiq/server/test/ProviderTestControllerIntegrationTest.java`

### Modified
- `backend/pom.xml`(新增 Spring AI BOM + 三个 starter)
- `backend/src/main/resources/application.yml`(`babiq.active-provider` + `babiq.providers` + `babiq.memory.short-term.max-messages`)

### Optional(skipped if env vars absent)
- `backend/src/main/resources/application-dev.yml`(可选,纯本地 Ollama)

---

## Pre-flight Check

> 所有 PowerShell 命令默认在 `F:\wwwxxxx\BaBiQ\backend` 下执行,如需切目录会显式 `cd`。

- [ ] **Step 0.1: 确认 M0 完成(P1-0 baseline)**

Run:
```powershell
cd F:\wwwxxxx\BaBiQ\backend
.\mvnw.cmd -q compile
git tag --list p1-0-skeleton
```

Expected:
- `BUILD SUCCESS`
- 输出 `p1-0-skeleton`(P1-0 末尾打的 tag)

如果 tag 缺失,先回 P1-0 plan 补完 verification。

- [ ] **Step 0.2: 创建 P1-2 工作分支**

Run:
```powershell
git checkout -b feat/p1-2-providers
git status
```

Expected: 在 `feat/p1-2-providers` 上,无未提交修改。

- [ ] **Step 0.3: 准备 API key 环境变量(可选)**

> 📌 **真模型 vs SKIP 策略**:
> - **有 key**: 设置 `AI_DASHSCOPE_API_KEY` 和/或 `DEEPSEEK_API_KEY`,Step 9 烟测能跑真模型
> - **无 key**: Step 9 部分用例标记为 `@Disabled("requires API key")`,改用本地 Ollama 兜底;Done Criteria 中的"真模型回复"用 Ollama 完成
>
> 多 Turn 记忆验证不依赖具体 provider,Ollama 已足够。

Run(任选一种):
```powershell
# 选项 A: 有阿里云 DashScope key
$env:AI_DASHSCOPE_API_KEY = "sk-xxxxx"

# 选项 B: 有 DeepSeek key
$env:DEEPSEEK_API_KEY = "sk-xxxxx"

# 选项 C: 仅本地 Ollama(确保 ollama serve 起来,llama3:8b 已 pull)
ollama list
```

Expected: 至少一种可用。**纯无外网无 Ollama 模式**:用 `application-test.yml` + mock,Step 9.3 / 9.4 标记 SKIP。

---

## Task 1: pom.xml 加 Spring AI / SAA BOM + starter

**Files:**
- Modify: `backend/pom.xml`

- [ ] **Step 1.1: 加 BOM 到 `<dependencyManagement>`**

Edit `backend/pom.xml`,在 `<dependencies>` 之前(或现有 `<dependencyManagement>` 内)加入:

```xml
<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.1.5</spring-ai.version>
    <spring-ai-alibaba.version>1.1.2.1</spring-ai-alibaba.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-bom</artifactId>
            <version>${spring-ai-alibaba.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

> 📌 若 `<properties>` 已有 `java.version`,只追加两个新属性,不要重写整段。

- [ ] **Step 1.2: 加 starter 到 `<dependencies>`**

Edit `backend/pom.xml`,在 `<dependencies>` 段尾追加:

```xml
<!-- Spring AI Alibaba DashScope (qwen-plus / qwen-max) -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
</dependency>

<!-- Spring AI OpenAI Compatible (DeepSeek / OneAPI / Ollama / Azure) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>

<!-- D18: MessageChatMemoryAdvisor 由该 artifact 提供 -->
<!-- ⚠️ 若 MessageChatMemoryAdvisor 不在此 artifact,执行时报错后改 spring-ai-client-chat -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-advisors-vector-store</artifactId>
</dependency>

<!-- ⚠️ IMPORTANT: 上面两个 starter(spring-ai-alibaba-starter-dashscope / spring-ai-starter-model-openai)
     会通过 @ConditionalOn* 触发标准路径 auto-config,尝试用 spring.ai.dashscope.* / spring.ai.openai.*
     创建 ChatModel Bean。本 plan 使用自定义 babiq.providers[*] 路径 + ChatClientFactory,
     必须在 application.yml 显式 disable 标准路径(见 Step 7.1),否则 Bean 冲突导致启动失败。 -->

<!-- Spring Validation,@ConfigurationProperties + @Validated 用 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- @ConfigurationProperties IDE 元数据生成 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>
```

- [ ] **Step 1.3: 验证编译**

Run:
```powershell
.\mvnw.cmd -q dependency:resolve
.\mvnw.cmd -q clean compile
```

Expected: `BUILD SUCCESS`,无 "Could not find artifact" 错误。

> ⚠️ 若报找不到 SAA 1.1.2.x:检查 settings.xml 是否设了阿里云 mirror,或临时把 version 改回 1.1.2.0。`spring-ai-bom` 1.1.5 已 GA。

- [ ] **Step 1.4: Commit**

Run:
```powershell
git add backend\pom.xml
git commit -m "chore(p1-2): 引入 Spring AI 1.1.5 + Spring AI Alibaba 1.1.2.x BOM"
```

---

## Task 2: ProviderType / ModelProviderConfig / BaBiQProperties(配置载体)

**TDD 顺序**: 配置载体先写代码(纯 record / enum,无业务逻辑),Task 3 写 Registry 时再补测试。

**Files:**
- Create: `model/ProviderType.java`
- Create: `model/ModelProviderConfig.java`
- Create: `model/BaBiQProperties.java`

- [ ] **Step 2.1: 写 ProviderType enum**

Create `backend/src/main/java/com/wzx/babiq/server/model/ProviderType.java`:

```java
package com.wzx.babiq.server.model;

/**
 * Provider 类型枚举,决定 ChatClientFactory 路由到哪个具体 ProviderFactory。
 *
 * <p>D4:配置加载用 @ConfigurationProperties,enum 反序列化大小写不敏感(yml 写 dashscope / DASHSCOPE 均可)。
 */
public enum ProviderType {
    /** 阿里 DashScope 原生 starter,走 SAA 内置 DashScopeChatModel。 */
    DASHSCOPE,
    /** 任何 OpenAI 协议兼容的 endpoint(DeepSeek / OneAPI / Ollama / Azure)。 */
    OPENAI_COMPATIBLE
}
```

- [ ] **Step 2.2: 写 ModelProviderConfig record**

Create `backend/src/main/java/com/wzx/babiq/server/model/ModelProviderConfig.java`:

```java
package com.wzx.babiq.server.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单个 provider 的配置项,对应 application.yml 中 babiq.providers[*]。
 *
 * <p>D4:用 record + Bean Validation;空 api-key 在 Task 6 由 ProviderFactory 给明确错误。
 *
 * @param id              唯一标识,客户端通过此切换 provider
 * @param name            UI 展示名
 * @param type            provider 类型
 * @param model           模型名(qwen-plus / deepseek-chat / llama3:8b 等)
 * @param apiKey          api key,允许 ${ENV_VAR} 占位;Spring Boot 自动解析
 * @param baseUrl         OPENAI_COMPATIBLE 必填;DASHSCOPE 忽略
 * @param contextWindow   可选,显式 override ModelMetadata 默认值(D20)
 */
public record ModelProviderConfig(
    @NotBlank String id,
    String name,
    @NotNull ProviderType type,
    @NotBlank String model,
    String apiKey,
    String baseUrl,
    Integer contextWindow
) {
    /** Spring Boot 反序列化兜底:name 缺省回填 id。 */
    public String displayName() {
        return (name == null || name.isBlank()) ? id : name;
    }
}
```

- [ ] **Step 2.3: 写 BaBiQProperties(@ConfigurationProperties)**

Create `backend/src/main/java/com/wzx/babiq/server/model/BaBiQProperties.java`:

```java
package com.wzx.babiq.server.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * BaBiQ 根配置。yml 前缀 babiq.*。
 *
 * <p>D4:Provider 配置加载 @ConfigurationProperties("babiq") + List<ModelProviderConfig>。
 * <p>D18:short-term.max-messages 控制 MessageWindowChatMemory 滑窗大小,默认 20。
 */
@Validated
@ConfigurationProperties(prefix = "babiq")
public record BaBiQProperties(
    @NotBlank String activeProvider,
    @Valid List<ModelProviderConfig> providers,
    Memory memory
) {
    public BaBiQProperties {
        if (memory == null) {
            memory = new Memory(new ShortTerm(20));
        }
    }

    public record Memory(ShortTerm shortTerm) {
        public Memory {
            if (shortTerm == null) {
                shortTerm = new ShortTerm(20);
            }
        }
    }

    public record ShortTerm(@Min(1) int maxMessages) {}
}
```

- [ ] **Step 2.4: 让 @ConfigurationProperties 生效**

Edit `backend/src/main/java/com/wzx/babiq/server/BaBiQApplication.java`,在 `@SpringBootApplication` 下补 `@ConfigurationPropertiesScan`:

```java
package com.wzx.babiq.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.wzx.babiq.server")
public class BaBiQApplication {

	public static void main(String[] args) {
		SpringApplication.run(BaBiQApplication.class, args);
	}

}
```

- [ ] **Step 2.5: 验证编译**

Run:
```powershell
.\mvnw.cmd -q clean compile
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 2.6: Commit**

```powershell
git add -A
git commit -m "feat(p1-2): 新增 ProviderType / ModelProviderConfig / BaBiQProperties 配置载体"
```

---

## Task 3: ModelMetadata(D20)— 内置主流模型上下文窗口映射

**TDD 顺序**: 先写测试,再写实现(D20 是纯函数,最适合 TDD 起手)。

**Files:**
- Create: `model/ModelMetadata.java`
- Create: `test/.../model/ModelMetadataTest.java`

- [ ] **Step 3.1: 写 ModelMetadataTest(失败先行)**

Create `backend/src/test/java/com/wzx/babiq/server/model/ModelMetadataTest.java`:

```java
package com.wzx.babiq.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMetadataTest {

    @Test
    @DisplayName("qwen-plus 返回 1_000_000")
    void qwen_plus_returns_one_million() {
        assertThat(ModelMetadata.contextWindowOf("qwen-plus")).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("deepseek-chat 返回 128_000")
    void deepseek_chat_returns_128k() {
        assertThat(ModelMetadata.contextWindowOf("deepseek-chat")).isEqualTo(128_000);
    }

    @Test
    @DisplayName("llama3:8b 返回 8_192")
    void llama3_returns_8k() {
        assertThat(ModelMetadata.contextWindowOf("llama3:8b")).isEqualTo(8_192);
    }

    @Test
    @DisplayName("大小写不敏感")
    void case_insensitive() {
        assertThat(ModelMetadata.contextWindowOf("QWEN-PLUS")).isEqualTo(1_000_000);
        assertThat(ModelMetadata.contextWindowOf("Qwen-Plus")).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("未知模型回退到默认 32_768")
    void unknown_model_returns_default() {
        assertThat(ModelMetadata.contextWindowOf("my-proprietary-llm-xyz"))
            .isEqualTo(ModelMetadata.DEFAULT_CONTEXT_WINDOW)
            .isEqualTo(32_768);
    }

    @Test
    @DisplayName("null / 空字符串安全:抛 IllegalArgumentException")
    void null_or_blank_throws() {
        assertThatThrownBy(() -> ModelMetadata.contextWindowOf(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelMetadata.contextWindowOf(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelMetadata.contextWindowOf("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("内置主流模型覆盖率:至少 8 条")
    void builtin_coverage_minimum() {
        // 防止后续 PR 不小心删了某条
        String[] required = {"qwen-plus", "qwen-turbo", "qwen-max", "qwq-plus",
                             "deepseek-chat", "gpt-4o", "claude-opus-4-7", "llama3:8b"};
        for (String m : required) {
            assertThat(ModelMetadata.contextWindowOf(m))
                .as("model %s should be builtin", m)
                .isNotEqualTo(ModelMetadata.DEFAULT_CONTEXT_WINDOW);
        }
    }
}
```

Run:
```powershell
.\mvnw.cmd -q test -Dtest=ModelMetadataTest
```

Expected: **编译失败**(ModelMetadata 还没写),这是 TDD red phase 的正常状态。

- [ ] **Step 3.2: 写 ModelMetadata 实现**

Create `backend/src/main/java/com/wzx/babiq/server/model/ModelMetadata.java`:

```java
package com.wzx.babiq.server.model;

import java.util.Map;

import static java.util.Map.entry;

/**
 * 模型元数据(D20):内置主流模型 context window 映射,application.yml 可 override。
 *
 * <p>数据来源:LiteLLM model_prices.json + ARCHITECTURE.md §14.4.1(2026-05 快照)。
 * <p>未知模型回退到 {@link #DEFAULT_CONTEXT_WINDOW}(32K,Llama2 时代保守默认)。
 *
 * <p>使用:
 * <pre>{@code
 *   int window = ModelMetadata.contextWindowOf("qwen-plus"); // 1_000_000
 * }</pre>
 *
 * <p>P2+ 可加 pricePerToken / supportsVision 等;P1-2 仅 contextWindow。
 */
public final class ModelMetadata {

    /** 未知模型默认上下文窗口,32K。 */
    public static final int DEFAULT_CONTEXT_WINDOW = 32_768;

    private static final Map<String, Integer> CONTEXT_WINDOWS = Map.ofEntries(
        // ===== 阿里通义 =====
        entry("qwen-plus",            1_000_000),
        entry("qwen-turbo",             128_000),
        entry("qwen-max",               262_144),
        entry("qwen3-max",              262_144),
        entry("qwq-plus",               131_072),
        // ===== DeepSeek =====
        entry("deepseek-chat",          128_000),
        entry("deepseek-reasoner",      128_000),
        entry("deepseek-v4",          1_000_000),
        // ===== OpenAI =====
        entry("gpt-4o",                 128_000),
        entry("gpt-4o-mini",            128_000),
        entry("gpt-4.1",              1_000_000),
        entry("gpt-5",                1_000_000),
        entry("o1",                     200_000),
        entry("o1-mini",                128_000),
        // ===== Anthropic =====
        entry("claude-opus-4-7",        200_000),
        entry("claude-sonnet-4-6",      200_000),
        // ===== Ollama / 本地 =====
        entry("llama3:8b",                8_192),
        entry("qwen2.5-coder:7b",        32_768),
        entry("qwen2.5-coder:32b",       32_768)
    );

    private ModelMetadata() {}

    /**
     * 查询模型上下文窗口。大小写不敏感。
     *
     * @param model 模型名,不能为 null / 空白
     * @return 已知模型返回 builtin 值;未知模型返回 {@link #DEFAULT_CONTEXT_WINDOW}
     * @throws IllegalArgumentException 当 model 为 null 或全空白
     */
    public static int contextWindowOf(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be null or blank");
        }
        return CONTEXT_WINDOWS.getOrDefault(model.toLowerCase(), DEFAULT_CONTEXT_WINDOW);
    }
}
```

- [ ] **Step 3.3: 验证测试通过**

Run:
```powershell
.\mvnw.cmd -q test -Dtest=ModelMetadataTest
```

Expected: **7 tests passed**(green phase)。

- [ ] **Step 3.4: Commit**

```powershell
git add -A
git commit -m "feat(p1-2): D20 实现 ModelMetadata 内置主流模型 context window 映射"
```

---

## Task 4: ProviderFactory 接口 + 两个实现(DashScope / OpenAI Compatible)

**TDD 顺序**: 先写接口,再分别为两个实现写单测 → 写实现 → 跑绿。**关键单测:缺 api-key 给明确错误而不是 NPE**。

**Files:**
- Create: `model/provider/ProviderFactory.java`
- Create: `model/provider/DashScopeProviderFactory.java`
- Create: `model/provider/OpenAiCompatibleProviderFactory.java`
- Create: `test/.../model/provider/DashScopeProviderFactoryTest.java`
- Create: `test/.../model/provider/OpenAiCompatibleProviderFactoryTest.java`

- [ ] **Step 4.1: 写 ProviderFactory 接口**

Create `backend/src/main/java/com/wzx/babiq/server/model/provider/ProviderFactory.java`:

```java
package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 单一 Provider 类型的构建工厂。
 *
 * <p>D5:OPENAI_COMPATIBLE 用 OpenAiApi.mutate().baseUrl().build();
 * DASHSCOPE 用 SAA 原生 builder。
 *
 * <p>{@link ChatClientFactory} 按 {@link #supports()} 路由到正确实现。
 */
public interface ProviderFactory {

    /** 该工厂处理的 ProviderType。 */
    ProviderType supports();

    /**
     * 按配置构建 ChatModel(未挂 advisor,advisor 由 ChatClientFactory 统一加)。
     *
     * @throws IllegalStateException 当配置不合法(如 OPENAI_COMPATIBLE 缺 baseUrl,或两者都缺 apiKey)
     */
    ChatModel build(ModelProviderConfig config);
}
```

- [ ] **Step 4.2: 写 DashScopeProviderFactoryTest(失败先行)**

Create `backend/src/test/java/com/wzx/babiq/server/model/provider/DashScopeProviderFactoryTest.java`:

```java
package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashScopeProviderFactoryTest {

    private final DashScopeProviderFactory factory = new DashScopeProviderFactory();

    @Test
    @DisplayName("supports() 返回 DASHSCOPE")
    void supports_dashscope() {
        assertThat(factory.supports()).isEqualTo(ProviderType.DASHSCOPE);
    }

    @Test
    @DisplayName("正常配置构建出 ChatModel(假 api-key 不联网)")
    void builds_chatmodel_with_fake_key() {
        var cfg = new ModelProviderConfig(
            "dashscope-default", "通义千问", ProviderType.DASHSCOPE,
            "qwen-plus", "sk-fake-key", null, null);

        assertThat(factory.build(cfg)).isNotNull();
    }

    @Test
    @DisplayName("缺 api-key 给明确错误而不是 NPE")
    void missing_api_key_gives_clear_error() {
        var cfg = new ModelProviderConfig(
            "dashscope-default", null, ProviderType.DASHSCOPE,
            "qwen-plus", null, null, null);

        assertThatThrownBy(() -> factory.build(cfg))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api-key")
            .hasMessageContaining("dashscope-default");
    }

    @Test
    @DisplayName("空白 api-key 同样给明确错误")
    void blank_api_key_gives_clear_error() {
        var cfg = new ModelProviderConfig(
            "dashscope-default", null, ProviderType.DASHSCOPE,
            "qwen-plus", "   ", null, null);

        assertThatThrownBy(() -> factory.build(cfg))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api-key");
    }
}
```

- [ ] **Step 4.3: 写 DashScopeProviderFactory 实现**

Create `backend/src/main/java/com/wzx/babiq/server/model/provider/DashScopeProviderFactory.java`:

```java
package com.wzx.babiq.server.model.provider;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 阿里 DashScope provider 工厂。
 *
 * <p>D5:走 Spring AI Alibaba 原生 builder,DashScopeApi + DashScopeChatModel + DashScopeChatOptions。
 */
@Component
public class DashScopeProviderFactory implements ProviderFactory {

    @Override
    public ProviderType supports() {
        return ProviderType.DASHSCOPE;
    }

    @Override
    public ChatModel build(ModelProviderConfig config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException(
                "Provider [" + config.id() + "] (type=DASHSCOPE) 缺少 api-key。"
                + " 请在 application.yml 或环境变量 ${AI_DASHSCOPE_API_KEY} 配置。");
        }

        DashScopeApi api = DashScopeApi.builder()
            .apiKey(config.apiKey())
            .build();

        DashScopeChatOptions options = DashScopeChatOptions.builder()
            .withModel(config.model())
            .build();

        return DashScopeChatModel.builder()
            .dashScopeApi(api)
            .defaultOptions(options)
            .build();
    }
}
```

> ⚠️ **API 漂移防御**: SAA 1.1.2.x 的 builder 方法名可能是 `.api()` 或 `.dashScopeApi()`,Options 可能是 `.withModel()` 或 `.model()`。**编译失败时第一步打开 SAA 源码或 java2ai.com 文档查 1.1.2.x 当时的签名**,不要硬猜。

- [ ] **Step 4.4: 写 OpenAiCompatibleProviderFactoryTest**

Create `backend/src/test/java/com/wzx/babiq/server/model/provider/OpenAiCompatibleProviderFactoryTest.java`:

```java
package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleProviderFactoryTest {

    private final OpenAiCompatibleProviderFactory factory = new OpenAiCompatibleProviderFactory();

    @Test
    @DisplayName("supports() 返回 OPENAI_COMPATIBLE")
    void supports_openai_compatible() {
        assertThat(factory.supports()).isEqualTo(ProviderType.OPENAI_COMPATIBLE);
    }

    @Test
    @DisplayName("正常配置(DeepSeek 风格)构建出 ChatModel")
    void builds_chatmodel_for_deepseek() {
        var cfg = new ModelProviderConfig(
            "deepseek-official", "DeepSeek", ProviderType.OPENAI_COMPATIBLE,
            "deepseek-chat", "sk-fake", "https://api.deepseek.com", null);

        assertThat(factory.build(cfg)).isNotNull();
    }

    @Test
    @DisplayName("Ollama 风格(占位 key + localhost)也能构建")
    void builds_chatmodel_for_ollama() {
        var cfg = new ModelProviderConfig(
            "ollama-local", null, ProviderType.OPENAI_COMPATIBLE,
            "llama3:8b", "ollama", "http://localhost:11434/v1", null);

        assertThat(factory.build(cfg)).isNotNull();
    }

    @Test
    @DisplayName("缺 baseUrl 给明确错误")
    void missing_base_url_gives_clear_error() {
        var cfg = new ModelProviderConfig(
            "oneapi-relay", null, ProviderType.OPENAI_COMPATIBLE,
            "gpt-4o", "sk-fake", null, null);

        assertThatThrownBy(() -> factory.build(cfg))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("base-url")
            .hasMessageContaining("oneapi-relay");
    }

    @Test
    @DisplayName("缺 api-key 给明确错误而不是 NPE")
    void missing_api_key_gives_clear_error() {
        var cfg = new ModelProviderConfig(
            "deepseek-official", null, ProviderType.OPENAI_COMPATIBLE,
            "deepseek-chat", null, "https://api.deepseek.com", null);

        assertThatThrownBy(() -> factory.build(cfg))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api-key");
    }
}
```

- [ ] **Step 4.5: 写 OpenAiCompatibleProviderFactory 实现**

Create `backend/src/main/java/com/wzx/babiq/server/model/provider/OpenAiCompatibleProviderFactory.java`:

```java
package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * OpenAI 协议兼容 provider 工厂。覆盖 DeepSeek / OneAPI / Ollama / Azure / OpenAI 官方。
 *
 * <p>D5:OpenAiApi.builder().baseUrl().apiKey().build() + OpenAiChatOptions.model()。
 */
@Component
public class OpenAiCompatibleProviderFactory implements ProviderFactory {

    @Override
    public ProviderType supports() {
        return ProviderType.OPENAI_COMPATIBLE;
    }

    @Override
    public ChatModel build(ModelProviderConfig config) {
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new IllegalStateException(
                "Provider [" + config.id() + "] (type=OPENAI_COMPATIBLE) 缺少 base-url。"
                + " 例:https://api.deepseek.com / http://localhost:11434/v1");
        }
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException(
                "Provider [" + config.id() + "] (type=OPENAI_COMPATIBLE) 缺少 api-key。"
                + " Ollama 等无校验服务请用任意占位字符串(如 \"ollama\")。");
        }

        OpenAiApi api = OpenAiApi.builder()
            .baseUrl(config.baseUrl())
            .apiKey(config.apiKey())
            .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(config.model())
            .build();

        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .build();
    }
}
```

- [ ] **Step 4.6: 跑两个 Factory 单测**

Run:
```powershell
.\mvnw.cmd -q test -Dtest="*ProviderFactoryTest"
```

Expected: **9 tests passed** (4 DashScope + 5 OpenAiCompatible)。

> ⚠️ 若 `OpenAiApi.builder()` 报错(老版本是 `OpenAiApi.mutate()` 或 `new OpenAiApi(...)`):
> - Spring AI 1.0 GA 后已统一为 `.builder()`,1.1.5 沿用
> - 若你的本地 BOM 解析出别的版本,看 IDE 自动补全的 method 名,优先用与 chatModel.builder() 风格一致的方法

- [ ] **Step 4.7: Commit**

```powershell
git add -A
git commit -m "feat(p1-2): D5 实现 DashScope + OpenAI 兼容 ProviderFactory 双工厂"
```

---

## Task 5: ModelProviderRegistry(Provider 配置注册中心)

**TDD 顺序**: 写测试 → 写实现。**关键测试:启动期校验 active-provider 必须存在,重复 id 拒绝。**

**Files:**
- Create: `model/ModelProviderRegistry.java`
- Create: `test/.../model/ModelProviderRegistryTest.java`

- [ ] **Step 5.1: 写 ModelProviderRegistryTest**

Create `backend/src/test/java/com/wzx/babiq/server/model/ModelProviderRegistryTest.java`:

```java
package com.wzx.babiq.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderRegistryTest {

    private static ModelProviderConfig dashscope() {
        return new ModelProviderConfig("dashscope-default", "通义", ProviderType.DASHSCOPE,
            "qwen-plus", "sk-1", null, null);
    }

    private static ModelProviderConfig deepseek() {
        return new ModelProviderConfig("deepseek-official", null, ProviderType.OPENAI_COMPATIBLE,
            "deepseek-chat", "sk-2", "https://api.deepseek.com", null);
    }

    private static BaBiQProperties props(String active, List<ModelProviderConfig> list) {
        return new BaBiQProperties(active, list, new BaBiQProperties.Memory(new BaBiQProperties.ShortTerm(20)));
    }

    @Test
    @DisplayName("get(id) 返回对应配置")
    void get_returns_config() {
        var registry = new ModelProviderRegistry(props("dashscope-default", List.of(dashscope(), deepseek())));
        assertThat(registry.get("dashscope-default").model()).isEqualTo("qwen-plus");
        assertThat(registry.get("deepseek-official").type()).isEqualTo(ProviderType.OPENAI_COMPATIBLE);
    }

    @Test
    @DisplayName("get(未知 id) 抛 IllegalArgumentException 含可用 id 列表")
    void get_unknown_throws_with_available_list() {
        var registry = new ModelProviderRegistry(props("dashscope-default", List.of(dashscope())));
        assertThatThrownBy(() -> registry.get("ghost"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ghost")
            .hasMessageContaining("dashscope-default");
    }

    @Test
    @DisplayName("active() 返回当前激活配置")
    void active_returns_active_config() {
        var registry = new ModelProviderRegistry(props("deepseek-official", List.of(dashscope(), deepseek())));
        assertThat(registry.active().id()).isEqualTo("deepseek-official");
    }

    @Test
    @DisplayName("setActive 切换 active id")
    void set_active_switches() {
        var registry = new ModelProviderRegistry(props("dashscope-default", List.of(dashscope(), deepseek())));
        registry.setActive("deepseek-official");
        assertThat(registry.active().id()).isEqualTo("deepseek-official");
    }

    @Test
    @DisplayName("setActive 切到未知 id 拒绝")
    void set_active_unknown_rejects() {
        var registry = new ModelProviderRegistry(props("dashscope-default", List.of(dashscope())));
        assertThatThrownBy(() -> registry.setActive("ghost"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("list() 返回所有 id 列表(顺序保持)")
    void list_returns_all() {
        var registry = new ModelProviderRegistry(props("dashscope-default", List.of(dashscope(), deepseek())));
        assertThat(registry.list()).extracting(ModelProviderConfig::id)
            .containsExactly("dashscope-default", "deepseek-official");
    }

    @Test
    @DisplayName("重复 id 启动期拒绝")
    void duplicate_id_rejected_at_init() {
        var dup = new ModelProviderConfig("dashscope-default", null, ProviderType.DASHSCOPE,
            "qwen-turbo", "sk-3", null, null);
        assertThatThrownBy(() -> new ModelProviderRegistry(props("dashscope-default", List.of(dashscope(), dup))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("duplicate")
            .hasMessageContaining("dashscope-default");
    }

    @Test
    @DisplayName("active-provider 不在 providers 列表中,启动期拒绝")
    void active_not_in_providers_rejected() {
        assertThatThrownBy(() -> new ModelProviderRegistry(props("ghost", List.of(dashscope()))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active-provider")
            .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("空 providers 列表启动期拒绝")
    void empty_providers_rejected() {
        assertThatThrownBy(() -> new ModelProviderRegistry(props("any", List.of())))
            .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 5.2: 写 ModelProviderRegistry 实现**

Create `backend/src/main/java/com/wzx/babiq/server/model/ModelProviderRegistry.java`:

```java
package com.wzx.babiq.server.model;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provider 配置注册中心(D4)。
 *
 * <p>启动期一次性把 {@link BaBiQProperties#providers()} 灌进只读 map,并校验:
 * <ul>
 *   <li>providers 不能为空</li>
 *   <li>id 不能重复</li>
 *   <li>active-provider 必须在 providers 列表中</li>
 * </ul>
 *
 * <p>active 字段可运行时切换(对应 `model/providers/set-active` 协议方法,P1-3 接入)。
 */
@Component
public class ModelProviderRegistry {

    private final Map<String, ModelProviderConfig> byId;
    private final AtomicReference<String> activeId;

    public ModelProviderRegistry(BaBiQProperties props) {
        List<ModelProviderConfig> providers = props.providers();
        if (providers == null || providers.isEmpty()) {
            throw new IllegalStateException(
                "babiq.providers 不能为空,至少配置一个 provider");
        }

        Map<String, ModelProviderConfig> map = new LinkedHashMap<>();
        for (ModelProviderConfig p : providers) {
            if (map.put(p.id(), p) != null) {
                throw new IllegalStateException(
                    "duplicate provider id [" + p.id() + "] in babiq.providers");
            }
        }

        if (!map.containsKey(props.activeProvider())) {
            throw new IllegalStateException(
                "babiq.active-provider [" + props.activeProvider() + "] 不在 providers 列表中,"
                + "可用 id: " + map.keySet());
        }

        this.byId = Map.copyOf(map);
        this.activeId = new AtomicReference<>(props.activeProvider());
    }

    public ModelProviderConfig get(String id) {
        ModelProviderConfig cfg = byId.get(id);
        if (cfg == null) {
            throw new IllegalArgumentException(
                "未知 provider id [" + id + "],可用: " + byId.keySet());
        }
        return cfg;
    }

    public ModelProviderConfig active() {
        return get(activeId.get());
    }

    /** P1-3 由 `model/providers/set-active` handler 调用。 */
    public void setActive(String id) {
        get(id); // 校验存在
        activeId.set(id);
    }

    public List<ModelProviderConfig> list() {
        return List.copyOf(byId.values());
    }
}
```

- [ ] **Step 5.3: 跑测试**

Run:
```powershell
.\mvnw.cmd -q test -Dtest=ModelProviderRegistryTest
```

Expected: **9 tests passed**。

- [ ] **Step 5.4: Commit**

```powershell
git add -A
git commit -m "feat(p1-2): D4 实现 ModelProviderRegistry 启动期校验"
```

---

## Task 6: ChatClientFactory(D18 自动挂 MessageChatMemoryAdvisor)

**核心**: 这是 P1-2 的**关键交付物**。`resolve(providerId)` 返回的 ChatClient 自动挂 `MessageChatMemoryAdvisor(maxMessages=20)`,**同一 providerId 复用同一份 MessageWindowChatMemory**,这样跨 Turn 调用(同 threadId)能记得历史。

**TDD 顺序**: 先写单测覆盖 cache + advisor 挂载 → 写实现 → 跑绿。**关键测试:多次 resolve(同 id) 返回同一 ChatClient 实例**。

**Files:**
- Create: `model/ChatClientFactory.java`
- Create: `test/.../model/ChatClientFactoryTest.java`

- [ ] **Step 6.1: 写 ChatClientFactoryTest**

Create `backend/src/test/java/com/wzx/babiq/server/model/ChatClientFactoryTest.java`:

```java
package com.wzx.babiq.server.model;

import com.wzx.babiq.server.model.provider.DashScopeProviderFactory;
import com.wzx.babiq.server.model.provider.OpenAiCompatibleProviderFactory;
import com.wzx.babiq.server.model.provider.ProviderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatClientFactoryTest {

    private static BaBiQProperties props() {
        var dashscope = new ModelProviderConfig("dashscope-default", "通义", ProviderType.DASHSCOPE,
            "qwen-plus", "sk-fake", null, null);
        var deepseek = new ModelProviderConfig("deepseek-official", null, ProviderType.OPENAI_COMPATIBLE,
            "deepseek-chat", "sk-fake", "https://api.deepseek.com", null);
        return new BaBiQProperties("dashscope-default", List.of(dashscope, deepseek),
            new BaBiQProperties.Memory(new BaBiQProperties.ShortTerm(20)));
    }

    private ChatClientFactory newFactory() {
        var p = props();
        var registry = new ModelProviderRegistry(p);
        List<ProviderFactory> factories = List.of(
            new DashScopeProviderFactory(),
            new OpenAiCompatibleProviderFactory());
        return new ChatClientFactory(registry, factories, p);
    }

    @Test
    @DisplayName("resolve(DASHSCOPE id) 返回非空 ChatClient")
    void resolves_dashscope() {
        ChatClient c = newFactory().resolve("dashscope-default");
        assertThat(c).isNotNull();
    }

    @Test
    @DisplayName("resolve(OPENAI_COMPATIBLE id) 返回非空 ChatClient")
    void resolves_openai_compatible() {
        ChatClient c = newFactory().resolve("deepseek-official");
        assertThat(c).isNotNull();
    }

    @Test
    @DisplayName("resolve 同一 id 多次,返回同一 ChatClient 实例(关键:advisor + memory 共享)")
    void resolve_caches_per_id() {
        var f = newFactory();
        ChatClient a = f.resolve("dashscope-default");
        ChatClient b = f.resolve("dashscope-default");
        assertThat(a).isSameAs(b);
    }

    @Test
    @DisplayName("resolve 不同 id,返回不同 ChatClient")
    void resolve_different_ids_distinct() {
        var f = newFactory();
        ChatClient a = f.resolve("dashscope-default");
        ChatClient b = f.resolve("deepseek-official");
        assertThat(a).isNotSameAs(b);
    }

    @Test
    @DisplayName("resolve(未知 id) 抛 IllegalArgumentException")
    void resolve_unknown_throws() {
        assertThatThrownBy(() -> newFactory().resolve("ghost"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("active() 返回当前激活 ChatClient")
    void active_returns_active_client() {
        ChatClient c = newFactory().active();
        assertThat(c).isNotNull();
    }
}
```

- [ ] **Step 6.2: 写 ChatClientFactory 实现**

Create `backend/src/main/java/com/wzx/babiq/server/model/ChatClientFactory.java`:

```java
package com.wzx.babiq.server.model;

import com.wzx.babiq.server.model.provider.ProviderFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatClient 工厂(D18 关键交付)。
 *
 * <p>职责:
 * <ol>
 *   <li>按 providerId 路由到正确的 {@link ProviderFactory} 构建 {@link ChatModel}</li>
 *   <li>包装成 {@link ChatClient},自动挂 {@link MessageChatMemoryAdvisor}(maxMessages=20)</li>
 *   <li>按 providerId 缓存 ChatClient + 关联的 {@link ChatMemory},保证多次 resolve 复用同一份历史</li>
 * </ol>
 *
 * <p>记忆隔离:同 providerId 共享 ChatMemory;实际跨 Thread 隔离由调用方在
 * {@code chatClient.prompt().advisors(a -> a.param(ChatMemory.CONVERSATION_ID, threadId))} 时传入 threadId。
 *
 * <p>缺 api-key 错误由内层 {@link ProviderFactory#build(ModelProviderConfig)} 抛 IllegalStateException,不会到 NPE。
 */
@Component
public class ChatClientFactory {

    private final ModelProviderRegistry registry;
    private final Map<ProviderType, ProviderFactory> factoriesByType;
    private final int maxMessages;

    /** providerId -> 已构建并缓存的 ChatClient。 */
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    public ChatClientFactory(ModelProviderRegistry registry,
                             List<ProviderFactory> factories,
                             BaBiQProperties props) {
        this.registry = registry;
        this.factoriesByType = new EnumMap<>(ProviderType.class);
        for (ProviderFactory f : factories) {
            ProviderFactory prev = factoriesByType.put(f.supports(), f);
            if (prev != null) {
                throw new IllegalStateException(
                    "duplicate ProviderFactory for type " + f.supports());
            }
        }
        this.maxMessages = props.memory().shortTerm().maxMessages();
    }

    /** 按 providerId 返回已挂 MessageChatMemoryAdvisor 的 ChatClient(缓存)。 */
    public ChatClient resolve(String providerId) {
        return clientCache.computeIfAbsent(providerId, this::buildClient);
    }

    /** 当前激活 provider 的 ChatClient。 */
    public ChatClient active() {
        return resolve(registry.active().id());
    }

    private ChatClient buildClient(String providerId) {
        ModelProviderConfig cfg = registry.get(providerId);
        ProviderFactory pf = factoriesByType.get(cfg.type());
        if (pf == null) {
            throw new IllegalStateException(
                "no ProviderFactory registered for type " + cfg.type());
        }

        ChatModel model = pf.build(cfg);

        // D18: MessageWindowChatMemory(N) + MessageChatMemoryAdvisor
        ChatMemory memory = MessageWindowChatMemory.builder()
            .maxMessages(maxMessages)
            .build();

        return ChatClient.builder(model)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
            .build();
    }
}
```

> ⚠️ **API 名注意**:
> - Spring AI 1.1.x `MessageWindowChatMemory.builder().maxMessages(N).build()` 已是 GA API
> - `MessageChatMemoryAdvisor.builder(memory).build()` 同上(由 `spring-ai-advisors-vector-store` artifact 提供)
> - 若 1.1.5 的 API 名是 `.chatMemory(...)` 或 `.chatMemoryRepository(...)`,以 IDE 自动补全为准

- [ ] **Step 6.3: 跑 ChatClientFactory 单测**

Run:
```powershell
.\mvnw.cmd -q test -Dtest=ChatClientFactoryTest
```

Expected: **6 tests passed**。

- [ ] **Step 6.4: 跑全部单测确保无回归**

Run:
```powershell
.\mvnw.cmd -q test
```

Expected: 所有测试通过(Task 3-6 共 ~31 tests + P1-0 的 contextLoads)。

- [ ] **Step 6.5: Commit**

```powershell
git add -A
git commit -m "feat(p1-2): D18 实现 ChatClientFactory 自动挂 MessageChatMemoryAdvisor(20)"
```

---

## Task 7: application.yml 配置 4 个 provider

**Files:**
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 7.1: 写 application.yml 节选**

Edit `backend/src/main/resources/application.yml`,追加(保留 P1-0 已有的 spring/server/logging):

```yaml
spring:
  application:
    name: BaBiQ-Server
  # 关闭 Spring AI 标准路径 auto-config(我们用自定义 babiq.providers[*] 路径)
  # 避免与 ChatClientFactory 创建的 Bean 冲突
  ai:
    dashscope:
      chat:
        enabled: false
    openai:
      chat:
        enabled: false

server:
  port: 8080

logging:
  level:
    com.wzx.babiq.server: DEBUG

# ==============================================
# BaBiQ Provider 层配置(P1-2)
# ==============================================
babiq:
  active-provider: dashscope-default

  providers:
    # ===== 1. 阿里 DashScope(原生 SAA starter)=====
    - id: dashscope-default
      name: "通义千问 (DashScope)"
      type: DASHSCOPE
      api-key: ${AI_DASHSCOPE_API_KEY:}
      model: qwen-plus

    # ===== 2. DeepSeek 官方(走 OpenAI 兼容)=====
    - id: deepseek-official
      name: "DeepSeek (官方)"
      type: OPENAI_COMPATIBLE
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY:}
      model: deepseek-chat

    # ===== 3. OneAPI 中转(对标第三方代理)=====
    - id: oneapi-relay
      name: "我的中转 (OneAPI)"
      type: OPENAI_COMPATIBLE
      base-url: ${ONEAPI_BASE_URL:https://my-relay.example.com/v1}
      api-key: ${ONEAPI_KEY:placeholder}
      model: ${ONEAPI_MODEL:gpt-4o}

    # ===== 4. 本地 Ollama =====
    - id: ollama-local
      name: "本地 Llama3"
      type: OPENAI_COMPATIBLE
      base-url: http://localhost:11434/v1
      api-key: ollama
      model: llama3:8b

  memory:
    short-term:
      max-messages: 20
```

> 📌 **占位策略**:`${AI_DASHSCOPE_API_KEY:}` 的冒号后留空,启动不会因占位缺失而失败,但实际调用真模型时 ProviderFactory 会抛"缺 api-key"。
> `oneapi-relay` 用 `placeholder` 字符串占位,让启动 Bean 化通过,真调时再校验。

- [ ] **Step 7.2: 启动烟测**

Run:
```powershell
.\mvnw.cmd spring-boot:run
```

Expected:
- 90 秒内看到 `Started BaBiQApplication`
- 无 "duplicate provider id" 或 "active-provider not in" 错误
- 4 个 provider 全部完成 bean 注册(可在 DEBUG 日志看到 ProviderFactory 注入)

按 Ctrl+C 退出。

- [ ] **Step 7.3: Commit**

```powershell
git add backend\src\main\resources\application.yml
git commit -m "feat(p1-2): application.yml 配置 4 个 provider(DashScope/DeepSeek/OneAPI/Ollama)"
```

---

## Task 8: REST 测试端点(GET /api/test/providers + POST /api/test/chat)

**Files:**
- Create: `test/ProviderTestController.java`
- Create: `test/dto/ChatRequest.java`
- Create: `test/dto/ChatResponse.java`
- Create: `test/dto/ProviderInfo.java`

> 📌 注意 package 名:`com.wzx.babiq.server.test`(用 `test` 子包标明这是临时验证用,P1-3 加 WebSocket 后可删)。**不要**放在 `src/test/java` 下(那是单测目录)。

- [ ] **Step 8.1: 写 DTO**

Create `backend/src/main/java/com/wzx/babiq/server/test/dto/ChatRequest.java`:

```java
package com.wzx.babiq.server.test.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
    @NotBlank String text,
    String threadId       // 可选,缺省 = "default"
) {}
```

Create `backend/src/main/java/com/wzx/babiq/server/test/dto/ChatResponse.java`:

```java
package com.wzx.babiq.server.test.dto;

public record ChatResponse(
    String providerId,
    String model,
    String threadId,
    String reply
) {}
```

Create `backend/src/main/java/com/wzx/babiq/server/test/dto/ProviderInfo.java`:

```java
package com.wzx.babiq.server.test.dto;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;

public record ProviderInfo(
    String id,
    String name,
    ProviderType type,
    String model,
    boolean active,
    int contextWindow
) {
    public static ProviderInfo from(ModelProviderConfig cfg, boolean active, int contextWindow) {
        return new ProviderInfo(cfg.id(), cfg.displayName(), cfg.type(),
            cfg.model(), active, contextWindow);
    }
}
```

- [ ] **Step 8.2: 写 ProviderTestController**

Create `backend/src/main/java/com/wzx/babiq/server/test/ProviderTestController.java`:

```java
package com.wzx.babiq.server.test;

import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.model.ModelMetadata;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.test.dto.ChatRequest;
import com.wzx.babiq.server.test.dto.ChatResponse;
import com.wzx.babiq.server.test.dto.ProviderInfo;
import jakarta.validation.Valid;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * P1-2 临时测试端点,验证多 provider + memory 跨调用记忆。
 * P1-3 接入 WebSocket 后此 controller 可移除。
 */
@RestController
@RequestMapping("/api/test")
public class ProviderTestController {

    private final ModelProviderRegistry registry;
    private final ChatClientFactory clientFactory;

    public ProviderTestController(ModelProviderRegistry registry, ChatClientFactory clientFactory) {
        this.registry = registry;
        this.clientFactory = clientFactory;
    }

    /** GET /api/test/providers — 列出全部 provider。 */
    @GetMapping("/providers")
    public List<ProviderInfo> list() {
        String activeId = registry.active().id();
        return registry.list().stream()
            .map(cfg -> ProviderInfo.from(cfg,
                cfg.id().equals(activeId),
                resolveContextWindow(cfg)))
            .toList();
    }

    /**
     * POST /api/test/chat?providerId=xxx body {"text":"hi","threadId":"t1"}
     * 走真模型,D18 advisor 自动加载历史 + 写入新对话。
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestParam(required = false) String providerId,
                             @RequestBody @Valid ChatRequest req) {
        String pid = (providerId == null || providerId.isBlank()) ? registry.active().id() : providerId;
        ModelProviderConfig cfg = registry.get(pid);
        String threadId = (req.threadId() == null || req.threadId().isBlank()) ? "default" : req.threadId();

        // D18 关键:用 advisors-param 把 CONVERSATION_ID 传给 advisor,
        // 同一 providerId + 同一 threadId 在 MessageWindowChatMemory 中复用同一份历史
        String reply = clientFactory.resolve(pid).prompt()
            .user(req.text())
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, threadId))
            .call()
            .content();

        return new ChatResponse(pid, cfg.model(), threadId, reply);
    }

    private int resolveContextWindow(ModelProviderConfig cfg) {
        return cfg.contextWindow() != null
            ? cfg.contextWindow()
            : ModelMetadata.contextWindowOf(cfg.model());
    }
}
```

> ⚠️ **`ChatMemory.CONVERSATION_ID` 名字注意**: Spring AI 1.1.x 标准常量是 `ChatMemory.CONVERSATION_ID`(字符串 `"chat_memory_conversation_id"`)。若你的 BOM 解析的版本里没这个常量,直接用字符串 `"chat_memory_conversation_id"` 兜底,在代码里加注释说明。

- [ ] **Step 8.3: 编译验证**

Run:
```powershell
.\mvnw.cmd -q clean compile
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 8.4: Commit**

```powershell
git add -A
git commit -m "feat(p1-2): REST 测试端点 GET/POST /api/test/providers /chat"
```

- [ ] **Step 8.5: 加 @ControllerAdvice 全局错误处理**

新建 `backend/src/main/java/com/wzx/babiq/server/test/GlobalExceptionHandler.java`:

```java
package com.wzx.babiq.server.test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "error", "invalid_configuration",
            "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "error", "internal_error",
            "message", ex.getMessage()
        ));
    }
}
```

Commit:
```powershell
git add backend/src/main/java/com/wzx/babiq/server/test/GlobalExceptionHandler.java
git commit -m "feat(p1-2): 加 @ControllerAdvice 全局错误处理(替代裸 500 stack trace)"
```

---

## Task 9: 端到端烟测 + 跨轮记忆验证(M2 关键验收)

> 📌 **真模型 vs 兜底**:Step 9.3-9.5 优先用 Ollama 跑(无需联网,无成本)。若 Ollama 也没装,记录 SKIP 并改用 Step 9.6 的 mock 集成测试兜底。

**Files:**
- Create: `test/.../test/ProviderTestControllerIntegrationTest.java`

- [ ] **Step 9.1: 启动 backend**

Run(开第 1 个终端):
```powershell
cd F:\wwwxxxx\BaBiQ\backend
.\mvnw.cmd spring-boot:run
```

Expected: `Started BaBiQApplication`,端口 8080。**保持运行,后续步骤在第 2 个终端跑**。

- [ ] **Step 9.2: 验证 GET /api/test/providers**

Run(第 2 个终端):
```powershell
Invoke-RestMethod -Uri http://localhost:8080/api/test/providers -Method Get | ConvertTo-Json -Depth 4
```

Expected: JSON 数组,4 个元素,id 分别为 `dashscope-default` / `deepseek-official` / `oneapi-relay` / `ollama-local`;`active=true` 只在 `dashscope-default` 一个。

- [ ] **Step 9.3: 真模型 hello world(Ollama 兜底)**

> 前置:`ollama serve` 起来,`ollama pull llama3:8b` 完成。

Run:
```powershell
$body = @{ text = "用一句话介绍你自己"; threadId = "t-smoke" } | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:8080/api/test/chat?providerId=ollama-local" `
    -Method Post -Body $body -ContentType "application/json"
```

Expected: 收到 200 响应,`reply` 字段非空,大致是 llama3 的中文/英文介绍。

> 若有 DeepSeek key,改 `providerId=deepseek-official` 再跑一次,确认切 provider 也能返回(M2 验收硬要求)。

- [ ] **Step 9.4: 切到 deepseek-official 验证(条件性 SKIP)**

> 仅在 `DEEPSEEK_API_KEY` 已配置时执行;否则文档化 SKIP。

Run:
```powershell
$body = @{ text = "Hello"; threadId = "t-smoke-ds" } | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:8080/api/test/chat?providerId=deepseek-official" `
    -Method Post -Body $body -ContentType "application/json"
```

Expected: `providerId=deepseek-official`,`model=deepseek-chat`,`reply` 是 DeepSeek 真实回复。

- [ ] **Step 9.5: ⭐ 多 Turn 跨轮记忆验证(M2 最关键验收)**

> 这是 D18 的核心证明:**同一 threadId 第 2 次调用必须记得第 1 次的内容**。

**第 1 轮:**
```powershell
$body1 = @{ text = "我叫小明,我最喜欢的水果是芒果。请只回答'好的,我记住了'。"; threadId = "memory-test-1" } | ConvertTo-Json
$r1 = Invoke-RestMethod -Uri "http://localhost:8080/api/test/chat?providerId=ollama-local" `
    -Method Post -Body $body1 -ContentType "application/json"
$r1.reply
```

Expected: reply 大致包含"记住"等字样(任何模型都会复诵)。

**第 2 轮(同 threadId,问历史):**
```powershell
$body2 = @{ text = "请告诉我我的名字和最喜欢的水果。"; threadId = "memory-test-1" } | ConvertTo-Json
$r2 = Invoke-RestMethod -Uri "http://localhost:8080/api/test/chat?providerId=ollama-local" `
    -Method Post -Body $body2 -ContentType "application/json"
$r2.reply
```

Expected: reply 同时包含 **"小明"** 和 **"芒果"** 两个关键词。
**如果不包含**,说明 ChatMemoryAdvisor 没生效或 conversationId 没传:
- 检查 Step 8.2 的 `a.param(ChatMemory.CONVERSATION_ID, threadId)` 是否正确写入
- 检查 Step 6.2 的 `MessageChatMemoryAdvisor.builder(memory).build()` 是否真的挂上了 `defaultAdvisors`
- 用 DEBUG 日志看 Spring AI 实际发给模型的 messages 数组是否含第 1 轮内容

**第 3 轮(不同 threadId,验证隔离):**
```powershell
$body3 = @{ text = "请告诉我我的名字。"; threadId = "memory-test-2" } | ConvertTo-Json
$r3 = Invoke-RestMethod -Uri "http://localhost:8080/api/test/chat?providerId=ollama-local" `
    -Method Post -Body $body3 -ContentType "application/json"
$r3.reply
```

Expected: reply **不**包含"小明"(因为这是新 thread,记忆隔离)。如果包含,说明 conversationId 没生效,所有 thread 共用一份历史 → 必须修。

- [ ] **Step 9.6: 缺 api-key 的明确错误验证**

```powershell
# 临时清掉 dashscope 的 key 占位(在 Step 9.1 启动时如果 env 未设,Step 9.2 已经能列出但 chat 会失败)
$body = @{ text = "hi"; threadId = "t-err" } | ConvertTo-Json
try {
    Invoke-RestMethod -Uri "http://localhost:8080/api/test/chat?providerId=dashscope-default" `
        -Method Post -Body $body -ContentType "application/json" -ErrorAction Stop
} catch {
    $_.ErrorDetails.Message
}
```

Expected — 当 `AI_DASHSCOPE_API_KEY` 未设时:
- HTTP 400 + body 含 `"error":"invalid_configuration"`
- 错误信息含 `Provider [dashscope-default] (type=DASHSCOPE) 缺少 api-key`
- **不是** `NullPointerException`

- [ ] **Step 9.7: Ctrl+C 关闭 backend**

回第 1 个终端按 Ctrl+C。

- [ ] **Step 9.8: 写集成测试固化 Step 9.5 的多 Turn 记忆验证(关键回归)**

> 真模型调用费钱/网络不稳,集成测试用 **mock provider** 替代。核心是验证 advisor 真的把第 1 轮的 messages 塞到了第 2 轮的 prompt 里。

Create `backend/src/test/java/com/wzx/babiq/server/test/ProviderTestControllerIntegrationTest.java`:

```java
package com.wzx.babiq.server.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.model.BaBiQProperties;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "babiq.active-provider=mock-default",
    "babiq.providers[0].id=mock-default",
    "babiq.providers[0].type=DASHSCOPE",
    "babiq.providers[0].api-key=sk-test",
    "babiq.providers[0].model=qwen-plus",
    "babiq.memory.short-term.max-messages=20"
})
class ProviderTestControllerIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("GET /api/test/providers 返回配置的 provider 列表")
    void list_providers() throws Exception {
        mvc.perform(get("/api/test/providers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("mock-default"))
            .andExpect(jsonPath("$[0].active").value(true))
            .andExpect(jsonPath("$[0].contextWindow").value(1_000_000));
    }

    // 真模型多 Turn 记忆走 Step 9.5 手工验证;
    // 此处仅断言 endpoint wiring 通,业务记忆已由 ChatClientFactoryTest + Spring AI 自身覆盖。
}
```

Run:
```powershell
.\mvnw.cmd -q test -Dtest=ProviderTestControllerIntegrationTest
```

Expected: passed。

- [ ] **Step 9.9: 跨轮记忆自动化集成测试(M2 关键验收)**

新建 `backend/src/test/java/com/wzx/babiq/server/test/CrossTurnMemoryIT.java`:

> ⚠️ **关键**: 本测试**不用 `@SpringBootTest`**,纯单元测试手动构造 factory + mock `ProviderFactory`。
> 原因: `DashScopeProviderFactory.build()` 内部 `new DashScopeChatModel(...)`,**不走容器注入**;
> `@MockBean ChatModel` 拦截不到;且 `@SpringBootTest` 会因空 api-key (`${AI_DASHSCOPE_API_KEY:}`) 在 ProviderFactory 构造时抛 `IllegalStateException`。
>
> **要求 ChatClientFactory 提供可注入的构造函数** `ChatClientFactory(registry, Map<ProviderType, ProviderFactory> factories, int maxMessages)`,这是 P1-2 实现 (Task 6) 的硬要求 — 见 §"Done Criteria"。

```java
package com.wzx.babiq.server.test;

import com.wzx.babiq.server.model.*;
import com.wzx.babiq.server.model.provider.ProviderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 纯单元测试 — 不用 @SpringBootTest,手动构造 factory + mock ChatModel,
 * 避免依赖 application.yml api-key 与 Spring AI 标准路径 auto-config.
 *
 * 验证:
 *  1) MessageChatMemoryAdvisor 真把第 1 轮历史注入第 2 轮 prompt
 *  2) 不同 conversationId(threadId)的会话历史互不共享
 */
class CrossTurnMemoryIT {

    /** 构造能 echo 当前 messages 数量的 mock ChatModel. */
    private ChatModel mockEchoModel() {
        ChatModel m = mock(ChatModel.class);
        when(m.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            String content = "echo:" + p.getInstructions().size();
            return new ChatResponse(List.of(
                new Generation(new AssistantMessage(content))));
        });
        return m;
    }

    /**
     * 构造测试用 factory:fake ProviderFactory 返回 mock ChatModel,跳过真实 build.
     * 假定 ChatClientFactory 提供 (registry, factoriesMap, maxMessages) 构造函数.
     * 若实际 P1-2 实现签名不同,执行时按真实签名调整即可,语义不变.
     */
    private ChatClientFactory buildFactoryWithMock(ChatModel mockModel) {
        ProviderFactory fake = cfg -> mockModel;
        ModelProviderConfig cfg = new ModelProviderConfig(
            "dashscope-default", "DashScope mock",
            ProviderType.DASHSCOPE,
            null,        // base-url
            "sk-test",   // api-key(随意,fake factory 不用)
            "qwen-plus",
            null         // options
        );
        ModelProviderRegistry registry =
            new ModelProviderRegistry(List.of(cfg), "dashscope-default");
        return new ChatClientFactory(
            registry,
            Map.of(ProviderType.DASHSCOPE, fake),
            20  // maxMessages = §1 必做表 D18 规定
        );
    }

    @Test
    void second_turn_includes_first_turn_history() {
        ChatModel mockModel = mockEchoModel();
        ChatClientFactory factory = buildFactoryWithMock(mockModel);
        ChatClient client = factory.resolve("dashscope-default");
        String threadId = "thread-X";

        // 第 1 轮
        client.prompt().user("turn-1")
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, threadId))
            .call().content();

        // 第 2 轮 — MessageChatMemoryAdvisor 应把第 1 轮历史注入
        String reply2 = client.prompt().user("turn-2")
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, threadId))
            .call().content();

        // 第 2 轮 prompt 至少含:user(t1) + assistant(echo:X) + user(t2) = 3 条
        // 容忍 system prompt 可能让总数为 4
        assertThat(reply2).isIn("echo:3", "echo:4");
    }

    @Test
    void different_thread_id_does_not_share_history() {
        ChatModel mockModel = mockEchoModel();
        ChatClientFactory factory = buildFactoryWithMock(mockModel);
        ChatClient client = factory.resolve("dashscope-default");

        // thread-A 跑一轮
        client.prompt().user("turn-1")
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "thread-A"))
            .call().content();

        // thread-B 首次调用,应不见 A 的历史
        String replyB = client.prompt().user("turn-1")
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "thread-B"))
            .call().content();

        // thread-B 仅 user 1 条(无 system)或 system+user 2 条
        assertThat(replyB).isIn("echo:1", "echo:2");
    }
}
```

Run:
```powershell
cd backend
.\mvnw.cmd test -Dtest=CrossTurnMemoryIT
cd ..
```

Expected: 2 个测试都 PASS,证明 MessageChatMemoryAdvisor 真把第 1 轮历史注入第 2 轮 prompt,且不同 threadId 不串台。

Commit:
```powershell
git add backend/src/test/java/com/wzx/babiq/server/test/CrossTurnMemoryIT.java
git commit -m "test(p1-2): 加跨轮记忆自动化测试(M2 关键验收)"
```

- [ ] **Step 9.11: Commit**

```powershell
git add -A
git commit -m "test(p1-2): 集成测试 + Step 9.5 多 Turn 跨轮记忆手工验证文档化"
```

---

## Task 10: 文档同步 + tag

**Files:**
- Modify: `docs/ARCHITECTURE.md`(§8.3 类列表确认与实现一致;§14.4 ModelMetadata 已对齐)
- Modify: `docs/superpowers/plans/2026-05-21-p1-master.md`(M2 验收行勾上)

- [ ] **Step 10.1: 同步 ARCHITECTURE.md(若需要)**

打开 `docs/ARCHITECTURE.md` §8.3,确认列出的类与本 plan 实际产物一致:

```
backend/src/main/java/com/wzx/babiq/server/model/
├── ModelProviderRegistry.java      ✅
├── ModelProviderConfig.java        ✅
├── ChatClientFactory.java          ✅
├── ProviderType.java               ✅
├── ModelMetadata.java              ✅(§14.4 已述)
└── provider/
    ├── DashScopeProviderFactory.java   ✅
    ├── OpenAiCompatibleProviderFactory.java   ✅
    └── ProviderFactory.java        ✅
```

若 §8.3 中 package 路径写成了 `com.wzx.babiq.model` 而非 `com.wzx.babiq.server.model`,改过来(P1-0 已统一为 server 子包)。

- [ ] **Step 10.2: 最终全量测试 + 烟测**

Run:
```powershell
.\mvnw.cmd -q clean test
.\mvnw.cmd -q package -DskipTests
ls target\*.jar
```

Expected:
- 所有测试通过
- `babiq-server-0.0.1-SNAPSHOT.jar` 存在

- [ ] **Step 10.3: 终态 commit + log**

```powershell
git add -A
git status
# 若有未提交内容:
git commit -m "docs(p1-2): 同步 ARCHITECTURE 与 master plan M2 验收"
git log --oneline -15
```

Expected: 看到 P1-2 期间约 9-10 个 commit。**不要 push,不要打 tag**(由用户自行决定)。

---

## Done Criteria (M2 整体验收)

P1-2 算完成的硬标准(任一项不达成都需回到对应 Task 修复):

- [x] `application.yml` 配置了 **4 个 provider**:`dashscope-default` / `deepseek-official` / `oneapi-relay` / `ollama-local`
- [x] `GET /api/test/providers` 返回 4 个 provider 列表,`active=true` 唯一
- [x] `POST /api/test/chat?providerId=ollama-local` body `{"text":"hi"}` 返回真实模型回复(若无 Ollama 改用 DeepSeek/DashScope 至少一种)
- [x] 切到 `deepseek-official`(或其他有 key 的 provider)也能返回回复
- [x] `ChatClientFactory` 有针对 DashScope / OpenAI Compatible 双工厂的单测(`*ProviderFactoryTest` 共 9 tests)
- [x] 缺 api-key 时给明确错误(`IllegalStateException` 含 provider id 与可读说明)而非 NPE
- [x] ⭐ `ChatClientFactory.resolve(providerId)` 自动挂 `MessageChatMemoryAdvisor`(D18)
- [x] ⭐ `ModelMetadata.contextWindowOf("qwen-plus") == 1_000_000`;未知模型回退 32_768(D20)
- [x] ⭐ **多 Turn 跨轮记忆生效**:Step 9.5 同 threadId 第 2 轮 reply 包含第 1 轮 用户提到的"小明" + "芒果";不同 threadId 隔离
- [x] `ProviderTestControllerIntegrationTest` 跑通
- [x] `cd backend && .\mvnw.cmd clean package` 成功
- [x] **不要 push,不要打 tag**(由用户自行决定 push 时机)

---

## 关键风险点 ⚠️

| 风险 | 严重度 | 缓解 |
|---|---|---|
| **Starter auto-config 冲突** | **高** | application.yml 显式 disable(`spring.ai.dashscope.chat.enabled: false` + `spring.ai.openai.chat.enabled: false`),见 Step 7.1 |
| **Memory advisor 在多次 resolve(同 id) 时 ChatMemory 没复用,跨调用记忆失效** | **高** | ChatClientFactory.resolve 缓存 ChatClient 实例(Task 6 关键设计);ChatClientFactoryTest 的 `resolve_caches_per_id` 用例硬卡住 |
| **`conversationId` 没传,所有 thread 共用一份历史** | **高** | Step 8.2 controller 必须 `a.param(ChatMemory.CONVERSATION_ID, threadId)`;Step 9.5 第 3 轮(不同 threadId)显式断言隔离 |
| **SAA 1.1.2.x builder 方法名漂移**(.api / .dashScopeApi / .withModel / .model) | 中 | Task 4.3 注释中显式提示;编译失败立即查 java2ai.com 当时文档,不要硬猜 |
| **`MessageChatMemoryAdvisor` artifact 不确定** | 中 | 执行前 `mvn dependency:tree \| Select-String "advisors"` 验证;若不在 `spring-ai-advisors-vector-store`,改为 `spring-ai-client-chat` 或 `spring-ai-core` |
| **`MessageChatMemoryAdvisor` 找不到** | 中 | 由 `spring-ai-advisors-vector-store` artifact 提供,Task 1.2 已加;若仍缺,换 `spring-ai-advisors-memory`(版本依赖) |
| **空 api-key 场景在 Bean 创建期就失败,导致整个应用启动不了** | 中 | ChatClientFactory.resolve **懒构建 + 缓存**,Bean 阶段只校验配置存在;真正 build ChatModel 推到 resolve 时,只有调用方触发才报错 |
| **`OpenAiChatOptions.builder().model(...)`** 在 Ollama localhost 调时被服务器拒识别 | 低 | Ollama 11434 端口的 OpenAI 兼容模式要求 model 名是已 pulled 的(如 `llama3:8b`);Step 0.3 已提示 |
| **真 LLM 调用产生费用** | 低 | Step 9.3-9.5 优先 Ollama;DeepSeek 单次 hi 调用 < ¥0.001;DashScope 同理 |
| **ChatMemory 在 P1 全内存,backend 重启丢历史** | 低 | 这是 P1 预期行为,P2 上 SQLite `ChatMemoryRepository` 修复;P1-2 不解决 |

---

## 完成后下一步

P1-2 完成后:

1. 跑 **superpowers:verification-before-completion** 跨步验收
2. 让我为 **P1-3a(Agent Loop 内核)** 写详细 plan,引入:
   - 6 个工具实现(`@Tool` 注解 + MethodToolCallbackProvider)
   - ReAct 循环 + Hook 化(D21)
   - PathGuard 沙箱(D31)
   - HumanInTheLoopHook 审批(D23)
3. 在 `feat/p1-3a-agent-loop` 分支上推进

> P1-2 留下的 `test/ProviderTestController` 在 P1-3 接入 WebSocket 后可移除(或迁为 `@Profile("dev")` 仅开发期可用)。
