package com.westart.ai.westart.DTO;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.model.output.structured.Description;

/**
 * 消息内容，包含Content对象和在批次中的索引
 */
public record MessageContent(
    @Description("消息索引") int index,
    @Description("消息内容") Content content
) { }
