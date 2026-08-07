package com.westart.ai.westart.memory.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.westart.ai.westart.memory.domain.TopicMemoryIndexStatus;
import com.westart.ai.westart.memory.dto.UserTopicMemoryQueryRequest;
import com.westart.ai.westart.memory.entity.MemorySourceMessage;
import com.westart.ai.westart.memory.entity.UserTopicMemory;
import com.westart.ai.westart.memory.mapper.MemorySourceMessageMapper;
import com.westart.ai.westart.memory.mapper.UserTopicMemoryMapper;
import com.westart.ai.westart.memory.repository.UserTopicMemoryRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 用户主题记忆MySQL仓储实现。
 *
 * 封装来源消息与主题记忆的事务写入和查询，不涉及Milvus向量存储。
 */
@Repository
@RequiredArgsConstructor
public class MySqlUserTopicMemoryRepository implements UserTopicMemoryRepository {

    private final MemorySourceMessageMapper memorySourceMessageMapper;
    private final UserTopicMemoryMapper userTopicMemoryMapper;

    /**
     * 查询当前批次已经持久化的消息ID，并返回尚未持久化的来源消息。
     *
     * @param sourceMessages 待检查的来源消息
     * @return 保持原始顺序的未持久化来源消息
     */
    @Override
    public List<MemorySourceMessage> selectUnstoredSourceMessages(
            List<MemorySourceMessage> sourceMessages) {
        if (sourceMessages == null || sourceMessages.isEmpty()) {
            return List.of();
        }

        String wechatUserId = validateAndResolveWechatUserId(sourceMessages);
        List<String> messageIds = sourceMessages.stream()
                .map(MemorySourceMessage::getMessageId)
                .toList();
        List<MemorySourceMessage> storedMessages = memorySourceMessageMapper.selectList(
                new LambdaQueryWrapper<MemorySourceMessage>()
                        .select(MemorySourceMessage::getMessageId)
                        .eq(MemorySourceMessage::getWechatUserId, wechatUserId)
                        .in(MemorySourceMessage::getMessageId, messageIds));
        Set<String> storedMessageIds = new HashSet<>(storedMessages.size());
        storedMessages.stream()
                .map(MemorySourceMessage::getMessageId)
                .forEach(storedMessageIds::add);
        return sourceMessages.stream()
                .filter(message -> !storedMessageIds.contains(message.getMessageId()))
                .toList();
    }

    /**
     * 在同一个MySQL事务中保存来源消息和主题记忆。
     *
     * @param sourceMessages 待保存的来源消息
     * @param topicMemories 待保存的主题记忆
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSourceMessagesAndTopics(
            List<MemorySourceMessage> sourceMessages,
            List<UserTopicMemory> topicMemories) {
        if (sourceMessages == null || sourceMessages.isEmpty()) {
            throw new IllegalArgumentException("来源消息列表不能为空");
        }
        validateAndResolveWechatUserId(sourceMessages);
        if (topicMemories == null) {
            throw new IllegalArgumentException("主题记忆列表不能为空");
        }
        if (topicMemories.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("主题记忆列表不能包含空元素");
        }

        memorySourceMessageMapper.insert(sourceMessages);
        if (!topicMemories.isEmpty()) {
            userTopicMemoryMapper.insert(topicMemories);
            if (topicMemories.stream()
                    .anyMatch(memory -> memory.getTopicMemoryId() == null)) {
                throw new IllegalStateException("主题记忆批量插入失败");
            }
        }
    }

    /**
     * 按主题记忆ID升序查询待索引主题，保证每轮处理顺序稳定。
     *
     * @param limit 最大返回数量
     * @return 待索引主题记忆
     */
    @Override
    public List<UserTopicMemory> selectPendingIndex(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("待索引主题查询数量必须大于0");
        }

        LambdaQueryWrapper<UserTopicMemory> queryWrapper =
                new LambdaQueryWrapper<UserTopicMemory>()
                        .eq(
                                UserTopicMemory::getIndexStatus,
                                TopicMemoryIndexStatus.PENDING)
                        .orderByAsc(UserTopicMemory::getTopicMemoryId)
                        .last("LIMIT " + limit);
        return userTopicMemoryMapper.selectList(queryWrapper);
    }

    /**
     * 仅将仍处于PENDING状态的主题更新为DONE。
     *
     * @param topicMemoryIds 已成功写入Milvus的主题记忆ID
     */
    @Override
    public void markIndexed(List<Long> topicMemoryIds) {
        if (topicMemoryIds == null || topicMemoryIds.isEmpty()) {
            return;
        }
        if (topicMemoryIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("主题记忆ID列表不能包含空元素");
        }

        LambdaUpdateWrapper<UserTopicMemory> updateWrapper =
                new LambdaUpdateWrapper<UserTopicMemory>()
                        .in(UserTopicMemory::getTopicMemoryId, topicMemoryIds)
                        .eq(
                                UserTopicMemory::getIndexStatus,
                                TopicMemoryIndexStatus.PENDING)
                        .set(
                                UserTopicMemory::getIndexStatus,
                                TopicMemoryIndexStatus.DONE);
        int affectedRows = userTopicMemoryMapper.update(updateWrapper);
        if (affectedRows != topicMemoryIds.size()) {
            throw new IllegalStateException("主题记忆索引状态更新数量不一致");
        }
    }

    /**
     * 查询指定用户最近的主题记忆。
     *
     * @param request 查询条件
     * @return 按发生时间和主题记忆ID倒序排列的主题记忆
     */
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

    /**
     * 校验来源消息，并返回当前批次所属的微信用户ID。
     *
     * @param sourceMessages 来源消息
     * @return 微信用户ID
     */
    private static String validateAndResolveWechatUserId(
            List<MemorySourceMessage> sourceMessages) {
        String wechatUserId = null;
        Set<String> messageIds = new HashSet<>(sourceMessages.size());
        for (MemorySourceMessage sourceMessage : sourceMessages) {
            if (sourceMessage == null) {
                throw new IllegalArgumentException("来源消息列表不能包含空元素");
            }
            if (StringUtils.isBlank(sourceMessage.getWechatUserId())) {
                throw new IllegalArgumentException("来源消息缺少微信用户ID");
            }
            if (StringUtils.isBlank(sourceMessage.getMessageId())) {
                throw new IllegalArgumentException("来源消息缺少消息ID");
            }
            if (!messageIds.add(sourceMessage.getMessageId())) {
                throw new IllegalArgumentException(
                        "来源消息包含重复消息ID：" + sourceMessage.getMessageId());
            }
            if (StringUtils.isBlank(sourceMessage.getRole())) {
                throw new IllegalArgumentException("来源消息缺少角色");
            }
            if (sourceMessage.getContent() == null) {
                throw new IllegalArgumentException("来源消息内容不能为null");
            }
            if (sourceMessage.getOccurredAt() == null) {
                throw new IllegalArgumentException("来源消息缺少发生时间");
            }
            if (wechatUserId == null) {
                wechatUserId = sourceMessage.getWechatUserId();
            } else if (!wechatUserId.equals(sourceMessage.getWechatUserId())) {
                throw new IllegalArgumentException("来源消息不能包含多个微信用户");
            }
        }
        return wechatUserId;
    }
}
