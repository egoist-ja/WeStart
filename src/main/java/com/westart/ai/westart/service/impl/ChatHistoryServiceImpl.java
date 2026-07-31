package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.westart.ai.westart.entity.ChatMessage;
import com.westart.ai.westart.repository.ChatMessageMapperImpl;
import com.westart.ai.westart.service.ChatHistoryService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 聊天历史消息服务实现，负责Redis Stream消息的写入、批量读取和消费确认。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryServiceImpl implements ChatHistoryService {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_AI = "AI";
    private static final String HISTORY_CONSUMER_NAME = "westart-memory-consumer";
    private static final String IMAGE_MESSAGE_PLACEHOLDER = "[图片消息]";
    private static final String VIDEO_MESSAGE_PLACEHOLDER = "[视频消息]";
    private static final String UNSUPPORTED_MESSAGE_PLACEHOLDER = "[不支持的微信消息]";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(5L);

    private final RedisTemplate<String, String> redisTemplate;
    private final ChatMessageMapperImpl chatMessageMapper;
    @Value("${westart.memory.history-stream-key}")
    private String historyStreamKey;
    @Value("${westart.memory.history-consumer-group}")
    private String historyConsumerGroup;
    @Value("${westart.memory.history-batch-size}")
    private int historyBatchSize;
    @Value("${westart.memory.history-pending-timeout}")
    private Duration historyPendingTimeout;
    @Value("${westart.memory.history-max-delivery-attempts}")
    private int historyMaxDeliveryAttempts;
    @Value("${westart.memory.history-dead-letter-stream-key}")
    private String historyDeadLetterStreamKey;

    /**
     * 将微信用户原始消息写入聊天历史Stream。
     *
     * @param memoryId 稳定聊天记忆ID
     * @param message 微信原始消息
     */
    @Override
    public void publishUserMessage(String memoryId, WeixinMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("微信原始消息不能为空");
        }
        if (!memoryId.equals(message.getFrom_user_id())) {
            throw new IllegalArgumentException("微信消息用户与memoryId不一致");
        }

        String messageId = resolveUserMessageId(message);
        Instant createdAt = resolveCreatedAt(message);
        publishToUserStream(memoryId, messageId, ROLE_USER, resolveUserContent(message), createdAt);
    }

    /**
     * 将AI最终文本回答写入聊天历史Stream。
     *
     * @param memoryId 稳定聊天记忆ID
     * @param content AI最终文本回答
     */
    @Override
    public void publishAiMessage(String memoryId, String content) {
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("AI回答内容不能为空");
        }
        publishToUserStream(memoryId,
                UUID.randomUUID().toString(),
                ROLE_AI,
                content,
                Instant.now());
    }

    /**
     * 将Stream消息转换为聊天历史实体，并在同一事务中批量写入数据库。
     * message_id重复时保留数据库中的原记录，不重复插入。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int saveMessageBatch(List<StreamMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        List<ChatMessage> chatMessages = messages.stream()
                .map(this::toChatMessage)
                .toList();
        int affectedRows = chatMessageMapper.insertBatchIgnoreDuplicates(chatMessages);

        log.info(
                "聊天历史批量写入MySQL完成，messageCount={}，affectedRows={}",
                chatMessages.size(),
                affectedRows);
        return affectedRows;
    }

    /**
     * 批量标记已经完成长期记忆处理的聊天消息，不修改原始消息内容。
     */
    @Override
    public int markMessagesMemoryProcessed(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        List<String> normalizedMessageIds = messageIds.stream()
                .filter(messageId -> !StringUtils.isBlank(messageId))
                .distinct()
                .toList();
        if (normalizedMessageIds.isEmpty()) {
            return 0;
        }

        int affectedRows = chatMessageMapper.markMemoryProcessed(normalizedMessageIds);
        log.info(
                "聊天消息记忆处理状态批量更新完成，messageCount={}，affectedRows={}",
                normalizedMessageIds.size(),
                affectedRows);
        return affectedRows;
    }

    /**
     * 查询指定消息中尚未完成长期记忆处理的消息ID。
     */
    @Override
    public List<String> findUnprocessedMessageIds(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> normalizedMessageIds = messageIds.stream()
                .filter(messageId -> !StringUtils.isBlank(messageId))
                .distinct()
                .toList();
        if (normalizedMessageIds.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(chatMessageMapper.selectUnprocessedMessageIds(normalizedMessageIds));
    }

    // ========== Per-User Stream 操作 ==========

    /**
     * 使用消费者组阻塞读取指定用户的一批新消息。
     *
     * <p>当Stream中没有新消息时，线程阻塞等待直到有新消息到达或超时。
     * 超时返回空列表，由调用方决定是否重试Pending消息后继续阻塞。</p>
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<StreamMessage> readUserMessageBatch(String userId) {
        String streamKey = userStreamKey(userId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(streamKey))) {
            return Collections.emptyList();
        }
        ensureUserConsumerGroup(userId);

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(userConsumerGroup(userId), HISTORY_CONSUMER_NAME),
                StreamReadOptions.empty().count(historyBatchSize).block(BLOCK_TIMEOUT),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }

        List<StreamMessage> messages = records.stream()
                .map(this::toStreamMessage)
                .toList();
        log.info("用户Stream阻塞读取成功，userId={}，messageCount={}", userId, messages.size());
        return messages;
    }

    /**
     * 重新领取指定用户超时Pending消息，达到最大投递次数时转入死信Stream。
     */
    @Override
    public List<StreamMessage> readUserRetryMessageBatch(String userId) {
        String streamKey = userStreamKey(userId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(streamKey))) {
            return Collections.emptyList();
        }
        validatePendingConfig();
        ensureUserConsumerGroup(userId);

        String consumerGroup = userConsumerGroup(userId);
        PendingMessages pendingMessages = redisTemplate.opsForStream().pending(
                streamKey,
                consumerGroup,
                Range.unbounded(),
                historyBatchSize,
                historyPendingTimeout);
        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return Collections.emptyList();
        }

        List<PendingMessage> retryableMessages = pendingMessages.stream()
                .filter(message -> message.getTotalDeliveryCount() < historyMaxDeliveryAttempts)
                .toList();
        List<PendingMessage> exhaustedMessages = pendingMessages.stream()
                .filter(message -> message.getTotalDeliveryCount() >= historyMaxDeliveryAttempts)
                .toList();
        moveToUserDeadLetterStream(userId, exhaustedMessages);

        if (retryableMessages.isEmpty()) {
            return Collections.emptyList();
        }
        RecordId[] retryRecordIds = retryableMessages.stream()
                .map(PendingMessage::getId)
                .toArray(RecordId[]::new);
        List<MapRecord<String, Object, Object>> claimedRecords =
                redisTemplate.opsForStream().claim(
                        streamKey,
                        consumerGroup,
                        HISTORY_CONSUMER_NAME,
                        historyPendingTimeout,
                        retryRecordIds);
        if (claimedRecords == null || claimedRecords.isEmpty()) {
            return Collections.emptyList();
        }

        List<StreamMessage> messages = claimedRecords.stream()
                .map(this::toStreamMessage)
                .toList();
        log.info("用户Pending消息重新领取完成，userId={}，messageCount={}", userId, messages.size());
        return messages;
    }

    /**
     * 确认指定用户已经完成处理的Stream消息。
     */
    @Override
    public void acknowledgeUserMessages(String userId, List<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return;
        }
        String[] ids = recordIds.stream()
                .filter(id -> !StringUtils.isBlank(id))
                .distinct()
                .toArray(String[]::new);
        if (ids.length == 0) {
            return;
        }

        String streamKey = userStreamKey(userId);
        Long acknowledgedCount = redisTemplate.opsForStream().acknowledge(
                streamKey,
                userConsumerGroup(userId),
                ids);
        log.info("确认用户Stream消息完成，userId={}，requestedCount={}，acknowledgedCount={}",
                userId, ids.length, acknowledgedCount == null ? 0L : acknowledgedCount);
    }

    // ========== Per-User Stream 私有方法 ==========

    /**
     * 写入一条消息到指定用户的Redis Stream。
     */
    private void publishToUserStream(String userId, String messageId, String role,
            String content, Instant createdAt) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("messageId", messageId);
        fields.put("memoryId", userId);
        fields.put("role", role);
        fields.put("content", content);
        fields.put("createdAt", createdAt.toString());

        String streamKey = userStreamKey(userId);
        try {
            RecordId recordId = redisTemplate.opsForStream().add(streamKey, fields);
            if (recordId == null) {
                throw new IllegalStateException("Redis未返回Stream Record ID");
            }
            log.info("消息写入用户Stream成功，userId={}，recordId={}，role={}",
                    userId, recordId.getValue(), role);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "消息写入用户Stream失败，userId=" + userId + "，role=" + role, exception);
        }
    }

    /**
     * 生成指定用户的Redis Stream Key。
     */
    private String userStreamKey(String userId) {
        return historyStreamKey + ":" + userId;
    }

    /**
     * 生成指定用户的消费者组名称。
     */
    private String userConsumerGroup(String userId) {
        return historyConsumerGroup + ":" + userId;
    }

    /**
     * 生成指定用户的死信Stream Key。
     */
    private String deadLetterStreamKey(String userId) {
        return historyDeadLetterStreamKey + ":" + userId;
    }

    /**
     * 校验Pending消息配置。
     */
    private void validatePendingConfig() {
        if (historyPendingTimeout == null
                || historyPendingTimeout.isNegative()
                || historyPendingTimeout.isZero()) {
            throw new IllegalStateException("Pending消息超时时间必须大于0");
        }
        if (historyMaxDeliveryAttempts <= 0) {
            throw new IllegalStateException("Pending消息最大投递次数必须大于0");
        }
    }

    /**
     * 确保指定用户的消费者组存在，不存在时自动创建。
     */
    private void ensureUserConsumerGroup(String userId) {
        String streamKey = userStreamKey(userId);
        String consumerGroup = userConsumerGroup(userId);
        try {
            boolean groupExists = redisTemplate.opsForStream()
                    .groups(streamKey)
                    .stream()
                    .anyMatch(group -> consumerGroup.equals(group.groupName()));
            if (groupExists) {
                return;
            }
            redisTemplate.opsForStream().createGroup(
                    streamKey, ReadOffset.from("0-0"), consumerGroup);
            log.info("用户消费者组创建成功，userId={}，streamKey={}，consumerGroup={}",
                    userId, streamKey, consumerGroup);
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "创建用户消费者组失败，userId=" + userId + "，streamKey=" + streamKey, exception);
        }
    }

    /**
     * 将指定用户超过最大投递次数的消息写入死信Stream。
     */
    private void moveToUserDeadLetterStream(String userId, List<PendingMessage> pendingMessages) {
        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return;
        }
        String streamKey = userStreamKey(userId);
        String dlqKey = deadLetterStreamKey(userId);
        for (PendingMessage pendingMessage : pendingMessages) {
            String recordId = pendingMessage.getIdAsString();
            List<MapRecord<String, Object, Object>> originalRecords =
                    redisTemplate.opsForStream().range(streamKey, Range.just(recordId));
            if (originalRecords == null || originalRecords.isEmpty()) {
                throw new IllegalStateException("无法读取待转移的Pending消息，recordId=" + recordId);
            }

            Map<String, String> deadLetterFields = originalRecords.getFirst()
                    .getValue()
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .collect(Collectors.toMap(
                            entry -> entry.getKey().toString(),
                            entry -> entry.getValue().toString(),
                            (left, right) -> left,
                            LinkedHashMap::new));
            deadLetterFields.put("originalRecordId", recordId);
            deadLetterFields.put("deliveryCount",
                    Long.toString(pendingMessage.getTotalDeliveryCount()));
            deadLetterFields.put("failedAt", Instant.now().toString());
            deadLetterFields.put("failureReason", "PROCESSING_RETRY_EXHAUSTED");

            RecordId deadLetterRecordId = redisTemplate.opsForStream().add(dlqKey, deadLetterFields);
            if (deadLetterRecordId == null) {
                throw new IllegalStateException("死信Stream未返回Record ID");
            }
            acknowledgeUserMessages(userId, List.of(recordId));
            log.warn("用户消息超过最大投递次数，已转入死信Stream，userId={}，deliveryCount={}",
                    userId, pendingMessage.getTotalDeliveryCount());
        }
    }

    /**
     * 将Redis原始记录转换为后续记忆分析所需的消息对象。
     */
    private StreamMessage toStreamMessage(MapRecord<String, Object, Object> record) {
        String recordId = record.getId().getValue();
        Map<Object, Object> fields = record.getValue();
        try {
            return new StreamMessage(
                    recordId,
                    requireField(fields, "messageId", recordId),
                    requireField(fields, "memoryId", recordId),
                    requireField(fields, "role", recordId),
                    requireField(fields, "content", recordId),
                    Instant.parse(requireField(fields, "createdAt", recordId)));
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException(
                    "Redis Stream消息创建时间格式错误，recordId=" + recordId,
                    exception);
        }
    }

    /**
     * 将Redis Stream消息转换为chat_message表实体。
     */
    private ChatMessage toChatMessage(StreamMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Stream消息不能为空");
        }
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessageId(message.messageId());
        chatMessage.setWechatUserId(message.memoryId());
        chatMessage.setRole(message.role());
        chatMessage.setContent(message.content());
        chatMessage.setCreatedAt(message.createdAt());
        chatMessage.setMemoryProcessed(false);
        return chatMessage;
    }

    /**
     * 读取Stream必填字段，字段缺失时保留Pending状态，交由后续重试和死信流程处理。
     */
    private String requireField(Map<Object, Object> fields, String fieldName, String recordId) {
        Object value = fields.get(fieldName);
        if (value == null || StringUtils.isBlank(value.toString())) {
            throw new IllegalStateException(
                    "Redis Stream消息缺少必填字段，recordId=" + recordId + "，field=" + fieldName);
        }
        return value.toString();
    }

    /**
     * 优先使用微信消息ID，微信未提供时生成内部消息ID。
     */
    private String resolveUserMessageId(WeixinMessage message) {
        if (message.getMessage_id() != null) {
            return message.getMessage_id().toString();
        }
        String messageId = UUID.randomUUID().toString();
        log.warn("微信消息未提供messageId，已生成内部消息ID，messageId={}", messageId);
        return messageId;
    }

    /**
     * 优先使用微信消息时间，微信未提供时使用当前时间。
     */
    private Instant resolveCreatedAt(WeixinMessage message) {
        Long createTimeMillis = message.getCreate_time_ms();
        if (createTimeMillis != null && createTimeMillis > 0L) {
            return Instant.ofEpochMilli(createTimeMillis);
        }
        log.warn("微信消息未提供有效创建时间，messageId={}", message.getMessage_id());
        return Instant.now();
    }

    /**
     * 将微信消息转换为适合保存到聊天历史的文本。
     * 图片和视频只保存占位描述，不保存Base64或二进制内容。
     */
    private String resolveUserContent(WeixinMessage message) {
        List<MessageItem> itemList = message.getItem_list();
        if (itemList == null || itemList.isEmpty()) {
            return UNSUPPORTED_MESSAGE_PLACEHOLDER;
        }

        StringJoiner contentJoiner = new StringJoiner(System.lineSeparator());
        for (MessageItem item : itemList) {
            if (item == null) {
                continue;
            }
            if (item.getText_item() != null
                    && !StringUtils.isBlank(item.getText_item().getText())) {
                contentJoiner.add(item.getText_item().getText().trim());
                continue;
            }
            if (item.getImage_item() != null) {
                contentJoiner.add(IMAGE_MESSAGE_PLACEHOLDER);
                continue;
            }
            if (item.getVideo_item() != null) {
                contentJoiner.add(VIDEO_MESSAGE_PLACEHOLDER);
            }
        }

        String content = contentJoiner.toString();
        return StringUtils.isBlank(content) ? UNSUPPORTED_MESSAGE_PLACEHOLDER : content;
    }
}
