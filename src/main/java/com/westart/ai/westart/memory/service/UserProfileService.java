package com.westart.ai.westart.memory.service;

import com.westart.ai.westart.memory.dto.MessageDTO;
import java.util.List;

/**
 * 用户画像服务。
 *
 * <p>业务服务只通过该接口使用记忆能力，不直接依赖Redis、数据库或记忆模型。</p>
 */
public interface UserProfileService {

    /**
     * 根据公共过滤后的聊天消息生成并更新用户画像。
     *
     * <p>没有用户消息或模型返回空画像时保持原画像不变。</p>
     *
     * @param messages 公共过滤后的业务消息
     */
    void updateProfile(List<MessageDTO> messages);

    /**
     * 构建供聊天模型使用的用户画像上下文。
     *
     * @param wechatUserId 微信用户ID
     * @return 带固定边界的用户画像上下文
     */
    String buildProfileContext(String wechatUserId);

}
