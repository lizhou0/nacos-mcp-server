/*
 * Copyright 2026 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.msp.nacosmcp.tools;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardVersionInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentVersionDetail;
import com.alibaba.nacos.api.ai.model.agentspecs.AgentSpec;
import com.alibaba.nacos.api.ai.model.agentspecs.AgentSpecBasicInfo;
import com.alibaba.nacos.api.ai.model.agentspecs.AgentSpecMeta;
import com.alibaba.nacos.api.ai.model.mcp.McpServerBasicInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.ai.model.prompt.PromptMetaSummary;
import com.alibaba.nacos.api.ai.model.prompt.PromptVersionSummary;
import com.alibaba.nacos.api.ai.model.skills.SkillMeta;
import com.alibaba.nacos.api.ai.model.skills.SkillSummary;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.model.Page;
import com.alibaba.nacos.maintainer.client.ai.AiMaintainerService;
import com.msp.nacosmcp.config.NacosClientFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * MCP tool definitions backed by Nacos AI resources.
 *
 * <p>Each {@link McpTool @McpTool} method is auto-published to the MCP endpoint by Spring AI MCP,
 * so any MCP-compatible client (Claude Code / Cursor / etc.) can call them with natural language.</p>
 *
 * <p>Every tool accepts an optional {@code namespace} argument. When omitted (null or blank),
 * the configured {@code nacos.namespace} is used; otherwise the supplied namespace is targeted.
 * Per-namespace clients are cached by {@link NacosClientFactory}.</p>
 */
@Component
public class NacosAiTools {

    private static final String NAMESPACE_PARAM_DESC =
            "Nacos namespace; empty for the configured default namespace.";

    private final NacosClientFactory clients;

    public NacosAiTools(NacosClientFactory clients) {
        this.clients = clients;
    }

    /* ==================== MCP ==================== */

    @McpTool(description = "List MCP servers registered on Nacos. Supports fuzzy keyword match.")
    public Page<McpServerBasicInfo> nacosListMcps(
            @McpToolParam(description = "MCP name keyword (fuzzy)", required = false) String keyword,
            @McpToolParam(description = "Page number, default 1", required = false) Integer pageNo,
            @McpToolParam(description = "Page size, default 20", required = false) Integer pageSize,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
        String name = keyword == null ? "" : keyword;
        String ns = clients.resolve(namespace);
        AiMaintainerService maintainer = clients.maintainerFor(ns);
        return maintainer.mcp().searchMcpServer(ns, name, page, size);
    }

    @McpTool(description = "Query MCP server detail from Nacos, including tool specifications and endpoints.")
    public McpServerDetailInfo nacosGetMcp(
            @McpToolParam(description = "MCP server name") String name,
            @McpToolParam(description = "Version, empty for latest", required = false) String version,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        AiService ai = clients.aiFor(namespace);
        return ai.getMcpServer(name, emptyToNull(version));
    }

    /* ==================== Skill ==================== */

    @McpTool(description = "List Skills registered on Nacos. Supports fuzzy keyword match on name/description.")
    public Page<SkillSummary> nacosListSkills(
            @McpToolParam(description = "Skill name or description keyword (fuzzy)", required = false) String keyword,
            @McpToolParam(description = "Page number, default 1", required = false) Integer pageNo,
            @McpToolParam(description = "Page size, default 20", required = false) Integer pageSize,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
        String name = keyword == null ? "" : keyword;
        String ns = clients.resolve(namespace);
        AiMaintainerService maintainer = clients.maintainerFor(ns);
        return maintainer.skill().listSkills(ns, name, "", page, size);
    }

    @McpTool(description = "Query Skill metadata from Nacos, including description, owner, online status, "
            + "labels and all published versions. Use this before nacosDownloadSkill to discover available versions.")
    public SkillMeta nacosGetSkill(
            @McpToolParam(description = "Skill name") String name,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        String ns = clients.resolve(namespace);
        AiMaintainerService maintainer = clients.maintainerFor(ns);
        return maintainer.skill().getSkillMeta(ns, name);
    }

