package com.westart.ai.westart.config;

import com.westart.ai.westart.service.tool.ToolRegistry;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 本地工具配置。
 */
@Configuration(proxyBeanMethods = false)
public class ToolConfig {

    /**
     * 本地工具所在的基础包路径。
     */
    private static final String TOOL_PACKAGE =
            "com.westart.ai.westart.service.tool";

    /**
     * 创建工具注册中心，并注册工具包中使用@Tool标记的方法。
     *
     * @param beanFactory Spring Bean工厂
     * @return 完成本地工具注册的工具注册中心
     */
    @Bean
    public ToolRegistry toolRegistry(ListableBeanFactory beanFactory) {
        ToolRegistry toolRegistry = new ToolRegistry();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> beanType = beanFactory.getType(beanName); //获取bean类型
            if (!isToolBean(beanType)) {
                continue;
            }
            toolRegistry.registerLocalTool(
                    beanName,
                    beanFactory.getBean(beanName));
        }
        return toolRegistry;
    }

    /**
     * 判断Bean类型是否属于工具包并包含工具方法。
     *
     * @param beanType Bean类型
     * @return 包含工具方法时返回true，否则返回false
     */
    private boolean isToolBean(Class<?> beanType) {
        if (beanType == null) {
            return false;
        }

        Class<?> userClass = ClassUtils.getUserClass(beanType);
        String packageName = userClass.getPackageName();
        if (!packageName.equals(TOOL_PACKAGE)
                && !packageName.startsWith(TOOL_PACKAGE + ".")) {
            return false;
        }
        return Arrays.stream(userClass.getMethods())
                .anyMatch(this::isToolMethod);
    }

    /**
     * 判断方法是否使用@Tool标记。
     *
     * @param method 待判断的方法
     * @return 使用@Tool标记时返回true，否则返回false
     */
    private boolean isToolMethod(Method method) {
        return method.isAnnotationPresent(Tool.class);
    }
}
