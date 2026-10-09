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

package com.msp.nacosmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Nacos AI MCP Server entry point.
 *
 * <p>Exposes Nacos AI resources (MCP / Skill / AgentSpec / Prompt) as MCP tools,
 * consumable by Claude Code or any MCP-compatible client.</p>
 *
 * <p>Tools are discovered automatically via {@code @McpTool} annotations on
 * {@link com.msp.nacosmcp.tools.NacosAiTools} — no manual registration needed.</p>
 */
@SpringBootApplication
public class NacosMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(NacosMcpApplication.class, args);
    }
}
