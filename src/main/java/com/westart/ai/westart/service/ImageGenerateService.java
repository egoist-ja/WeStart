package com.westart.ai.westart.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import dev.langchain4j.data.message.Content;

import java.util.List;

/**
 * 图片生成接口
 */
public interface ImageGenerateService {

    /**
     * 根据消息内容生成图片，并将生成结果发送给指定微信用户。
     *
     * @param client 当前消息所属的iLink客户端
     * @param sessionId iLink客户端会话ID
     * @param userId 微信用户ID
     * @param contents 当前图片任务引用的原始消息内容
     * @param context 路由模型补充的图片生成描述
     */
    void generateAndSendImages(
            ILinkClient client,
            String sessionId,
            String userId,
            List<Content> contents,
            String context);
}
