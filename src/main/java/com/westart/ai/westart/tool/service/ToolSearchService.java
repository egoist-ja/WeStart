package com.westart.ai.westart.tool.service;

import com.westart.ai.westart.tool.entity.ToolEntity;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.util.List;

/**
 * 动态工具调用
 */
public interface ToolSearchService {

    List<ToolEntity> searchTools(String query);

    void initializeTools(ApplicationReadyEvent event);
}
