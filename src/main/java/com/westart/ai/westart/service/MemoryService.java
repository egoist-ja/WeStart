package com.westart.ai.westart.service;

import com.westart.ai.westart.entity.UserMemory;

import java.util.List;

/**
 * 聊天记忆服务。
 *
 * <p>业务服务只通过该接口使用记忆能力，不直接依赖Redis、数据库或记忆模型。</p>
 */
public interface MemoryService {

    /**
     * 根据微信用户ID取得稳定的聊天记忆ID。
     *
     * @param wechatUserId 微信消息发送者的from_user_id
     * @return 与登录会话无关的聊天记忆ID
     */
    String resolveMemoryId(String wechatUserId);

    /**
     * 调用第一阶段记忆模型，从已经通过主题筛选的用户消息中筛选用户画像候选消息。
     *
     * <p>模型返回结果还会经过后端校验，只保留输入中真实存在且角色为USER的消息。</p>
     *
     * @param messages 已经通过主题筛选的原始USER消息
     * @return 通过模型筛选和后端校验的用户消息
     */
    List<ChatHistoryService.StreamMessage> filterUserProfileMessages(
            List<ChatHistoryService.StreamMessage> messages);

    /**
     * 根据第一阶段候选消息和已有长期记忆，生成该用户当前的完整画像内容。
     *
     * @param candidateMessages 第一阶段筛选出的用户消息
     * @return 去除空内容和重复内容后的完整用户画像
     */
    List<String> summarizeUserProfile(
            List<ChatHistoryService.StreamMessage> candidateMessages);

    /**
     * 将第二阶段返回的完整画像同步到长期记忆表。
     *
     * <p>画像内容为空时保持已有画像不变，避免模型空结果误删历史数据。</p>
     *
     * @param candidateMessages 本次画像生成使用的候选用户消息
     * @param profileContents 第二阶段模型返回的完整画像内容
     */
    void synchronizeUserProfile(
            List<ChatHistoryService.StreamMessage> candidateMessages,
            List<String> profileContents);

    /**
     * 查询指定微信用户当前保存的长期记忆。
     *
     * @param wechatUserId 微信用户ID
     * @return 按最后更新时间倒序排列的长期记忆
     */
    List<UserMemory> getUserMemories(String wechatUserId);

    /**
     * 组装供聊天模型使用的长期记忆数据上下文。
     *
     * @param wechatUserId 微信用户ID
     * @return 带固定边界的长期记忆上下文
     */
    String buildUserMemoryContext(String wechatUserId);

}
