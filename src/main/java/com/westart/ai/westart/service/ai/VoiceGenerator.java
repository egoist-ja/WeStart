package com.westart.ai.westart.service.ai;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

import java.util.List;

/**
 * 根据模型回答生成可直接发送的语音回复。
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
    chatModel = "voiceGenerateModel")
public interface VoiceGenerator {

    /**
     * 理解用户消息并生成语音回复。
     *
     * @param contents 当前用户消息批次
     * @return 语音生成结果
     */
    ChatResponse generateVoice(List<Content> contents);

}
