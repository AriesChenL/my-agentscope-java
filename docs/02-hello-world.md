# 第 2 章 · 环境准备与 Hello World

> 目标：从零把项目跑起来，在浏览器里完成第一次对话。出问题能定位到是 JDK / 网络 / Key 哪一类。

## 2.1 先决条件

| 软件 | 版本 | 验证命令 |
|------|------|---------|
| JDK | **21+**（必须） | `java -version` |
| Maven | 任意（项目自带 wrapper，可不装） | `./mvnw -v` |
| 浏览器 | 任意现代浏览器 | — |
| 网络 | 能访问任一 LLM provider 的 API | — |

JDK 21 是硬要求 —— 项目用到了 sealed interface（`ContentBlock`）、record、`switch` 表达式增强等只在 21 才稳定的特性。如果你的 `java -version` 显示 8/11/17，直接装 [Eclipse Temurin 21](https://adoptium.net/) 即可。

## 2.2 拿一个 LLM API Key

**最便宜最快**：DeepSeek

- 注册：https://platform.deepseek.com/
- 充值 1 元能用很久
- API base URL：`https://api.deepseek.com`
- 协议兼容 OpenAI，本项目里走 `agentscope.providers.openai.*` 槽位

**境外 provider**（需要代理）：

- Anthropic Claude：https://console.anthropic.com/
- Google Gemini：https://aistudio.google.com/

下面以 DeepSeek 为例。

## 2.3 配置 API Key

### 方式 A：本地配置文件（推荐开发用）

复制模板：

```bash
cp src/main/resources/application-local.properties.example \
   src/main/resources/application-local.properties
```

编辑 `application-local.properties`：

```properties
agentscope.providers.openai.api-key=sk-xxxxxxxxxxxxxxxx
```

⚠️ **`application-local.properties` 已在 `.gitignore`**，不会被提交到 git。

### 方式 B：环境变量（推荐 CI / 生产）

`application.properties` 默认有这一行：

```properties
agentscope.providers.openai.api-key-env=DEEPSEEK_API_KEY
```

意思是：当 `api-key` 没显式配时，去读 `DEEPSEEK_API_KEY` 环境变量。

```powershell
$env:DEEPSEEK_API_KEY = "sk-xxxxxxxxxxxxxxxx"
.\mvnw.cmd spring-boot:run
```

## 2.4 启动

项目根目录下：

```bash
# Windows PowerShell
.\mvnw.cmd spring-boot:run

# Linux / Mac
./mvnw spring-boot:run
```

第一次启动 Maven Wrapper 会下载对应版本的 Maven（约 30 秒），之后用缓存秒启。

启动成功后控制台会看到：

```
Started AgentScopeApplication in 3.241 seconds (process running for 3.612)
Tomcat started on port 8080 (http) with context path '/'
```

如果失败，看 §2.7 排错。

## 2.5 第一次对话

### Web UI

打开 http://localhost:8080/chat.html

1. 左上角"新建对话"按钮 → 输入对话名 → 选择 DeepSeek
2. 输入框输入"你好" → 回车
3. 应该能看到回复一个字一个字地"打字机式"出现

### 测试工具调用

输入：**"123 加 456 等于多少？"**

正常情况下你会看到：

1. 一个金色卡片：`add(a=123, b=456)` —— 模型决定调 `CalculatorTool.add`
2. 一个绿色卡片：`579` —— 工具执行结果
3. 助手回复：`123 加 456 等于 579`

这就是一次完整的 ReAct 循环。

### 测试 HITL（ask_user）

输入：**"帮我订一张机票"**

模型会判断信息不全，调用内置 `ask_user` 工具。前端会渲染一个表单卡片让你填出发地、目的地、日期。填完提交，agent 才会继续。

## 2.6 命令行 Demo（不用浏览器）

`com.lynn.myagentscopejava.demo.FullDemoRunner` 是一个 `CommandLineRunner`，启动时如果开启了某个 profile 会跑一遍完整 demo。看代码就懂，这里跳过。

## 2.7 常见排错

### 启动报 `至少要启用一个 provider`

`application.properties` 中三个 `*.enabled` 都是 `false`，或者你启用的 provider 没配 key。检查：

```properties
agentscope.providers.openai.enabled=true        # 至少一个 true
agentscope.providers.openai.api-key=sk-xxx      # 或 api-key-env 指向有效环境变量
```

### 报 `SSLHandshakeException` / `Connection refused` / `EOFException`

境内访问海外 API 网络问题。两条路：

**路径 1：改用 DeepSeek**（不需要代理）。
**路径 2：开代理**：

```properties
agentscope.http.proxy.enabled=true
agentscope.http.proxy.host=127.0.0.1
agentscope.http.proxy.port=7890        # 你本地 Clash / V2Ray 的端口
```

项目已经在 `WebClient` 上挂了"瞬时网络错误自动重试"过滤器（指数退避 2 次），偶发抖动不需要你管。

### 报 `401 Unauthorized` / `403`

Key 错了或过期了。直接去 provider 的控制台再生成一个。

### 报 `Module ... has been compiled by a more recent version of the Java Runtime`

JDK 版本不对。检查 `java -version` 和 `mvn -v` 显示的 Java 版本是否都 ≥ 21。

### 端口 8080 被占用

```properties
server.port=8081
```

加到 `application-local.properties` 里。

## 2.8 回头看一眼配置文件

打开 `src/main/resources/application.properties`，第 7-44 行就是你刚才配的所有东西的来源。重点字段：

- `agentscope.name` / `agentscope.sys-prompt` —— Agent 名字和 system prompt
- `agentscope.providers.{openai|anthropic|gemini}.*` —— 三个 provider 各自的开关与 key
- `agentscope.providers.default-id` —— 新建对话默认走哪家
- `agentscope.session.dir` —— 对话历史落盘到哪（默认 `./.agent-state`）

第 4 章会带你看这些配置怎么映射到 Java 代码（`AgentProperties.java`）。

## 2.9 自检

- [ ] 我能描述启动命令做了什么（Spring Boot 启动 → Tomcat 在 8080 → 访问 /chat.html）
- [ ] 我能在浏览器里完成一次"加法"对话，并能指出哪里是 reasoning 哪里是 acting
- [ ] 我知道 API key 在哪里配、不会被 commit 到 git
- [ ] 我知道遇到 SSL 错误第一步该改什么

下一章我们看代码地图，建立"想改 X 该看哪个文件"的导航能力。
