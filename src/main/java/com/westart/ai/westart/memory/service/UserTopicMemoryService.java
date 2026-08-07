package com.westart.ai.westart.memory.service;

import com.westart.ai.westart.memory.dto.MessageDTO;

import java.util.List;

/**
 * 用户主题记忆业务流程服务。
 *
 * <p>业务调用方只依赖该接口，不直接依赖模型或持久化实现。</p>
 */
public interface UserTopicMemoryService {

    /**
     * 根据公共过滤后的聊天消息生成并持久化主题记忆。
     *
     * <p>没有消息或没有生成有效主题分块时不执行持久化。</p>
     *
     * @param messages 公共过滤后的业务消息
     */
    void updateTopicMemory(List<MessageDTO> messages);
}
