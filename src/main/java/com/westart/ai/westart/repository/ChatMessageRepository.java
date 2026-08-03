package com.westart.ai.westart.repository;

import com.westart.ai.westart.DTO.ChatMemorySearchRequest;
import com.westart.ai.westart.entity.ChatMessage;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.List;

/**
 * RAG中存储的聊天消息
 */
public interface ChatMessageRepository {

    /**
     * 批量插入用户历史消息
     * @param messages
     * @return
     */
    InsertResp insertBatch(List<ChatMessage> messages);

    /**
     * 对用户客观事实消息进行混合搜索
     * @param searchRequest
     * @return
     */
    SearchResp search(ChatMemorySearchRequest searchRequest);
}
