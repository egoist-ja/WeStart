package com.westart.ai.westart.tool.runtime;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待入库工具注册中心。
 *
 * 收集应用中的本地工具定义和已初始化的MCP工具，统一提供工具元数据
 * 入库及本地工具运行时注册所需的数据。
 */
public class ToolRegistry {

    /**
     * 按Bean名称分组的待入库本地工具。
     */
    private final Map<String, List<AiServiceTool>> localTools =
            new LinkedHashMap<>();

    /**
     * 按客户端标识分组的待入库MCP工具。
     */
    private final Map<String, List<ToolSpecification>> mcpTools =
            new LinkedHashMap<>();

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

        List<AiServiceTool> tools = new ArrayList<>();
        for (Method method : toolBean.getClass().getMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }

            ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
            ToolExecutor executor = new DefaultToolExecutor(toolBean, method);
            tools.add(AiServiceTool.builder()
                    .toolSpecification(specification)
                    .toolExecutor(executor)
                    .returnBehavior(method.getAnnotation(Tool.class).returnBehavior())
                    .build());
        }

        if (tools.isEmpty()) {
            throw new IllegalArgumentException(
                    "工具Bean中不存在使用@Tool标记的方法：" + beanName);
        }
        ToolSpecifications.validateSpecifications(tools.stream()
                .map(AiServiceTool::toolSpecification)
                .toList());
        localTools.put(beanName, List.copyOf(tools));
    }

    /**
     * 获取按Bean名称分组的待入库本地工具。
     *
     * @return 不可修改的本地工具映射
     */
    public Map<String, List<AiServiceTool>> localTools() {
        return Collections.unmodifiableMap(localTools);
    }

    /**
     * 获取保留返回行为的本地AI服务工具。
     *
     * @return 不可修改的本地AI服务工具列表
     */
    public List<AiServiceTool> localAiServiceTools() {
        return localTools.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    /**
     * 登记已初始化成功的待入库MCP工具。
     *
     * @param clientKey MCP客户端标识
     * @param toolSpecifications 带客户端命名空间的MCP工具定义
     * @throws IllegalArgumentException 参数无效时抛出
     * @throws IllegalStateException 客户端工具重复注册时抛出
     */
    public void registerMcpTools(
            String clientKey,
            List<ToolSpecification> toolSpecifications) {
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("MCP客户端标识不能为空");
        }
        if (toolSpecifications == null) {
            throw new IllegalArgumentException("MCP工具定义列表不能为空");
        }
        if (mcpTools.containsKey(clientKey)) {
            throw new IllegalStateException(
                    "MCP客户端工具已注册：" + clientKey);
        }

        List<ToolSpecification> tools = toolSpecifications.stream()
                .filter(tool -> tool != null)
                .toList();
        ToolSpecifications.validateSpecifications(tools);
        mcpTools.put(clientKey, tools);
    }

    /**
     * 获取按客户端标识分组的待入库MCP工具。
     *
     * @return 不可修改的MCP工具映射
     */
    public Map<String, List<ToolSpecification>> mcpTools() {
        return Collections.unmodifiableMap(mcpTools);
    }
}