    @McpTool(description = "Download a Skill from Nacos and return it as a base64-encoded ZIP. "
            + "Bytes are returned inline so they can be saved on the caller's machine regardless of "
            + "where the MCP server runs. The caller (or its LLM) should decode the base64 to a .zip "
            + "file, then extract it locally — MCP has no cross-host file-write primitive.")
    public String nacosDownloadSkill(
            @McpToolParam(description = "Skill name") String name,
            @McpToolParam(description = "Version; empty for label/latest", required = false) String version,
            @McpToolParam(description = "Label; empty for latest", required = false) String label,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace)
            throws NacosException {

        AiService ai = clients.aiFor(namespace);
        byte[] zip;
        String qualifier;
        if (version != null && !version.isBlank()) {
            zip = ai.downloadSkillZipByVersion(name, version);
            qualifier = "version=" + version;
        } else if (label != null && !label.isBlank()) {
            zip = ai.downloadSkillZipByLabel(name, label);
            qualifier = "label=" + label;
        } else {
            zip = ai.downloadSkillZip(name);
            qualifier = "latest";
        }

        String ns = clients.resolve(namespace);
        String b64 = Base64.getEncoder().encodeToString(zip);
        return "Skill '" + name + "' (" + qualifier + ") fetched from namespace '" + ns + "'.\n"
                + "Raw ZIP size: " + zip.length + " bytes.\n"
                + "The content below is base64-encoded. Decode it to a .zip file then extract.\n"
                + "\n"
                + b64;
    }

    /* ==================== AgentSpec ==================== */

    @McpTool(description = "List AgentSpecs registered on Nacos. Supports fuzzy keyword match on name/description.")
    public Page<AgentSpecBasicInfo> nacosListAgentSpecs(
            @McpToolParam(description = "AgentSpec name or description keyword (fuzzy)", required = false) String keyword,
            @McpToolParam(description = "Page number, default 1", required = false) Integer pageNo,
            @McpToolParam(description = "Page size, default 20", required = false) Integer pageSize,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
        String name = keyword == null ? "" : keyword;
        String ns = clients.resolve(namespace);
        AiMaintainerService maintainer = clients.maintainerFor(ns);
        return maintainer.agentSpec().listAgentSpecs(ns, name, "", page, size);
    }

    @McpTool(description = "Query AgentSpec metadata from Nacos, including description, online status, labels, "
            + "bizTags and all published versions (version/status/author/downloadCount). "
            + "Use this before nacosLoadAgentSpec to discover available versions.")
    public AgentSpecMeta nacosGetAgentSpec(
            @McpToolParam(description = "AgentSpec name") String name,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        String ns = clients.resolve(namespace);
        AiMaintainerService maintainer = clients.maintainerFor(ns);
        return maintainer.agentSpec().getAgentSpecAdminDetail(ns, name);
    }

    @McpTool(description = "Load an AgentSpec from Nacos with full resources (tools/skills/mcps/prompts members). "
            + "Returns the latest version by default; pass a specific version to load that one. "
            + "Use nacosGetAgentSpec first to list available versions.")
    public AgentSpec nacosLoadAgentSpec(
            @McpToolParam(description = "AgentSpec name") String name,
            @McpToolParam(description = "Version; empty for latest", required = false) String version,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        String ns = clients.resolve(namespace);
        if (version == null || version.isBlank()) {
            AiService ai = clients.aiFor(ns);
            return ai.loadAgentSpec(name);
        }
        AiMaintainerService maintainer = clients.maintainerFor(ns);
        return maintainer.agentSpec().getAgentSpecVersionDetail(ns, name, version);
    }

    /* ==================== Agent (A2A AgentCard) ==================== */

