package com.westart.ai.westart.adapter;

import com.westart.ai.westart.DTO.MessageDTO;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将主题记忆业务消息适配为LangChain4j文本片段。
 *
 * <p>本适配器只负责对象格式转换，不负责消息过滤、模型调用或数据持久化。</p>
 */
@Component
public class TopicMemoryMessageAdapter {

    public static final String MESSAGE_ID_METADATA_KEY = "message_id";
    public static final String WECHAT_USER_ID_METADATA_KEY = "wechat_user_id";
    public static final String ROLE_METADATA_KEY = "role";
    public static final String CREATED_AT_METADATA_KEY = "created_at";

    /**
     * 按原始顺序批量转换业务消息。
     *
     * @param messages 待转换的业务消息
     * @return 与输入顺序一致的文本片段
     */
    public List<TextSegment> adaptAll(List<MessageDTO> messages) {
        if (messages == null) {
            throw new IllegalArgumentException("业务消息列表不能为空");
        }
        return messages.stream()
                .map(this::adaptOne)
                .toList();
    }

    /**
     * 将一条业务消息转换为LangChain4j文本片段。
     *
     * @param message 待转换的业务消息
     * @return 包含业务元数据的文本片段
     */
    private TextSegment adaptOne(MessageDTO message) {
        if (message == null) {
            throw new IllegalArgumentException("业务消息不能为空");
        }
        Metadata metadata = new Metadata()
                .put(MESSAGE_ID_METADATA_KEY, message.messageId())
                .put(WECHAT_USER_ID_METADATA_KEY, message.wechatUserId())
                .put(ROLE_METADATA_KEY, message.role())
                .put(CREATED_AT_METADATA_KEY, message.createdAt().toString());
        return TextSegment.from(message.content(), metadata);
    }
}
