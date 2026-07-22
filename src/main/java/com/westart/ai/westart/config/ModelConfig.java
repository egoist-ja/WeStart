package com.westart.ai.westart.config;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelConfig {

    private static final String DEFAULT_TTS_VOICE = "Cherry";
    private static final String DEFAULT_TTS_LANGUAGE_TYPE = "Chinese";

    /**
     * 文本、图片微信助手
     * @return
     */
    @Bean
    public OpenAiChatModel textAssistantModel(){
        return OpenAiChatModel.builder()
                .apiKey(System.getenv("QWEN_API_KEY"))
                .baseUrl("https://"+System.getenv("WORKSPACE_ID")+".cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen3.7-plus")
                .build();
    }

    /**
     * 图片生成模型
     * @return
     */
    @Bean
    public QwenChatModel imageGenerateModel(){
        return QwenChatModel.builder()
                .apiKey(System.getenv("QWEN_API_KEY"))
                .baseUrl("https://"+System.getenv("WORKSPACE_ID")+".cn-beijing.maas.aliyuncs.com/api/v1")
                .modelName("qwen-image-2.0")
                .build();
    }

    /**
     * 消息路由模型
     * @return
     */
    @Bean
    public OpenAiChatModel routeModel(){
        return OpenAiChatModel.builder()
                .apiKey(System.getenv("QWEN_API_KEY"))
                .baseUrl("https://"+System.getenv("WORKSPACE_ID")+".cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen3.7-plus")
                .logResponses(true)
                .logRequests(true)
                .build();
    }

    /**
     * 语音合成模型
     * @return
     */
    @Bean
    public QwenChatModel voiceGenerateModel(){
        QwenChatRequestParameters.TtsOptions ttsOptions =
                QwenChatRequestParameters.TtsOptions.builder()
                        .voice(DEFAULT_TTS_VOICE)
                        .languageType(DEFAULT_TTS_LANGUAGE_TYPE)
                        .build();
        return QwenChatModel.builder()
                .apiKey(System.getenv("QWEN_API_KEY"))
                .baseUrl("https://"+System.getenv("WORKSPACE_ID")+".cn-beijing.maas.aliyuncs.com/api/v1")
                .modelName("qwen3-tts-flash")
                .defaultRequestParameters(QwenChatRequestParameters.builder()
                        .ttsOptions(ttsOptions)
                        .build())
                .build();
    }
}
