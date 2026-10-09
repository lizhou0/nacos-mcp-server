# nacos-mcp-server (Java)

把 Nacos 上的 AI 资源(MCP / Skill / AgentSpec / A2A Agent / Prompt)暴露为 MCP 工具,供 Claude Code 等任意 MCP 客户端消费。

基于 Spring Boot 3.3 + Spring AI MCP Server (WebMVC) + nacos-client 3.2.1。

> **术语区分**:Nacos 里 `AgentSpec` 与 A2A `Agent`(即 `AgentCard`)是两类不同的资源 —— 前者是「Agent 编排规格」(声明 Agent 该用哪些 tools/skills/mcps/prompts),后者是 A2A 协议里的「Agent 名片」(声明能力、端点、鉴权方式等)。本服务两类都覆盖,工具命名上分别带 `AgentSpec` / `Agent` 前缀。

## 工具列表

按资源类型分组,共 14 个工具,均为**只读**。

### MCP Server
| 工具 | 作用 |
|---|---|
| `nacosListMcps` | 列出 / 搜索 Nacos 上的 MCP server(模糊匹配名称) |
| `nacosGetMcp` | 查询某个 MCP server 详情(含 tool schema 与 endpoint),可指定版本 |

### Skill
| 工具 | 作用 |
|---|---|
| `nacosListSkills` | 列出 / 搜索 Skill(模糊匹配名称 / 描述) |
| `nacosGetSkill` | 查询 Skill 元信息(描述、owner、上下线状态、标签及全部已发布版本) |
| `nacosDownloadSkill` | 下载 Skill ZIP 并解压到本地(默认 `.claude/skills/{name}`),支持按 version / label 拉取 |

### AgentSpec
| 工具 | 作用 |
|---|---|
| `nacosListAgentSpecs` | 列出 / 搜索 AgentSpec |
| `nacosGetAgentSpec` | 查询 AgentSpec 元信息(描述、在线状态、标签及全部已发布版本) |
| `nacosLoadAgentSpec` | 加载完整 AgentSpec(主配置 + tools/skills/mcps/prompts 成员),可指定版本,默认 latest |

### A2A Agent (AgentCard)
| 工具 | 作用 |
|---|---|
| `nacosListAgents` | 列出 / 搜索 A2A Agent(模糊匹配名称) |
| `nacosListAgentVersions` | 列出某 Agent 的全部已发布版本(version / 创建更新时间 / 是否 latest) |
| `nacosGetAgent` | 拉取 AgentCard 详情(capabilities / skills / endpoints / authentication / provider),可指定版本,默认 latest |

### Prompt
| 工具 | 作用 |
|---|---|
| `nacosListPrompts` | 列出 / 搜索 Prompt(模糊匹配 key) |
| `nacosListPromptVersions` | 列出某 Prompt 的全部已发布版本(状态、commit message、修改人) |
| `nacosGetPrompt` | 获取 Prompt 文本与变量,可按 version / label 拉取 |

## 构建与运行

```bash
mvn clean package -DskipTests
```

### 本地直接运行

```bash
java -jar target/nacos-mcp-server-0.1.0.jar \
  --nacos.addr=127.0.0.1:8848 \
  --nacos.username=nacos \
  --nacos.password=nacos
```

启动后提供两种 MCP 传输:
- SSE:`http://localhost:9000/sse`(消息端点 `/mcp/messages`)
- Streamable HTTP:`http://localhost:9000/mcp`

### Docker

```bash
mvn clean package -DskipTests
docker build -t nacos-mcp-server:0.1.0 .
docker run -p 9000:9000 -e NACOS_ADDR=nacos.internal:8848 nacos-mcp-server:0.1.0
```

## Claude Code 接入

在项目根目录或 `~/.claude.json` 中添加 `.mcp.json`:

```json
{
  "mcpServers": {
    "nacos": {
      "url": "http://localhost:9000/sse",
      "transport": "sse"
    }
  }
}
```

带网关(TLS + Bearer)的生产示例:

```json
{
  "mcpServers": {
    "nacos": {
      "url": "https://mcp.internal.example.com/sse",
      "transport": "sse",
      "headers": {
        "Authorization": "Bearer nacos-mcp-xxxxxxxxxxxx"
      }
    }
  }
}
```

启动 Claude Code 后用 `/mcp` 验证 `nacos` 已连接,工具全部可用。

## 使用示例

```
> Nacos 上有哪些 MCP server?
> 把 code-review 这个 Skill 下载下来
> 把 nacos-ai-prompt 这个 prompt 拿出来作为接下来的系统提示
> Nacos 上注册了哪些 Agent?模糊搜一下带 "review" 的
> 把 customer-service 这个 Agent 的所有版本列出来
> 拉 customer-service 这个 AgentCard 的详情,我看下它的能力与端点
> Nacos 上有没有现成的 AgentSpec?加载 code-review-assistant 这个 AgentSpec
```

## 配置项

| 配置 | 环境变量 | 默认值 |
|---|---|---|
| `nacos.addr` | `NACOS_ADDR` | `127.0.0.1:8848` |
| `nacos.username` | `NACOS_USERNAME` | `nacos` |
| `nacos.password` | `NACOS_PASSWORD` | `nacos` |
| `nacos.namespace` | `NACOS_NAMESPACE` | `public` |
| `nacos.context-path` | `NACOS_CONTEXT_PATH` | `nacos` |
| `nacos.ai-transport-mode` | `NACOS_AI_TRANSPORT_MODE` | `http` |

> `ai-transport-mode` 可选 `http` 或 `grpc`。默认 HTTP 与 RESTful 风格一致;gRPC 适合大规模集群。

## 安全建议

- 为本服务在 Nacos 上**单独建一个只读账号**,只授予 AI 资源的 READ 权限
- 生产部署必须前置 APISIX/Kong 网关做 TLS + Bearer Token 鉴权
- `nacos.password` 通过环境变量注入,不要硬编码或提交到 git

## License

[Apache License 2.0](LICENSE)



