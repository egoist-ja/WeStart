package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.westart.ai.westart.DTO.MessageContent;
import com.westart.ai.westart.DTO.SegmentResult;
import com.westart.ai.westart.DTO.batch.SegmentResultBatch;
import com.westart.ai.westart.service.WeChatAgentService;
import com.westart.ai.westart.service.ImageStoreService;
import com.westart.ai.westart.service.ai.ImageGenerator;
import com.westart.ai.westart.service.ai.VoiceGenerator;
import com.westart.ai.westart.service.ai.WeChatAssistant;
import com.westart.ai.westart.service.ai.WeChatMessageRouter;
import com.westart.ai.westart.service.domain.RouteType;
import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class WeChatAgentServiceImpl implements WeChatAgentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WeChatAgentServiceImpl.class);
    private static final String DEFAULT_IMAGE_PROMPT = "请分析用户发送的图片并给出有帮助的回答。";
    private static final String DEFAULT_IMAGE_CLARIFICATION =
            "请补充希望生成的图片主体、场景或具体用途后，再发送完整的图片生成请求。";
    private static final String MODEL_FAILURE_REPLY = "消息处理失败，请稍后重试。";
    private static final String GENERATED_AUDIOS_ATTRIBUTE = "generated_audios";
    private static final String GENERATED_VOICE_FILE_NAME = "assistant-reply.wav";
    private static final int GENERATED_VOICE_SAMPLE_RATE = 24_000;
    private static final int GENERATED_VOICE_BITS_PER_SAMPLE = 16;
    private static final int GENERATED_VOICE_CHANNEL_COUNT = 1;
    private static final long MESSAGE_BATCH_GAP_MILLIS = 8_000L;
    private static final long COLLECTION_IDLE_TIMEOUT_NANOS =
            TimeUnit.MILLISECONDS.toNanos(MESSAGE_BATCH_GAP_MILLIS);
    private static final int GLOBAL_QUEUE_CAPACITY = 1_024; //全局队列总大小
    private static final int USER_QUEUE_CAPACITY = 256; //用户队列大小

    private final ILinkClient iLinkClient;
    private final WeChatAssistant wechatAssistant; //微信助手
    private final ImageGenerator imageGenerator; //图片生成
    private final VoiceGenerator voiceGenerator; //语音合成
    private final WeChatMessageRouter weChatMessageRouter;
    private final OkHttpClient okHttpClient;
    private final ExecutorService wechatUserMessageExecutor;
    private final ImageStoreService imageStoreService;
    private final BlockingQueue<IncomingMessage> globalMessageQueue =
            new ArrayBlockingQueue<>(GLOBAL_QUEUE_CAPACITY); //全局消息队列
    private final ConcurrentHashMap<String, BlockingQueue<IncomingMessage>> userMessageQueues =
            new ConcurrentHashMap<>(); //用户消息队列

    /**
     * 启动全局微信消息分发任务。
     */
    @PostConstruct
    public void startMessageDispatcher() {
        try {
            Thread.ofVirtual().name("wechat-message-dispatcher").start(this::dispatchMessages);
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException("微信消息分发任务启动失败", exception);
        }
    }

    /**
     * 发起微信扫码登录。登录成功后，由iLink SDK负责消息轮询。
     *
     * @return 用于生成登录二维码的内容
     */
    @Override
    public String userLogin() {
        if (iLinkClient.isLoggedIn()) {
            throw new IllegalStateException("微信机器人已登录，请勿重复扫码");
        }

        String qrCodeContent = iLinkClient.executeLogin();
        iLinkClient.getLoginFuture().whenComplete((loginContext, throwable) -> {
            if (throwable != null) {
                LOGGER.error("微信机器人扫码登录失败", throwable);
                return;
            }
            LOGGER.info("微信机器人登录成功，iLink SDK开始轮询消息");
        });
        return qrCodeContent;
    }

    /**
     * 向指定微信用户发送文本消息。
     *
     * @param userId 微信用户ID
     * @param content 消息内容
     */
    @Override
    public void sendMessage(String userId, String content) {
        validateMessage(userId, content);
        if (!iLinkClient.isLoggedIn()) {
            throw new IllegalStateException("微信机器人尚未登录");
        }

        try {
            iLinkClient.sendText(userId, content);
            LOGGER.info("微信消息发送成功，userId={}", userId);
        } catch (IOException | ILinkException exception) {
            throw new IllegalStateException("微信消息发送失败，userId=" + userId, exception);
        }
    }

    /**
     * 接收iLink SDK轮询到的消息，过滤无效消息并立即放入防抖队列。
     *
     * @param messages iLink拉取到的消息集合
     */
    public void handleMessages(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (WeixinMessage message : messages) {
            if (message == null || isBlank(message.getFrom_user_id())) {
                continue;
            }
            enqueueIncomingMessage(message);
        }
    }

    /**
     * 将原始微信消息放入全局有界队列。
     *
     * @param message iLink原始微信消息
     */
    private void enqueueIncomingMessage(WeixinMessage message) {
        String userId = message.getFrom_user_id();
        try {
            globalMessageQueue.put(new IncomingMessage(
                            message,
                            resolveSentAtMillis(message),
                            System.nanoTime()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("微信消息入队被中断，userId={}", userId, exception);
        }
    }

    /**
     * 持续读取全局队列，并按用户ID将消息分发到独立队列。
     */
    private void dispatchMessages() {
        LOGGER.info("微信消息分发任务已启动");
        try {
            while (!Thread.currentThread().isInterrupted()) {
                IncomingMessage incomingMessage = globalMessageQueue.take();
                String userId = incomingMessage.message().getFrom_user_id();
                //判断对应的userId是否有消息队列存在，没有则创建
                BlockingQueue<IncomingMessage> userQueue = userMessageQueues.computeIfAbsent(
                        userId, this::createUserMessageQueue);
                userQueue.put(incomingMessage);
                startTyping(userId);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            LOGGER.info("微信消息分发任务已停止");
        }
    }

    /**
     * 为首次出现的用户创建消息队列，并启动对应的虚拟线程任务。
     *
     * @param userId 微信用户ID
     * @return 该用户的独立消息队列
     */
    private BlockingQueue<IncomingMessage> createUserMessageQueue(String userId) {
        BlockingQueue<IncomingMessage> userQueue =
                new ArrayBlockingQueue<>(USER_QUEUE_CAPACITY);
        try {
            wechatUserMessageExecutor.execute(() -> processUserMessageLoop(userId, userQueue));
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException("用户消息处理任务启动失败，userId=" + userId, exception);
        }
        LOGGER.info("微信用户消息队列已创建，userId={}", userId);
        return userQueue;
    }

    /**
     * 处理指定用户的独立消息队列，并按发送时间进行分批。
     * 核心方法
     * @param userId 微信用户ID
     * @param userQueue 用户独立消息队列
     */
    private void processUserMessageLoop(
            String userId,
            BlockingQueue<IncomingMessage> userQueue) {
        LOGGER.info("微信用户消息处理任务已启动，userId={}", userId);
        IncomingMessage deferredMessage = null; //表示下一批次的消息，用于比较两条消息的间隔时间
        try {
            while (!Thread.currentThread().isInterrupted()) {
                IncomingMessage firstMessage;
                if (deferredMessage == null) {
                    firstMessage = userQueue.take();
                } else {
                    firstMessage = deferredMessage; //使用上一批次缓存的消息作为本批次的第一条消息
                    deferredMessage = null;
                }

                List<IncomingMessage> batchMessages = new ArrayList<>();
                batchMessages.add(firstMessage);
                long lastSentAtMillis = firstMessage.sentAtMillis(); //用户最后一条消息的实际发送时间
                long lastReceivedAtNanos = firstMessage.receivedAtNanos(); //程序收到该批次最后一条消息的时间

                while (!Thread.currentThread().isInterrupted()) {
                    long elapsedNanos = System.nanoTime() - lastReceivedAtNanos; //计算消息在被poll前消耗的时间
                    long remainingNanos = COLLECTION_IDLE_TIMEOUT_NANOS - elapsedNanos; //计算队列的等待时间
                    IncomingMessage nextMessage = userQueue.poll(
                            Math.max(remainingNanos, 0L), TimeUnit.NANOSECONDS);
                    if (nextMessage == null) {
                        break;
                    }
                    if (nextMessage.sentAtMillis() - lastSentAtMillis > MESSAGE_BATCH_GAP_MILLIS) {
                        deferredMessage = nextMessage; //作为下一批的消息
                        break;
                    }
                    batchMessages.add(nextMessage);
                    lastSentAtMillis = Math.max(lastSentAtMillis, nextMessage.sentAtMillis());
                    lastReceivedAtNanos = nextMessage.receivedAtNanos();
                }

                LOGGER.info(
                        "微信消息批次收集完成，userId={}，messageCount={}",
                        userId,
                        batchMessages.size());
                processMessageBatch(userId, userQueue, batchMessages, deferredMessage != null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            LOGGER.info("微信用户消息处理任务已停止，userId={}", userId);
        }
    }

    /**
     * 调用多模态模型处理完整消息批次，并保证单批次异常不会终止后续消息处理。
     *
     * @param userId 微信用户ID
     * @param userQueue 用户独立消息队列
     * @param batchMessages 完成防抖的原始微信消息
     * @param hasDeferredMessage 是否已有待处理的下一批消息
     */
    private void processMessageBatch(
            String userId,
            BlockingQueue<IncomingMessage> userQueue,
            List<IncomingMessage> batchMessages,
            boolean hasDeferredMessage) {
        try {
            boolean containsVoice = containsVoiceMessage(batchMessages);
            List<MessageContent> messageContents = buildBatchContents(batchMessages);
            if (messageContents.isEmpty()) {
                if (containsVoice) {
                    sendMessage(userId,"语音消息未包含可用的转写内容呢,再试一遍吧？");
                }
                LOGGER.info("微信消息批次不包含可处理内容，userId={}", userId);
                return;
            }
            List<SegmentResult> segmentResults = messagesClassifier(userId, messageContents);
            Map<Integer, MessageContent> messageIndex = buildMessageIndex(messageContents);
            for (SegmentResult segmentResult : segmentResults) {
                try {
                    if (segmentResult.type() == RouteType.IMAGE
                            && !segmentResult.executable()) {
                        String clarification = isBlank(segmentResult.clarification())
                                ? DEFAULT_IMAGE_CLARIFICATION
                                : segmentResult.clarification();
                        sendMessage(userId, clarification);
                        continue;
                    }
                    processRouteSegment(userId, messageIndex, segmentResult, containsVoice);
                } catch (RuntimeException exception) {
                    LOGGER.error(
                            "微信消息路由片段处理失败，userId={}，routeType={}",
                            userId,
                            segmentResult.type(),
                            exception);
                    sendFailureReply(userId);
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.error("微信消息模型处理失败，userId={}", userId, exception);
            sendFailureReply(userId);
        } finally {
            refreshTypingState(userId, userQueue, hasDeferredMessage);
        }
    }

    /**
     * 判断当前批次是否包含微信语音消息。
     */
    private boolean containsVoiceMessage(List<IncomingMessage> batchMessages) {
        return batchMessages.stream()
                .map(IncomingMessage::message)
                .map(WeixinMessage::getItem_list)
                .filter(itemList -> itemList != null && !itemList.isEmpty())
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .anyMatch(item -> item.getVoice_item() != null);
    }


    /**
     * 根据路由类型将单个语义片段分发给对应模型。
     *
     * @param userId 微信用户ID
     * @param messageIndex 当前批次的消息索引
     * @param segmentResult 语义路由片段
     * @param replyWithVoice 当前批次是否需要使用语音回复
     */
    private void processRouteSegment(
            String userId,
            Map<Integer, MessageContent> messageIndex,
            SegmentResult segmentResult,
            boolean replyWithVoice) {
        List<Content> segmentContents = selectSegmentContents(messageIndex, segmentResult.content());
        switch (segmentResult.type()) {
            case CHAT -> {
                String reply = wechatAssistant.reply(prepareModelContents(segmentContents));
                if (replyWithVoice) {
                    AiMessage voiceMessage = voiceGenerator.generateVoice(reply);
                    Audio audio = extractGeneratedAudio(voiceMessage);
                    sendGeneratedVoice(userId, audio);
                } else {
                    sendMessage(userId, reply);
                }
            }
            case IMAGE -> {
                List<Content> imageContents = prepareImageGenerationContents(
                        segmentContents,
                        segmentResult.context(),
                        userId);
                List<Image> images = imageGenerator.generateImage(imageContents);
                sendGeneratedImages(userId, images);
                // 生成并发送成功后清理暂存图片
                imageStoreService.deleteAll(userId);
            }
            case VIDEO -> LOGGER.info("暂不处理微信视频生成请求，userId={}", userId);
        }
    }

    /**
     * 从LangChain4j模型响应属性中提取生成的第一个音频。
     *
     * @param aiMessage 语音模型返回的消息
     * @return 语音模型生成的音频
     */
    private Audio extractGeneratedAudio(AiMessage aiMessage) {
        if (aiMessage == null) {
            throw new IllegalStateException("语音模型未返回有效响应");
        }
        Object generatedAudios = aiMessage.attributes()
                .get(GENERATED_AUDIOS_ATTRIBUTE);
        if (!(generatedAudios instanceof List<?> audioList) || audioList.isEmpty()) {
            throw new IllegalStateException("语音模型响应中不包含生成音频");
        }
        Object firstAudio = audioList.getFirst();
        if (!(firstAudio instanceof Audio audio)) {
            throw new IllegalStateException("语音模型返回了不支持的音频结果类型");
        }
        return audio;
    }

    /**
     * 将语音模型返回的完整音频作为普通文件发送给微信用户。
     *
     * @param userId 微信用户ID
     * @param audio 语音模型返回的完整音频
     */
    private void sendGeneratedVoice(String userId, Audio audio) {
        byte[] voiceData = prepareGeneratedAudioFile(
                resolveGeneratedAudioBytes(audio), audio.mimeType());
        try {
            iLinkClient.sendFile(
                    userId,
                    voiceData,
                    GENERATED_VOICE_FILE_NAME,
                    null);
            LOGGER.info("微信语音文件发送成功，userId={}，voiceSize={}", userId, voiceData.length);
        } catch (IOException | ILinkException exception) {
            throw new IllegalStateException("微信语音文件发送失败，userId=" + userId, exception);
        }
    }

    /**
     * 获取语音模型返回的音频字节，优先读取二进制和Base64数据，否则下载音频URL。
     *
     * @param audio 语音生成结果
     * @return 完整音频字节
     */
    private byte[] resolveGeneratedAudioBytes(Audio audio) {
        if (audio == null) {
            throw new IllegalArgumentException("语音生成结果不能为空");
        }
        byte[] binaryData = audio.binaryData();
        if (binaryData != null && binaryData.length > 0) {
            return binaryData;
        }
        if (!isBlank(audio.base64Data())) {
            try {
                return Base64.getDecoder().decode(audio.base64Data());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("语音模型返回了无效的Base64数据", exception);
            }
        }
        if (audio.url() == null) {
            throw new IllegalStateException("语音生成结果不包含URL、Base64或二进制数据");
        }

        Request request = new Request.Builder()
                .url(audio.url().toString())
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            return getBytes(response);
        } catch (IOException exception) {
            throw new IllegalStateException("下载生成语音失败，url=" + audio.url(), exception);
        }
    }

    /**
     * 将语音模型返回的数据转换为可直接播放的WAV文件。
     *
     * @param audioData 模型返回的音频数据
     * @param mimeType 音频MIME类型
     * @return 完整WAV文件数据
     */
    private byte[] prepareGeneratedAudioFile(byte[] audioData, String mimeType) {
        if (audioData == null || audioData.length == 0) {
            throw new IllegalArgumentException("生成的语音数据不能为空");
        }
        if (isWaveAudio(audioData, mimeType)) {
            return audioData;
        }
        if (isBlank(mimeType) || "audio/pcm".equalsIgnoreCase(mimeType)) {
            return wrapPcmAsWave(audioData);
        }
        throw new IllegalStateException("不支持的语音格式：" + mimeType);
    }

    private boolean isWaveAudio(byte[] audioData, String mimeType) {
        return "audio/wav".equalsIgnoreCase(mimeType)
                || "audio/wave".equalsIgnoreCase(mimeType)
                || (audioData.length >= 12
                && matchesAt(audioData, 0, 0x52, 0x49, 0x46, 0x46)
                && matchesAt(audioData, 8, 0x57, 0x41, 0x56, 0x45));
    }

    private byte[] wrapPcmAsWave(byte[] pcmData) {
        int waveSize = Math.addExact(44, pcmData.length);
        int byteRate = GENERATED_VOICE_SAMPLE_RATE
                * GENERATED_VOICE_CHANNEL_COUNT
                * GENERATED_VOICE_BITS_PER_SAMPLE / Byte.SIZE;
        short blockAlign = (short) (GENERATED_VOICE_CHANNEL_COUNT
                * GENERATED_VOICE_BITS_PER_SAMPLE / Byte.SIZE);
        ByteBuffer buffer = ByteBuffer.allocate(waveSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'});
        buffer.putInt(waveSize - 8);
        buffer.put(new byte[]{'W', 'A', 'V', 'E'});
        buffer.put(new byte[]{'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) GENERATED_VOICE_CHANNEL_COUNT);
        buffer.putInt(GENERATED_VOICE_SAMPLE_RATE);
        buffer.putInt(byteRate);
        buffer.putShort(blockAlign);
        buffer.putShort((short) GENERATED_VOICE_BITS_PER_SAMPLE);
        buffer.put(new byte[]{'d', 'a', 't', 'a'});
        buffer.putInt(pcmData.length);
        buffer.put(pcmData);
        return buffer.array();
    }

    /**
     * 将图片模型返回的图片发送给微信用户。
     *
     * @param userId 微信用户ID
     * @param images 图片生成结果
     */
    private void sendGeneratedImages(String userId, List<Image> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalStateException("图片生成模型未返回图片");
        }

        for (int index = 0; index < images.size(); index++) {
            byte[] imageBytes = resolveGeneratedImageBytes(images.get(index));
            String fileName = "generated-image-" + (index + 1) + ".png";
            try {
                iLinkClient.sendImage(userId, imageBytes, fileName, null);
                LOGGER.info(
                        "微信生成图片发送成功，userId={}，imageIndex={}，imageSize={}",
                        userId,
                        index,
                        imageBytes.length);
            } catch (IOException | ILinkException exception) {
                throw new IllegalStateException(
                        "微信生成图片发送失败，userId=" + userId + "，imageIndex=" + index,
                        exception);
            }
        }
    }

    /**
     * 获取图片生成结果的原始字节，优先读取Base64数据，否则下载图片URL。
     *
     * @param image 图片生成结果
     * @return 图片原始字节
     */
    private byte[] resolveGeneratedImageBytes(Image image) {
        if (image == null) {
            throw new IllegalArgumentException("图片生成结果不能为空");
        }
        if (!isBlank(image.base64Data())) {
            try {
                return Base64.getDecoder().decode(image.base64Data());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("图片生成模型返回了无效的Base64数据", exception);
            }
        }
        if (image.url() == null) {
            throw new IllegalStateException("图片生成结果不包含URL或Base64数据");
        }

        Request request = new Request.Builder()
                .url(image.url().toString())
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            return getBytes(response);
        } catch (IOException exception) {
            throw new IllegalStateException("下载生成图片失败，url=" + image.url(), exception);
        }
    }

    private static byte @NonNull [] getBytes(Response response) throws IOException {
        if (!response.isSuccessful()) {
            throw new IllegalStateException(
                    "下载生成图片失败，HTTP状态码=" + response.code());
        }
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            throw new IllegalStateException("下载生成图片失败，响应体为空");
        }
        byte[] imageBytes = responseBody.bytes();
        if (imageBytes.length == 0) {
            throw new IllegalStateException("下载生成图片失败，图片内容为空");
        }
        return imageBytes;
    }

    /**
     * 创建原始消息索引，供路由结果按索引选择内容。
     *
     * @param messages 带索引的批次消息
     * @return 以原始消息索引为键的不可变映射
     */
    private Map<Integer, MessageContent> buildMessageIndex(List<MessageContent> messages) {
        Map<Integer, MessageContent> messageIndex = new HashMap<>(messages.size());
        for (MessageContent message : messages) {
            MessageContent previous = messageIndex.put(message.index(), message);
            if (previous != null) {
                throw new IllegalArgumentException("批次中存在重复的消息索引：" + message.index());
            }
        }
        return Map.copyOf(messageIndex);
    }

    /**
     * 按路由模型返回的索引顺序提取原始模型内容。
     *
     * @param messageIndex 当前批次的消息索引
     * @param selectedIndexes 路由片段引用的消息索引
     * @return 当前语义片段对应的不可变内容列表
     */
    private List<Content> selectSegmentContents(
            Map<Integer, MessageContent> messageIndex,
            List<Integer> selectedIndexes) {
        List<Content> contents = new ArrayList<>(selectedIndexes.size());
        for (Integer selectedIndex : selectedIndexes) {
            MessageContent message = messageIndex.get(selectedIndex);
            if (message == null) {
                throw new IllegalArgumentException("路由结果引用了不存在的消息索引：" + selectedIndex);
            }
            contents.add(message.content());
        }
        return List.copyOf(contents);
    }

    /**
     * 组装图片生成模型输入。优先使用路由模型补全的任务描述，并保留用户提供的参考图片。
     *
     * @param segmentContents 当前图片任务引用的原始内容
     * @param context 路由模型补全的图片生成描述
     * @return 可直接交给图片生成模型的不可变内容列表
     */
    private List<Content> prepareImageGenerationContents(
            List<Content> segmentContents,
            String context,
            String userId) {
        List<Content> imageContents = new ArrayList<>(segmentContents.size() + 2);

        if (!isBlank(context)) {
            imageContents.add(TextContent.from(context));
        }

        boolean hasCurrentImage = false;
        for (Content content : segmentContents) {
            if (content instanceof ImageContent) {
                imageContents.add(content);
                hasCurrentImage = true;
            }
        }

        // 跨批次补充：有编辑上下文但当前批次无图片 → 从磁盘加载最近暂存的图片
        if (!isBlank(context) && !hasCurrentImage) {
            Optional<ImageStoreService.ImageData> saved = imageStoreService.readLatestAsBase64(userId);
            if (saved.isPresent()) {
                ImageStoreService.ImageData imageData = saved.get();
                imageContents.add(ImageContent.from(imageData.base64Data(), imageData.mimeType()));
                LOGGER.info("跨批次加载暂存图片，userId={}", userId);
            } else {
                LOGGER.info("未找到暂存图片，userId={}，继续仅使用文本描述生成", userId);
            }
        }

        return List.copyOf(imageContents);
    }

    /**
     * 调用消息路由模型，对当前用户批次进行语义分块和任务分类。
     *
     * @param userId 微信用户ID
     * @param messages 带原始消息索引的批次内容
     * @return 按用户原始表达顺序排列的有效路由片段
     */
    private List<SegmentResult> messagesClassifier(
            String userId,
            List<MessageContent> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<Content> routerContents = new ArrayList<>(messages.size() * 2);
        Set<Integer> validIndexes = new HashSet<>(messages.size());
        for (MessageContent message : messages) {
            if (message == null || message.content() == null) {
                LOGGER.warn("路由输入包含空消息内容，userId={}", userId);
                continue;
            }
            if (message.index() < 0 || !validIndexes.add(message.index())) {
                throw new IllegalArgumentException(
                        "路由输入包含非法或重复的消息索引，userId=" + userId);
            }
            routerContents.add(TextContent.from("[消息索引：" + message.index() + "]"));
            routerContents.add(message.content());
        }
        if (routerContents.isEmpty()) {
            return List.of();
        }

        SegmentResultBatch resultBatch = weChatMessageRouter.route(List.copyOf(routerContents));
        if (resultBatch == null
                || resultBatch.segmentResults() == null
                || resultBatch.segmentResults().isEmpty()) {
            throw new IllegalStateException("消息路由模型未返回有效分类结果，userId=" + userId);
        }

        List<SegmentResult> validSegments = new ArrayList<>(resultBatch.segmentResults().size());
        for (SegmentResult segment : resultBatch.segmentResults()) {
            if (segment == null
                    || segment.type() == null
                    || segment.content() == null
                    || segment.content().isEmpty()) {
                LOGGER.warn("忽略结构不完整的消息路由片段，userId={}", userId);
                continue;
            }

            List<Integer> indexes = segment.content().stream()
                    .filter(validIndexes::contains)
                    .distinct()
                    .toList();
            if (indexes.isEmpty()) {
                LOGGER.warn("忽略未引用有效消息索引的路由片段，userId={}", userId);
                continue;
            }
            if (indexes.size() != segment.content().size()) {
                LOGGER.warn("消息路由片段包含非法或重复索引，已完成过滤，userId={}", userId);
            }

            String context = segment.context() == null ? "" : segment.context().trim();
            String clarification = segment.clarification() == null
                    ? ""
                    : segment.clarification().trim();
            validSegments.add(new SegmentResult(
                    segment.type(),
                    indexes,
                    context,
                    segment.executable(),
                    clarification));
        }
        if (validSegments.isEmpty()) {
            throw new IllegalStateException("消息路由模型返回的分类结果不可用，userId=" + userId);
        }

        LOGGER.info(
                "微信消息分类完成，userId={}，messageCount={}，segmentCount={}",
                userId,
                messages.size(),
                validSegments.size());
        return List.copyOf(validSegments);
    }

    /**
     * 将完整消息批次转换为带原始消息索引的模型内容。
     *
     * @param batchMessages 完成分批的原始微信消息
     * @return 按原始消息顺序排列的索引化多模态内容
     */
    private List<MessageContent> buildBatchContents(List<IncomingMessage> batchMessages) {
        List<MessageContent> contents = new ArrayList<>(batchMessages.size());
        for (int index = 0; index < batchMessages.size(); index++) {
            WeixinMessage message = batchMessages.get(index).message();
            Optional<Content> content = buildUserMessage(message);
            if (content.isPresent()) {
                contents.add(new MessageContent(index, content.get()));
            }
        }
        return List.copyOf(contents);
    }

    /**
     * 读取用户实际发送时间；SDK未提供时使用当前时间降级。
     *
     * @param message iLink原始微信消息
     * @return 用户发送时间，毫秒
     */
    private long resolveSentAtMillis(WeixinMessage message) {
        Long sentAtMillis = message.getCreate_time_ms();
        if (sentAtMillis != null && sentAtMillis > 0L) {
            return sentAtMillis;
        }
        LOGGER.warn("微信消息缺少create_time_ms，使用本地时间降级，messageId={}", message.getMessage_id());
        return System.currentTimeMillis();
    }

    /**
     * 为完整批次补充必要的默认图片提示，并返回不可变内容列表。
     *
     * @param contents 已合并的批次内容
     * @return 可直接交给模型的不可变列表
     */
    private List<Content> prepareModelContents(List<Content> contents) {
        boolean containsText = contents.stream().anyMatch(TextContent.class::isInstance);
        if (containsText) {
            return List.copyOf(contents);
        }

        List<Content> modelContents = new ArrayList<>(contents.size() + 1);
        modelContents.add(TextContent.from(DEFAULT_IMAGE_PROMPT));
        modelContents.addAll(contents);
        return List.copyOf(modelContents);
    }

    /**
     * 根据iLink消息项类型构建单条模型内容。
     *
     * @param message iLink原始微信消息
     * @return 文本、图片或语音转写模型内容；消息无效或类型不受支持时返回空
     */
    private Optional<Content> buildUserMessage(WeixinMessage message) {
        List<MessageItem> itemList = message.getItem_list();
        if (itemList == null || itemList.isEmpty()) {
            LOGGER.warn("微信消息不包含消息项，messageId={}", message.getMessage_id());
            return Optional.empty();
        }

        for (MessageItem item : itemList) {
            if (item == null) {
                continue;
            }
            if (item.getText_item() != null) {
                String text = item.getText_item().getText();
                if (!isBlank(text)) {
                    return Optional.of(TextContent.from(text.trim()));
                }
                continue;
            }
            if (item.getImage_item() != null) {
                String messageId = message.getMessage_id() != null
                        ? message.getMessage_id().toString()
                        : "unknown";
                return buildImageContent(message.getFrom_user_id(), messageId, item);
            }
            if (item.getVoice_item() != null) {
                return buildVoiceContent(message.getFrom_user_id(), item);
            }
            if (item.getVideo_item() != null) {
                LOGGER.info("忽略微信视频消息，userId={}", message.getFrom_user_id());
                return Optional.empty();
            }
        }

        LOGGER.info(
                "忽略不支持的微信消息，userId={}，messageId={}",
                message.getFrom_user_id(),
                message.getMessage_id());
        return Optional.empty();
    }

    /**
     * 将微信语音消息的转写结果转换为文本模型内容。
     *
     * @param userId 微信用户ID
     * @param item 包含语音转写结果的消息项
     * @return 语音转写文本；转写结果为空时返回空
     */
    private Optional<Content> buildVoiceContent(String userId, MessageItem item) {
        String transcription = item.getVoice_item().getText();
        if (isBlank(transcription)) {
            LOGGER.warn("微信语音消息未包含转写文本，userId={}", userId);
            return Optional.empty();
        }

        String normalizedTranscription = transcription.trim();
        LOGGER.info(
                "微信语音转文字成功，userId={}，textLength={}",
                userId,
                normalizedTranscription.length());
        return Optional.of(TextContent.from(normalizedTranscription));
    }

    /**
     * 下载微信图片并转换为模型图片内容。
     *
     * @param userId 微信用户ID
     * @param item 包含图片信息的消息项
     * @return 图片模型内容；下载失败或图片无效时返回空
     */
    private Optional<Content> buildImageContent(String userId, String messageId, MessageItem item) {
        try {
            byte[] imageBytes = iLinkClient.downloadImageFromMessageItem(item);
            if (imageBytes == null || imageBytes.length == 0) {
                LOGGER.warn("收到空的微信图片消息，userId={}", userId);
                return Optional.empty();
            }
            String mimeType = detectImageMimeType(imageBytes);
            String base64Data = Base64.getEncoder().encodeToString(imageBytes);

            // 保存到磁盘，失败不影响当前批次处理
            try {
                imageStoreService.save(userId, imageBytes, mimeType);
            } catch (RuntimeException e) {
                LOGGER.warn("微信图片暂存失败，userId={}，不影响当前批次处理", userId, e);
            }

            LOGGER.info("微信图片下载成功，userId={}，imageSize={}", userId, imageBytes.length);
            return Optional.of(ImageContent.from(base64Data, mimeType));
        } catch (IOException | ILinkException | IllegalArgumentException exception) {
            LOGGER.error("下载微信图片失败，userId={}", userId, exception);
            return Optional.empty();
        }
    }

    /**
     * 消息入队后开启微信输入状态。
     *
     * @param userId 微信用户ID
     */
    private void startTyping(String userId) {
        try {
            iLinkClient.startTyping(userId);
        } catch (IOException | ILinkException exception) {
            LOGGER.warn("开启微信输入状态失败，userId={}", userId, exception);
        }
    }

    /**
     * 完成当前批次后刷新微信输入状态。
     *
     * @param userId 微信用户ID
     * @param userQueue 用户独立消息队列
     * @param hasDeferredMessage 是否已有待处理的下一批消息
     */
    private void refreshTypingState(
            String userId,
            BlockingQueue<IncomingMessage> userQueue,
            boolean hasDeferredMessage) {
        try {
            iLinkClient.stopTyping(userId);
        } catch (IOException | ILinkException exception) {
            LOGGER.warn("关闭微信输入状态失败，userId={}", userId, exception);
        }

        if (hasDeferredMessage || !userQueue.isEmpty()) {
            startTyping(userId);
        }
    }

    /**
     * 模型调用失败时发送统一提示，避免异常继续向上传播。
     *
     * @param userId 微信用户ID
     */
    private void sendFailureReply(String userId) {
        try {
            iLinkClient.sendText(userId, MODEL_FAILURE_REPLY);
        } catch (IOException | ILinkException exception) {
            LOGGER.error("微信模型失败提示发送失败，userId={}", userId, exception);
        }
    }

    /**
     * 校验消息发送所需参数。
     *
     * @param userId 微信用户ID
     * @param content 消息内容
     */
    private void validateMessage(String userId, String content) {
        if (isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (isBlank(content)) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
    }

    /**
     * 判断字符串是否为空或仅包含空白字符。
     *
     * @param value 待检查字符串
     * @return 为空时返回true，否则返回false
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 根据图片文件头识别MIME类型。
     *
     * @param imageBytes 图片原始字节
     * @return 图片MIME类型
     */
    private String detectImageMimeType(byte[] imageBytes) {
        if (startsWith(imageBytes, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(imageBytes, 0x89, 0x50, 0x4E, 0x47)) {
            return "image/png";
        }
        if (startsWith(imageBytes, 0x47, 0x49, 0x46, 0x38)) {
            return "image/gif";
        }
        if (startsWith(imageBytes, 0x42, 0x4D)) {
            return "image/bmp";
        }
        if (imageBytes.length >= 12
                && startsWith(imageBytes, 0x52, 0x49, 0x46, 0x46)
                && matchesAt(imageBytes, 8, 0x57, 0x45, 0x42, 0x50)) {
            return "image/webp";
        }
        throw new IllegalArgumentException("不支持或无法识别的图片格式");
    }

    /**
     * 判断字节数组是否以指定文件头开始。
     *
     * @param source 源字节数组
     * @param expected 期望的无符号字节序列
     * @return 匹配时返回true，否则返回false
     */
    private boolean startsWith(byte[] source, int... expected) {
        return matchesAt(source, 0, expected);
    }

    /**
     * 判断字节数组指定位置是否与目标字节序列匹配。
     *
     * @param source 源字节数组
     * @param offset 起始偏移量
     * @param expected 期望的无符号字节序列
     * @return 匹配时返回true，否则返回false
     */
    private boolean matchesAt(byte[] source, int offset, int... expected) {
        if (source.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (Byte.toUnsignedInt(source[offset + index]) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 清理全局消息队列和全部用户消息队列。
     */
    @PreDestroy
    public void destroy() {
        globalMessageQueue.clear();
        userMessageQueues.values().forEach(BlockingQueue::clear);
        userMessageQueues.clear();
    }

    /**
     * 进入防抖队列的单条原始微信消息。
     *
     * @param message iLink原始微信消息
     * @param sentAtMillis 用户实际发送时间
     * @param receivedAtNanos 消息到达时的单调时间
     */
    private record IncomingMessage(
            WeixinMessage message,
            long sentAtMillis,
            long receivedAtNanos) {
    }

}
