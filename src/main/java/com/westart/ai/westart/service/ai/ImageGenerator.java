package com.westart.ai.westart.service.ai;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

import java.util.List;

@AiService(
    wiringMode= AiServiceWiringMode.EXPLICIT,
    chatModel = "imageGenerateModel"
)
public interface ImageGenerator {


    /**
     * 根据用户的图片生成描述和可选参考图片生成图片。
     *
     * @param contents 图片生成描述和参考图片
     * @return 图片生成模型返回的图片列表
     */
    List<Image> generateImage(@UserMessage List<Content> contents);
}
