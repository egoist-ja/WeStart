package com.westart.ai.westart.memory.dto;

/**
 * 用户最近主题记忆查询请求。
 *
 * @param wechatUserId 微信用户ID
 * @param limit 最大返回数量
 */
public record UserTopicMemoryQueryRequest(
        String wechatUserId,
        int limit) {

    private static final int MAX_LIMIT = 10;

    public UserTopicMemoryQueryRequest {
        if (wechatUserId == null || wechatUserId.isBlank()) {
            throw new IllegalArgumentException("微信用户ID不能为空");
        }
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "主题记忆查询数量必须在1到" + MAX_LIMIT + "之间");
        }
    }
}
