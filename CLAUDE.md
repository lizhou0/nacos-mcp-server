# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Spring Boot 3.3.5 + Spring AI MCP Server that exposes Nacos 3.2.1 AI resources (MCP servers, Skills, AgentSpecs, Prompts) as MCP tools, consumable by any MCP client (Claude Code, Cursor). Built on Java 17, packaged as a single jar.

## Commands

```bash
# Build
mvn clean package -DskipTests

# Run locally (overrides Nacos connection)
java -jar target/nacos-mcp-server-0.1.0.jar \
  --nacos.addr=127.0.0.1:8848 --nacos.username=nacos --nacos.password=nacos

# Docker
docker build -t nacos-mcp-server:0.1.0 .
docker run -p 9000:9000 -e NACOS_ADDR=nacos.internal:8848 nacos-mcp-server:0.1.0

# Run a single test (no tests exist yet; this is the pattern when added)
mvn test -Dtest=ClassName#methodName
```

No tests currently exist (`src/test` is absent). The project uses `spring-boot-starter-test` (JUnit) should any be added.

Server starts on port `9000`. MCP transport is controlled by `spring.ai.mcp.server.protocol` (env `MCP_PROTOCOL`) — only **one protocol is active at a time** (changed in Spring AI 1.1.x; 1.0.x exposed SSE + Streamable HTTP simultaneously):
- `STREAMABLE` (default): `/mcp` — recommended, supersedes SSE
- `SSE`: `/sse` + `/mcp/messages` — legacy, deprecated since Spring AI 2.0.0
- `STATELESS`: `/mcp` with no session — for microservice / cloud-native deployments

## Architecture

The whole project is three Java files. The non-obvious part is how a method annotated `@McpTool` ends up callable over the network — it requires reading all three files to see:

1. **`NacosAiTools`** (`tools/NacosAiTools.java`) — a `@Component` holding `@McpTool`-annotated methods (from `org.springaicommunity.mcp.annotation`, transitive via `spring-ai-mcp-annotations`). This is the only place MCP tools are defined. Each method's `@McpToolParam` annotations become the tool's input schema.
2. **`NacosMcpApplication`** (`NacosMcpApplication.java`) — just `@SpringBootApplication` + `main`. No bean definitions; the MCP annotation scanner auto-registers `@McpTool` methods.
3. **Spring AI MCP WebMVC starter** (from `spring-ai-starter-mcp-server-webmvc` in `pom.xml`) — auto-configures a single transport based on `spring.ai.mcp.server.protocol` in `application.yml`, and scans the classpath for `@McpTool` / `@McpResource` / `@McpPrompt` beans. No manual controller/servlet code exists.

**To add a new MCP tool:** add a `@McpTool` method to `NacosAiTools`. The framework handles publishing, schema generation, and dispatch. No other wiring needed.

### Two Nacos clients, split by responsibility

`NacosAiConfig` (`config/NacosAiConfig.java`) wires two beans from the same `nacos.*` properties:

- **`AiService`** (`com.alibaba.nacos.api.ai.AiService`, impl `NacosAiService`) — runtime read client. Handles token refresh, caching, and (optionally) gRPC transport. Used for fetching a single resource by name/version: `getMcpServer`, `downloadSkillZip*`, `loadAgentSpec`, `getPrompt*`. Its `shutdown()` is invoked on context close via `destroyMethod` declared reflectively (the interface doesn't expose it).
- **`AiMaintainerService`** (`com.alibaba.nacos.maintainer.client.ai.AiMaintainerService`, impl `NacosAiMaintainerServiceImpl`) — admin client. Used for list/search/version enumeration (`searchMcpServer`, `listSkills`, `getSkillMeta`, `listPrompts`, `listPromptVersions`) that `AiService` does not expose.

When adding a tool, pick the client by operation type: get-by-name/version → `ai`; list/search/meta → `maintainer`.

### Transport mode

`nacos.ai-transport-mode` (`http` default, or `grpc`) is translated to `AiConstants.AI_TRANSPORT_MODE_HTTP`/`_GRPC` and set on the Nacos `Properties` before constructing `AiService`. This affects only the Nacos client → server hop, not the MCP transport exposed to clients.

## Configuration

All `nacos.*` keys in `application.yml` map 1:1 to `NACOS_*` env vars (see `application.yml` for defaults). Standard Spring Boot relaxed binding applies — CLI flags like `--nacos.addr` work too.

## Known issues

- **Logging package mismatch:** `application.yml` sets `com.example.nacosmcp: INFO` but the actual package is `com.msp.nacosmcp` (see all `package` declarations and `.idea/workspace.xml`). The app's own logs currently fall through to root level. Fix the logging config if touching that section.
