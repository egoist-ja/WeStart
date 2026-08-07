package com.westart.ai.westart.memory.service;

import com.westart.ai.westart.memory.dto.MessageDTO;

import java.util.List;

/**
 * 用户主题记忆业务流程服务。
 *
 * 业务调用方只依赖该接口，不直接依赖模型或持久化实现。
 */
public interface UserTopicMemoryService {

    /**
     * 根据细筛后的聊天消息生成主题记忆，并持久化来源消息和主题。
     *
     * 来源消息已经全部持久化时跳过重复处理；模型没有生成主题时仍保存来源消息。
     * 本方法只保证MySQL事务完成，不等待Milvus索引写入。
     *
     * @param messages 细筛后的业务消息
     * @throws IllegalArgumentException 消息列表或消息字段不符合要求时抛出
     * @throws IllegalStateException 模型调用、结果校验或MySQL持久化失败时抛出
     */
    void updateTopicMemory(List<MessageDTO> messages);
}
