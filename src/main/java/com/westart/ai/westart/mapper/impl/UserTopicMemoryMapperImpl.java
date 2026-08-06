package com.westart.ai.westart.mapper.impl;

import com.westart.ai.westart.entity.UserTopicMemoryEntity;
import com.westart.ai.westart.mapper.UserTopicMemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户主题记忆数据访问，封装MyBatis-Plus新增操作。
 */
@Repository
@RequiredArgsConstructor
public class UserTopicMemoryMapperImpl {

    private final UserTopicMemoryMapper userTopicMemoryMapper;

    /**
     * 批量新增主题记忆，由MySQL回填自增topicMemoryId。
     *
     * @param topicMemories 等待写入MySQL的主题记忆实体
     * @return 批量操作中受影响的行数
     */
    @Transactional
    public int insertBatch(List<UserTopicMemoryEntity> topicMemories) {
        if (topicMemories == null || topicMemories.isEmpty()) {
            return 0;
        }
        int affectedRows = 0;
        for (UserTopicMemoryEntity topicMemory : topicMemories) {
            affectedRows += userTopicMemoryMapper.insert(topicMemory);
            if (topicMemory.getTopicMemoryId() == null) {
                throw new IllegalStateException("MySQL未回填topicMemoryId");
            }
        }
        return affectedRows;
    }
}
