package com.westart.ai.westart.media.service.ai;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

import java.util.List;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "imageGenerateModel"
)
public interface ImageGenerator {

    /**
     * 根据用户的图片生成描述生成图片。
     *
     * @param context 图片生成描述
     * @return 图片生成模型返回的图片列表
     */
    List<Image> generateImage(@UserMessage String context);
}
