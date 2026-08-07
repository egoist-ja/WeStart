package com.westart.ai.westart.memory.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.westart.ai.westart.memory.dto.UserTopicMemoryQueryRequest;
import com.westart.ai.westart.memory.entity.UserTopicMemory;
import com.westart.ai.westart.memory.mapper.UserTopicMemoryMapper;
import com.westart.ai.westart.memory.repository.UserTopicMemoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 用户主题记忆MySQL仓储实现。
 *
 * <p>封装主题记忆的批量写入和按用户查询，不涉及Milvus向量存储。</p>
 */
@Repository
@RequiredArgsConstructor
public class MySqlUserTopicMemoryRepository implements UserTopicMemoryRepository {

    private final UserTopicMemoryMapper userTopicMemoryMapper;

    @Override
    @Transactional
    public int insertBatch(List<UserTopicMemory> userTopicMemories) {
        if (userTopicMemories == null || userTopicMemories.isEmpty()) {
            return 0;
        }
        if (userTopicMemories.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("主题记忆列表不能包含空元素");
        }

        userTopicMemoryMapper.insert(userTopicMemories);
        if (userTopicMemories.stream()
                .anyMatch(memory -> memory.getTopicMemoryId() == null)) {
            throw new IllegalStateException("主题记忆批量插入失败");
        }
        return userTopicMemories.size();
    }

    @Override
    public List<UserTopicMemory> selectRecent(
            UserTopicMemoryQueryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("主题记忆查询请求不能为空");
        }

        LambdaQueryWrapper<UserTopicMemory> queryWrapper =
                new LambdaQueryWrapper<UserTopicMemory>()
                        .eq(
                                UserTopicMemory::getWechatUserId,
                                request.wechatUserId())
                        .orderByDesc(UserTopicMemory::getTopicOccurredAt)
                        .orderByDesc(UserTopicMemory::getTopicMemoryId)
                        .last("LIMIT " + request.limit());
        return userTopicMemoryMapper.selectList(queryWrapper);
    }
}
