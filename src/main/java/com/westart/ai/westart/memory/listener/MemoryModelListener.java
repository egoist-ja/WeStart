package com.westart.ai.westart.memory.listener;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MemoryModelListener implements ChatModelListener {

    private static final int MAX_LOG_LENGTH = 2000;

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        AiMessage aiMessage = responseContext.chatResponse().aiMessage();

        log.debug(
                "记忆模型响应，modelName={}，thinking={}，response={}",
                responseContext.chatResponse().modelName(),
                truncate(aiMessage.thinking()),
                truncate(aiMessage.text()));
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        log.error(
                "记忆模型调用失败，modelName={}，reason={}",
                errorContext.chatRequest().modelName(),
                errorContext.error().getMessage(),
                errorContext.error());
    }

    private String truncate(String content) {
        if (StringUtils.length(content) <= MAX_LOG_LENGTH) {
            return content;
        }
        return content.substring(0, MAX_LOG_LENGTH) + "...";
    }
}