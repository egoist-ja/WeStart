package com.westart.ai.westart.mapper.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.westart.ai.westart.entity.ChatMessage;
import com.westart.ai.westart.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

/**
 * 聊天历史消息数据访问，使用LambdaWrapper构建SQL。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ChatMessageMapperImpl {

    private final ChatMessageMapper chatMessageMapper;

    /**
     * 批量写入聊天历史，主键重复时保留已存在的原始消息。
     */
    public int insertBatchIgnoreDuplicates(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return chatMessageMapper.insertBatchIgnoreDuplicates(messages);
    }

    /**
     * 将已完成长期记忆处理的聊天消息标记为已处理。
     */
    public int markMemoryProcessed(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        List<String> validIds = messageIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (validIds.isEmpty()) {
            return 0;
        }

        LambdaUpdateWrapper<ChatMessage> wrapper = new LambdaUpdateWrapper<ChatMessage>()
                .set(ChatMessage::getMemoryProcessed, true)
                .eq(ChatMessage::getMemoryProcessed, false)
                .in(ChatMessage::getMessageId, validIds);
        return chatMessageMapper.update(null, wrapper);
    }

    /**
     * 查询尚未完成长期记忆处理的消息ID。
     */
    public List<String> selectUnprocessedMessageIds(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> validIds = messageIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (validIds.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .select(ChatMessage::getMessageId)
                .eq(ChatMessage::getMemoryProcessed, false)
                .in(ChatMessage::getMessageId, validIds);
        return chatMessageMapper.selectList(wrapper).stream()
                .map(ChatMessage::getMessageId)
                .toList();
    }
}
