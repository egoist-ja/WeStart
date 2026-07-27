package com.westart.ai.westart.service;

import com.github.wechat.ilink.sdk.ILinkClient;

/**
 * 语音生成接口
 */
public interface VoiceGenerateService {

    /**
     * 根据文本生成语音，并将完整语音文件发送给指定微信用户。
     *
     * @param client 当前消息所属的iLink客户端
     * @param userId 微信用户ID
     * @param content 待合成的文本内容
     */
    void generateAndSendVoice(ILinkClient client, String userId, String content);
}
