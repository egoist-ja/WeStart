package com.westart.ai.westart.memory.repository;

import com.westart.ai.westart.memory.dto.UserTopicMemoryQueryRequest;
import com.westart.ai.westart.memory.entity.MemorySourceMessage;
import com.westart.ai.westart.memory.entity.UserTopicMemory;

import java.util.List;

/**
 * 用户主题记忆MySQL仓储。
 */
public interface UserTopicMemoryRepository {

    /**
     * 从当前批次中过滤已经持久化的来源消息。
     *
     * @param sourceMessages 待检查的来源消息
     * @return 保持原始顺序的未持久化来源消息
     */
    List<MemorySourceMessage> selectUnstoredSourceMessages(
            List<MemorySourceMessage> sourceMessages);

    /**
     * 在同一个MySQL事务中保存来源消息和主题记忆。
     *
     * @param sourceMessages 待保存的来源消息
     * @param topicMemories 待保存的主题记忆，允许为空列表
     */
    void saveSourceMessagesAndTopics(
            List<MemorySourceMessage> sourceMessages,
            List<UserTopicMemory> topicMemories);

    /**
     * 查询等待写入Milvus索引的主题记忆。
     *
     * @param limit 最大返回数量
     * @return 按主题记忆ID升序排列的待索引主题
     * @throws IllegalArgumentException 查询数量不大于0时抛出
     */
    List<UserTopicMemory> selectPendingIndex(int limit);

    /**
     * 将指定主题记忆的索引状态更新为已完成。
     *
     * @param topicMemoryIds 已成功写入Milvus的主题记忆ID
     * @throws IllegalArgumentException ID列表包含空元素时抛出
     * @throws IllegalStateException 实际更新数量与ID数量不一致时抛出
     */
    void markIndexed(List<Long> topicMemoryIds);

    /**
     * 查询指定用户最近的主题记忆。
     *
     * @param request 查询条件
     * @return 按主题发生时间倒序排列的主题记忆
     */
    List<UserTopicMemory> selectRecent(UserTopicMemoryQueryRequest request);

}
