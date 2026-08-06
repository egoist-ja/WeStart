package com.westart.ai.westart.repository;

import com.westart.ai.westart.entity.UserTopicMemoryEntity;

import java.util.List;

/**
 * 用户主题记忆存储仓库。
 *
 * <p>分别提供主存储和向量存储写入能力，由上层决定写入顺序。</p>
 */
public interface TopicMemoryRepository {

    /**
     * 将最终主题记忆JSON写入MySQL的user_topic_memory表。
     *
     * @param assembledJson 已完成校验和字段组装的主题记忆JSON
     * @return 已由MySQL回填topicMemoryId的主题记忆
     */
    List<UserTopicMemoryEntity> saveToMysql(String assembledJson);

    /**
     * 将MySQL已持久化的主题记忆向量化后写入Milvus。
     *
     * @param persistedMemories 已包含topicMemoryId的MySQL主题记忆
     */
    void saveToMilvus(List<UserTopicMemoryEntity> persistedMemories);
}
