package com.westart.ai.westart.memory.repository;

import com.westart.ai.westart.memory.dto.UserTopicMemoryQueryRequest;
import com.westart.ai.westart.memory.entity.UserTopicMemory;

import java.util.List;

/**
 * 用户主题记忆存储仓库,写入MySQL
 */
public interface UserTopicMemoryRepository {

    /**
     * 批量插入用户主题记忆。
     *
     * @param userTopicMemories 待保存的主题记忆
     * @return 成功写入的主题记忆数量
     */
    int insertBatch(List<UserTopicMemory> userTopicMemories);

    /**
     * 查询指定用户最近的主题记忆。
     *
     * @param request 查询条件
     * @return 按主题发生时间倒序排列的主题记忆
     */
    List<UserTopicMemory> selectRecent(UserTopicMemoryQueryRequest request);

}
