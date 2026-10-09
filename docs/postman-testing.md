# Postman 测试指南

本项目默认暴露 MCP Streamable HTTP 端点 `http://localhost:9000/mcp`,协议为 JSON-RPC 2.0。本文档描述如何用 Postman 手动完成 MCP 握手并调用工具。

> 如果只想要快速验证服务是否正常,跳到文末 [curl 一行验证](#curl-一行验证)。

---

## 前置条件

- 服务已启动:`mvn spring-boot:run` 或 `java -jar target/nacos-mcp-server-0.1.0.jar`
- 默认端口 `9000`,默认传输 `STREAMABLE`(端点 `/mcp`)
- Postman 11.x+(老版本也可,只要能自定义 Header 和 raw JSON Body)
- 准备一个固定 UUID 作为 session id,例如 `11111111-2222-3333-4444-555555555555`,整个会话复用

> 在线生成 UUID:https://www.uuidgenerator.net/

---

## 关键概念

MCP Streamable HTTP 是**有状态**协议,流程分三步:

1. `initialize` → 协商版本和能力,客户端**自带** session id
2. `notifications/initialized` → 通知服务端初始化完成
3. `tools/list` / `tools/call` → 正常工具调用,所有请求都带同一 session id

⚠️ Spring AI MCP 1.1.x 与 MCP 规范的差异:**客户端必须在 initialize 请求里就自己提供 `Mcp-Session-Id` 请求头**,服务端不会在下发响应里主动返回。

---

## Step 1:初始化会话

### 请求

- **Method:** `POST`
- **URL:** `http://localhost:9000/mcp`

### Headers

| Key | Value |
|---|---|
| `Content-Type` | `application/json` |
| `Accept` | `application/json, text/event-stream` |
| `Mcp-Session-Id` | `11111111-2222-3333-4444-555555555555` |

> ⚠️ `Accept` 值里逗号后**必须有一个空格**,写成 `application/json, text/event-stream`,不要写 `application/json,text/event-stream`。

### Body

Postman Body 标签选 **raw → JSON**(右边下拉框选 JSON,不要选 Text),粘进去:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-06-18",
    "capabilities": {},
    "clientInfo": {"name": "postman", "version": "1.0"}
  }
}
```

> ⚠️ `protocolVersion` 用 `2025-06-18`。Spring AI 1.1.7 对版本字符串校验较严,填错会报 `invalid message format`。

### 预期响应

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-06-18",
    "capabilities": {...},
    "serverInfo": {"name": "nacos-mcp-server", "version": "0.1.0"}
  }
}
```

如果响应是 `text/event-stream` 格式,在 Postman Body 切换到 **EventStream** 视图查看,JSON-RPC 结果在 `data:` 字段里。

---

## Step 2:发送 initialized 通知

握手必须以一条 notification 结束,否则部分服务端不会接受后续工具调用。

### Headers

与 Step 1 完全相同,**`Mcp-Session-Id` 必须是同一个值**。

### Body

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/initialized"
}
```

> ⚠️ 这是 notification,**没有 `id` 字段**。服务端不会返回任何响应(或返回 HTTP 202),这是正常的。

---

## Step 3:列出工具

### Headers

同 Step 1。

### Body

```json
{"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
```

### 预期响应

返回 `result.tools` 数组,列出 `NacosAiTools` 中全部 14 个 `@McpTool` 方法,每个工具包含 `name`、`description`、`inputSchema`。从里面挑要调的工具,照 `inputSchema` 填参数。

---

## Step 4:调用工具

以 `nacosListMcps` 为例,模糊搜索 `nacos`:

### Headers

同 Step 1。

### Body

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "nacosListMcps",
    "arguments": {"keyword": "nacos"}
  }
}
```

`name` 必须与 `tools/list` 返回的工具名完全一致(区分大小写),`arguments` 按 schema 填。

### 工具名速查

完整列表见 [README.md](../README.md#工具列表),常用的几个:

| 工具 | 示例 arguments |
|---|---|
| `nacosListMcps` | `{"keyword": "nacos"}` |
| `nacosGetMcp` | `{"name": "xxx", "version": "latest"}` |
| `nacosListSkills` | `{"keyword": "code-review"}` |
| `nacosDownloadSkill` | `{"name": "code-review", "version": "latest", "targetDir": "/tmp/skills"}` |
| `nacosGetPrompt` | `{"key": "nacos-ai-prompt", "version": "latest"}` |

---

## 常见报错排查

### `session id required in mcp-session-id header`

请求没带 `Mcp-Session-Id` 请求头。Spring AI MCP 1.1.x 要求**所有请求**(包括 initialize)都带这个头。补上即可。

### `invalid message format`

JSON-RPC 解析失败。按以下顺序排查:

1. **Body 类型错了**:必须选 `raw → JSON`,不能是 `form-data` 或 `x-www-form-urlencoded`
2. **Accept header 格式错**:逗号后要有空格,`application/json, text/event-stream`
3. **protocolVersion 不被支持**:用 `2025-06-18`
4. **智能引号**:从聊天框/富文本复制时 `"` 被替换成 `"` `"`,在 Postman Console 查看实际 Request Body,确认是直引号
5. **Content-Type 重复或错误**:Postman 选 raw JSON 后会自动加 `Content-Type: application/json`,如果手动也加了一条可能冲突

### 后续请求突然 401 / session not found

`Mcp-Session-Id` 用了不同值。整次测试会话必须用**同一个** UUID。如果用了 `{{$guid}}` 动态变量,每次请求会生成新值,不能跨请求复用——改用写死的固定 UUID。

### 响应看不到 JSON,全是 `data: {...}`

服务端用 SSE 流返回。Postman Body 视图切到 **EventStream**,或直接把 `data:` 前缀去掉看里面的 JSON。

---

## 备选方案

### Postman 原生 MCP 客户端(推荐)

Postman 11.x+ 内置 MCP 支持,自动处理握手:

1. 新建 Collection → Add Request
2. 类型选 **MCP**(不是 HTTP)
3. Server URL 填 `http://localhost:9000/mcp`
4. Postman 自动跑 initialize,工具列表直接列出
5. 点工具 → 填表单 → Send,无需手写 JSON-RPC

省去手动管理 session id 和握手步骤,日常调试推荐这种方式。

### curl 一行验证

如果想跳过 Postman、最快确认服务正常:

```bash
# 1. initialize + initialized 合并测试(分两次请求更规范,这里简化)
curl -i -X POST http://localhost:9000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: 11111111-2222-3333-4444-555555555555" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'

# 2. 列出工具(同一个 session id)
curl -i -X POST http://localhost:9000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: 11111111-2222-3333-4444-555555555555" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 3. 调用工具
curl -i -X POST http://localhost:9000/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: 11111111-2222-3333-4444-555555555555" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"nacosListMcps","arguments":{"keyword":"nacos"}}}'
```

curl 通了,说明服务端没问题;再回 Postman 排查配置。
