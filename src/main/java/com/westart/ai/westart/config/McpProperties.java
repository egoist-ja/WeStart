package com.westart.ai.westart.config;

import dev.langchain4j.mcp.client.McpHeadersSupplier;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    private Map<String,ServerConfig> servers = new HashMap<>();

    @Data
    public static class ServerConfig {

        private String name;

        private boolean enabled = true;

        private String transport;

        private String url;

        private String authType;

        private String apiKey;

        private boolean logRequests;

        private boolean logResponses;

        private Map<String, String> extraHeaders = new HashMap<>();
    }
}
