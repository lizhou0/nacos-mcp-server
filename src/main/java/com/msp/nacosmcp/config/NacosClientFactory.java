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

package com.msp.nacosmcp.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.ai.NacosAiService;
import com.alibaba.nacos.maintainer.client.ai.AiMaintainerService;
import com.alibaba.nacos.maintainer.client.ai.NacosAiMaintainerServiceImpl;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Builds and caches Nacos AI clients per namespace.
 *
 * <p>{@link AiService} pins its {@code namespaceId} at construction and exposes no per-call
 * override, so targeting a non-default namespace requires a separate instance. This factory
 * lazily creates and caches one {@link AiService} / {@link AiMaintainerService} per namespace,
 * falling back to the configured default ({@code nacos.namespace}) when a tool omits the
 * namespace argument.</p>
 */
@Component
public class NacosClientFactory {

    private static final String PUBLIC_NAMESPACE = "public";

    private final String addr;
    private final String username;
    private final String password;
    private final String contextPath;
    private final String aiTransportModeConstant;
    private final String defaultNamespace;

    private final ConcurrentMap<String, AiService> aiServices = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AiMaintainerService> maintainers = new ConcurrentHashMap<>();

    public NacosClientFactory(
            @Value("${nacos.addr}") String addr,
            @Value("${nacos.username:}") String username,
            @Value("${nacos.password:}") String password,
            @Value("${nacos.namespace:public}") String namespace,
            @Value("${nacos.context-path:nacos}") String ctxPath,
            @Value("${nacos.ai-transport-mode:http}") String transportMode) {
        this.addr = addr;
        this.username = username;
        this.password = password;
        this.contextPath = ctxPath;
        this.aiTransportModeConstant = "grpc".equalsIgnoreCase(transportMode)
                ? AiConstants.AI_TRANSPORT_MODE_GRPC
                : AiConstants.AI_TRANSPORT_MODE_HTTP;
        this.defaultNamespace = normalize(namespace);
    }

    /** Configured default namespace (normalized; never null or blank). */
    public String defaultNamespace() {
        return defaultNamespace;
    }

    /**
     * Resolve the effective namespace: the override if non-blank, otherwise the configured
     * default. Empty / blank / {@code "public"} are all normalized to {@code "public"}.
     */
    public String resolve(String override) {
        if (override == null || override.isBlank()) {
            return defaultNamespace;
        }
        return normalize(override);
    }

    /** Cached {@link AiService} for the resolved namespace, lazily built. */
    public AiService aiFor(String namespaceOverride) throws NacosException {
        return aiServices.computeIfAbsent(resolve(namespaceOverride), this::buildAi);
    }

    /** Cached {@link AiMaintainerService} for the resolved namespace, lazily built. */
    public AiMaintainerService maintainerFor(String namespaceOverride) throws NacosException {
        return maintainers.computeIfAbsent(resolve(namespaceOverride), this::buildMaintainer);
    }

    private AiService buildAi(String namespace) {
        try {
            Properties props = buildProperties(namespace);
            props.put(AiConstants.AI_TRANSPORT_MODE, aiTransportModeConstant);
            return new NacosAiService(props);
        } catch (NacosException e) {
            throw new IllegalStateException("Failed to build AiService for namespace: " + namespace, e);
        }
    }

    private AiMaintainerService buildMaintainer(String namespace) {
        try {
            Properties props = buildProperties(namespace);
            return new NacosAiMaintainerServiceImpl(props);
        } catch (NacosException e) {
            throw new IllegalStateException(
                    "Failed to build AiMaintainerService for namespace: " + namespace, e);
        }
    }

    private Properties buildProperties(String namespace) {
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, addr);
        props.put(PropertyKeyConst.CONTEXT_PATH, contextPath);
        if (username != null && !username.isEmpty()) {
            props.put(PropertyKeyConst.USERNAME, username);
            props.put(PropertyKeyConst.PASSWORD, password);
        }
        String ns = normalize(namespace);
        if (!ns.isEmpty() && !PUBLIC_NAMESPACE.equals(ns)) {
            props.put(PropertyKeyConst.NAMESPACE, ns);
        }
        return props;
    }

    private static String normalize(String ns) {
        if (ns == null) {
            return PUBLIC_NAMESPACE;
        }
        String trimmed = ns.trim();
        return trimmed.isEmpty() ? PUBLIC_NAMESPACE : trimmed;
    }

    @PreDestroy
    void shutdown() {
        aiServices.values().forEach(service -> {
            try {
                service.shutdown();
            } catch (Exception ignored) {
                // best-effort cleanup during context shutdown
            }
        });
        // AiMaintainerService has no shutdown on its interface; nothing to close.
    }
}
