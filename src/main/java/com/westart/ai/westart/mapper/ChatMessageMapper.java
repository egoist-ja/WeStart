package com.westart.ai.westart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.westart.ai.westart.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天历史消息Mapper，负责chat_message表的数据访问。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
