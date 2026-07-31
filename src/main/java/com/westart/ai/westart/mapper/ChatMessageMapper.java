package com.westart.ai.westart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.westart.ai.westart.entity.ChatMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 聊天历史消息Mapper，负责chat_message表的数据访问。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 批量插入聊天消息，主键重复时保留数据库原记录。
     *
     * @param messages 待插入的聊天消息
     * @return 实际插入行数
     */
    @Insert("""
            <script>
            INSERT IGNORE INTO chat_message
                (message_id, wechat_user_id, role, content, created_at, memory_processed)
            VALUES
            <foreach collection="messages" item="message" separator=",">
                (#{message.messageId}, #{message.wechatUserId}, #{message.role},
                 #{message.content}, #{message.createdAt}, #{message.memoryProcessed})
            </foreach>
            </script>
            """)
    int insertBatchIgnoreDuplicates(@Param("messages") List<ChatMessage> messages);
}
