package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 待入库工具注册中心。
 *
 * 收集应用中的本地工具定义和已初始化的MCP客户端，作为工具元数据写入
 * Milvus时的数据来源。AI服务运行时的工具注册和执行由LangChain4j负责，
 * 不通过该注册中心完成。
 */
@Slf4j
public class ToolRegistry {

    /**
     * 按Bean名称分组的待入库本地工具。
     */
    private final Map<String, Map<ToolSpecification, ToolExecutor>> localTools = new LinkedHashMap<>();

    /**
     * 按客户端标识分组的待入库MCP客户端。
     */
    private final Map<String, McpClient> mcpClients = new LinkedHashMap<>();

    /**
     * 解析并登记指定Bean中使用@Tool标记的待入库工具方法。
     *
     * @param beanName 工具Bean名称
     * @param toolBean 工具Bean对象
     * @throws IllegalArgumentException 参数无效或Bean中不存在工具方法时抛出
     * @throws IllegalStateException Bean重复注册时抛出
     */
    public void registerLocalTool(String beanName, Object toolBean) {
        if (beanName == null || beanName.isBlank()) {
            throw new IllegalArgumentException("工具Bean名称不能为空");
        }
        if (toolBean == null) {
            throw new IllegalArgumentException("工具Bean不能为空");
        }
        if (localTools.containsKey(beanName)) {
            throw new IllegalStateException("本地工具Bean已注册：" + beanName);
        }

        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        for (Method method : toolBean.getClass().getMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }

            ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
            tools.put(specification,
                    new DefaultToolExecutor(toolBean, method));
        }

        if (tools.isEmpty()) {
            throw new IllegalArgumentException(
                    "工具Bean中不存在使用@Tool标记的方法：" + beanName);
        }
        ToolSpecifications.validateSpecifications(tools.keySet().stream().toList());
        localTools.put(beanName, Collections.unmodifiableMap(tools));
        log.info("本地工具Bean注册成功，beanName：{}，工具数量：{}",
                beanName, tools.size());
    }

    /**
     * 获取按Bean名称分组的待入库本地工具。
     *
     * @return 不可修改的本地工具映射
     */
    public Map<String, Map<ToolSpecification, ToolExecutor>> localTools() {
        return Collections.unmodifiableMap(localTools);
    }

    /**
     * 登记已初始化成功的待入库MCP客户端。
     *
     * @param mcpClient MCP客户端
     * @throws IllegalArgumentException 客户端或客户端标识无效时抛出
     * @throws IllegalStateException 客户端重复注册时抛出
     */
    public void registerMcpClient(McpClient mcpClient) {
        if (mcpClient == null) {
            throw new IllegalArgumentException("MCP客户端不能为空");
        }

        String clientKey = mcpClient.key();
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("MCP客户端标识不能为空");
        }
        if (mcpClients.containsKey(clientKey)) {
            throw new IllegalStateException("MCP客户端已注册：" + clientKey);
        }

        mcpClients.put(clientKey, mcpClient);
        log.info("MCP客户端注册成功，clientKey：{}", clientKey);
    }

    /**
     * 获取按客户端标识分组的待入库MCP客户端。
     *
     * @return 不可修改的MCP客户端映射
     */
    public Map<String, McpClient> mcpClients() {
        return Collections.unmodifiableMap(mcpClients);
    }
}
