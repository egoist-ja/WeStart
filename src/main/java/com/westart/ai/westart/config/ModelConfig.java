package com.westart.ai.westart.config;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenChatRequestParameters;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;


@Configuration
public class ModelConfig {

    private static final String DEFAULT_TTS_VOICE = "Cherry";
    private static final String DEFAULT_TTS_LANGUAGE_TYPE = "Chinese";

    /**
     * 文本、图片微信助手
     * @return
     */
    @Bean
    public OpenAiChatModel weChatAssistantModel(){
        return OpenAiChatModel.builder()
                .apiKey(System.getenv("QWEN_API_KEY"))
                .baseUrl("https://"+System.getenv("WORKSPACE_ID")+".cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen3.7-plus")
                .timeout(Duration.ofSeconds(120))
                .listeners(List.of())
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

    /**
     * 语音识别模型
     * @return
     */
    @Bean
    public QwenChatModel audioDetectModel(){
        QwenChatRequestParameters.AsrOptions asrOptions =
                QwenChatRequestParameters.AsrOptions.builder()
                        .language("zh")
                        .enableItn(true)
                        .build();
        return QwenChatModel.builder()
                .apiKey(System.getenv("QWEN_API_KEY"))
                .baseUrl("https://"+System.getenv("WORKSPACE_ID")+".cn-beijing.maas.aliyuncs.com/api/v1")
                .modelName("qwen3-asr-flash")
                .defaultRequestParameters(QwenChatRequestParameters.builder()
                        .asrOptions(asrOptions)
                        .build())
                .build();
    }

    /**
     * 配置向量模型
     * @return
     */
    @Bean
    public QwenEmbeddingModel embeddingModel(){
        return QwenEmbeddingModel.builder()
                .apiKey(System.getenv("QWEN_API_KEY"))
                .baseUrl("https://"+System.getenv("WORKSPACE_ID")+".cn-beijing.maas.aliyuncs.com/api/v1")
                .modelName("text-embedding-v4")
                .dimension(1024)
                .build();
    }
}
