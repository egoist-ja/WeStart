package com.westart.ai.westart.service.ai;

import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
    chatModel = "audioDetectModel")
public interface AudioDetector {

    String detectAudio(@UserMessage AudioContent audioContent);
}
