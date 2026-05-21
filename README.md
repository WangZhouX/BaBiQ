# BaBiQ — Spring AI Alibaba 学习项目

> 一个基于 **Spring AI Alibaba** 框架构建 **Java Agent** 的学习型项目。
> 目标：通过动手实践，掌握使用 Spring 生态搭建 LLM 智能体应用的完整链路。

---

## 项目定位

本仓库不是生产级框架，而是个人学习用的实验场。
聚焦在 **Spring AI Alibaba** 这一栈上，逐步实现以下能力：

- 调用大模型（通义千问 / DashScope 等）完成对话与推理
- 使用 Prompt 模板、结构化输出
- 接入 Function Calling / Tool 调用
- 构建 RAG（检索增强生成）流程
- 编排多步骤 Agent 工作流（Workflow / Graph）
- 集成记忆（Memory）与多轮会话上下文

---

## 技术栈

| 组件 | 版本 / 说明 |
| --- | --- |
| JDK | Java 25（框架最低要求 JDK 17+） |
| Spring Boot | 3.5.14 |
| 构建工具 | Maven（含 mvnw） |
| 核心框架 | [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba) v1.1.2.x |
| Agent Framework | `spring-ai-alibaba-agent-framework` 1.1.2.0 |
| 模型 Starter | `spring-ai-alibaba-starter-dashscope` 1.1.2.1 |
| 辅助 | Lombok、Spring Boot DevTools |

> 版本以 [Maven Central](https://central.sonatype.com/search?q=spring-ai-alibaba) 上的最新发布为准，本表为撰文时（基于 context7 拉取的官方文档）的当前版本。

---

## 目录结构

```
BaBiQ/
├── src/
│   ├── main/
│   │   ├── java/com/wzx/babiq/        # 业务代码（Agent、Tool、Service 等）
│   │   │   └── BaBiQApplication.java  # 启动类
│   │   └── resources/
│   │       └── application.properties # 配置文件（模型 Key、参数）
│   └── test/
│       └── java/com/wzx/babiq/        # 单元测试
├── pom.xml                            # Maven 依赖
└── README.md
```

---

## 快速开始

### 1. 环境准备

- JDK 17+（项目当前配置为 25）
- Maven 3.8+（或直接使用项目自带的 `mvnw` / `mvnw.cmd`）
- 一个可用的大模型 API Key（推荐 [阿里云百炼 DashScope](https://bailian.console.aliyun.com/)）

### 2. 配置 API Key

**推荐使用环境变量**（PowerShell）：

```powershell
$env:AI_DASHSCOPE_API_KEY = "sk-你的key"
```

然后把 `src/main/resources/application.properties` 改为 `application.yml`，写入：

```yaml
spring:
  application:
    name: BaBiQ
  ai:
    dashscope:
      api-key: ${AI_DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen-plus      # 可选：qwen-turbo / qwen-max / qwen-max-longcontext / qwq-plus
          temperature: 0.7
```

> ⚠️ 官方约定的环境变量名是 **`AI_DASHSCOPE_API_KEY`**，不要写错。
> ⚠️ 千万不要把 Key 硬编码到代码或提交到 Git。

### 3. 添加 Spring AI Alibaba 依赖

在 `pom.xml` 的 `<dependencies>` 中加入：

```xml
<!-- Agent 编排框架（ReactAgent / AssistantAgent / Graph） -->
<dependency>
	<groupId>com.alibaba.cloud.ai</groupId>
	<artifactId>spring-ai-alibaba-agent-framework</artifactId>
	<version>1.1.2.0</version>
</dependency>

<!-- DashScope (通义千问) Starter，自动装配 ChatClient / ChatModel -->
<dependency>
	<groupId>com.alibaba.cloud.ai</groupId>
	<artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
	<version>1.1.2.1</version>
</dependency>

<!-- Web 接口（写一个 /chat 路由） -->
<dependency>
	<groupId>org.springframework.boot</groupId>
	<artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

### 4. 写第一个 Hello LLM 接口

新建 `src/main/java/com/wzx/babiq/controller/ChatController.java`：

```java
package com.wzx.babiq.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

	private final ChatClient chatClient;

	public ChatController(ChatClient.Builder builder) {
		this.chatClient = builder.build();
	}

	@GetMapping("/chat")
	public String chat(@RequestParam(defaultValue = "你好,请介绍下你自己") String input) {
		return chatClient.prompt()
			.user(input)
			.call()
			.content();
	}
}
```

### 5. 启动并验证

```powershell
# Windows PowerShell
.\mvnw.cmd spring-boot:run

# 浏览器访问
# http://localhost:8080/chat?input=用一句话解释什么是Agent
```

---

## 学习路线（建议按顺序推进）

| 阶段 | 主题 | 关键 API / 概念 |
| --- | --- | --- |
| 1 | **Hello LLM** | `ChatClient.Builder` → `prompt().user().call().content()` |
| 2 | **流式输出** | `chatClient.prompt().stream().content()` 返回 `Flux<String>` |
| 3 | **Prompt 工程** | `PromptTemplate`、`SystemMessage`、`BeanOutputConverter` 结构化输出 |
| 4 | **Tool / Function Calling** | `@Tool` 注解 / `FunctionToolCallback.builder()` |
| 5 | **Memory 记忆** | `ChatMemory`、`MessageChatMemoryAdvisor` |
| 6 | **RAG 检索增强** | `VectorStore`、`DocumentReader`、`QuestionAnswerAdvisor` |
| 7 | **Agent 编排** | `AssistantAgent` / `ReactAgent`（agent-framework 提供） |
| 8 | **Graph 多 Agent** | `StateGraph`、`NodeAction`、多智能体协同 / 路由 |
| 9 | **MCP 集成** | 作为 MCP Server 暴露能力，或作为 MCP Client 调用外部工具 |
| 10 | **可观测性** | Spring AI Alibaba + ARMS / Micrometer 链路追踪 |

---

## 参考资料

- 📦 GitHub 源码：[alibaba/spring-ai-alibaba](https://github.com/alibaba/spring-ai-alibaba)
- 📖 官方中文文档：[java2ai.com](https://java2ai.com/)
- 🚀 快速开始：[java2ai.com/docs/quick-start](https://java2ai.com/docs/quick-start)
- 🤖 Agent 入门：[AssistantAgent Quick Start](https://java2ai.com/agents/assistantagent/quick-start)
- 🧩 ChatClient 用法：[java2ai.com/integration/chatclient](https://java2ai.com/integration/chatclient)
- 💡 示例仓库：[spring-ai-alibaba-examples](https://github.com/springaialibaba/spring-ai-alibaba-examples)
- 🌱 上游框架：[Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
- 🛠 模型服务：[阿里云百炼 DashScope](https://bailian.console.aliyun.com/)
- 🍃 Spring Boot：[3.5.14 Reference](https://docs.spring.io/spring-boot/3.5.14/reference/)

---

## 备注

- 项目主分支保持可运行，每完成一个学习模块就提交一次，commit 信息描述本次学到的概念
- 实验性代码放在 `feat/xxx` 分支，避免污染主线
- 涉及到的 Key、Secret 一律走环境变量，不进仓库