    @McpTool(description = "List A2A Agents (AgentCards) registered on Nacos. Supports fuzzy keyword match on agent name.")
    public Page<AgentCardVersionInfo> nacosListAgents(
            @McpToolParam(description = "Agent name keyword (fuzzy)", required = false) String keyword,
            @McpToolParam(description = "Page number, default 1", required = false) Integer pageNo,
            @McpToolParam(description = "Page size, default 20", required = false) Integer pageSize,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
        String pattern = keyword == null ? "" : keyword;
        String ns = clients.resolve(namespace);
        AiMaintainerService maintainer = clients.maintainerFor(ns);
        return maintainer.searchAgentCardsByName(ns, pattern, page, size);
    }

    @McpTool(description = "List all published versions of an A2A Agent (AgentCard) on Nacos, including "
            + "version, creation/update time and latest flag.")
    public java.util.List<AgentVersionDetail> nacosListAgentVersions(
            @McpToolParam(description = "Agent name") String name,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        String ns = clients.resolve(namespace);
        AiMaintainerService maintainer = clients.maintainerFor(ns);
        // A2A convention: listAllVersionOfAgent(name, namespace) — name first, ns second.
        return maintainer.listAllVersionOfAgent(name, ns);
    }

    @McpTool(description = "Fetch an A2A Agent (AgentCard) detail from Nacos, including capabilities, skills, "
            + "endpoints, authentication and provider info. Returns the latest version by default; "
            + "pass a specific version to load that one. Use nacosListAgentVersions to discover versions.")
    public AgentCardDetailInfo nacosGetAgent(
            @McpToolParam(description = "Agent name") String name,
            @McpToolParam(description = "Version; empty for latest", required = false) String version,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        AiService ai = clients.aiFor(namespace);
        if (version == null || version.isBlank()) {
            return ai.getAgentCard(name);
        }
        return ai.getAgentCard(name, version);
    }

    /* ==================== Prompt ==================== */

    @McpTool(description = "List Prompts registered on Nacos. Supports fuzzy keyword match on prompt key.")
    public Page<PromptMetaSummary> nacosListPrompts(
            @McpToolParam(description = "Prompt key keyword (fuzzy)", required = false) String keyword,
            @McpToolParam(description = "Page number, default 1", required = false) Integer pageNo,
            @McpToolParam(description = "Page size, default 20", required = false) Integer pageSize,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
        String key = keyword == null ? "" : keyword;
        String ns = clients.resolve(namespace);
        AiMaintainerService maintainer = clients.maintainerFor(ns);
        return maintainer.prompt().listPrompts(ns, key, "", "", page, size);
    }

    @McpTool(description = "List published versions of a Prompt on Nacos, including status, commit message and modifier.")
    public Page<PromptVersionSummary> nacosListPromptVersions(
            @McpToolParam(description = "Prompt key") String promptKey,
            @McpToolParam(description = "Page number, default 1", required = false) Integer pageNo,
            @McpToolParam(description = "Page size, default 20", required = false) Integer pageSize,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
        String ns = clients.resolve(namespace);
        AiMaintainerService maintainer = clients.maintainerFor(ns);
        return maintainer.prompt().listPromptVersions(ns, promptKey, page, size);
    }

    @McpTool(description = "Fetch a Prompt from Nacos. Returns prompt text and variables.")
    public Prompt nacosGetPrompt(
            @McpToolParam(description = "Prompt key") String promptKey,
            @McpToolParam(description = "Version", required = false) String version,
            @McpToolParam(description = "Label", required = false) String label,
            @McpToolParam(description = NAMESPACE_PARAM_DESC, required = false) String namespace) throws NacosException {
        AiService ai = clients.aiFor(namespace);
        if (version != null && !version.isBlank()) {
            return ai.getPromptByVersion(promptKey, version);
        }
        if (label != null && !label.isBlank()) {
            return ai.getPromptByLabel(promptKey, label);
        }
        return ai.getPrompt(promptKey);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
