package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.westart.ai.westart.service.VoiceGenerateService;
import com.westart.ai.westart.service.ai.VoiceGenerator;
import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.message.AiMessage;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;

/**
 * 语音生成服务实现，负责调用语音模型、解析完整音频并以文件形式发送给微信用户。
 */
@Service
@RequiredArgsConstructor
public class VoiceGenerateServiceImpl implements VoiceGenerateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoiceGenerateServiceImpl.class);
    private static final String GENERATED_AUDIOS_ATTRIBUTE = "generated_audios";
    private static final String GENERATED_VOICE_FILE_NAME = "assistant-reply.wav";
    private static final int GENERATED_VOICE_SAMPLE_RATE = 24_000;
    private static final int GENERATED_VOICE_BITS_PER_SAMPLE = 16;
    private static final int GENERATED_VOICE_CHANNEL_COUNT = 1;

    private final VoiceGenerator voiceGenerator;
    private final ILinkClient iLinkClient;
    private final OkHttpClient okHttpClient;

    /**
     * 根据文本生成语音，并将完整语音文件发送给指定微信用户。
     *
     * @param userId 微信用户ID
     * @param content 待合成的文本内容
     */
    @Override
    public void generateAndSendVoice(String userId, String content) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (StringUtils.isBlank(content)) {
            throw new IllegalArgumentException("语音合成内容不能为空");
        }

        AiMessage voiceMessage = voiceGenerator.generateVoice(content);
        Audio audio = extractGeneratedAudio(voiceMessage);
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
        if (!StringUtils.isBlank(audio.base64Data())) {
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
     * 校验音频下载响应并读取完整响应体。
     *
     * @param response 音频下载响应
     * @return 完整音频字节
     * @throws IOException 读取响应体失败时抛出
     */
    private byte[] getBytes(Response response) throws IOException {
        if (!response.isSuccessful()) {
            throw new IllegalStateException(
                    "下载生成语音失败，HTTP状态码=" + response.code());
        }
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            throw new IllegalStateException("下载生成语音失败，响应体为空");
        }
        byte[] audioBytes = responseBody.bytes();
        if (audioBytes.length == 0) {
            throw new IllegalStateException("下载生成语音失败，音频内容为空");
        }
        return audioBytes;
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
        if (StringUtils.isBlank(mimeType) || "audio/pcm".equalsIgnoreCase(mimeType)) {
            return wrapPcmAsWave(audioData);
        }
        throw new IllegalStateException("不支持的语音格式：" + mimeType);
    }

    /**
     * 判断模型返回的数据是否为WAV音频。
     *
     * @param audioData 模型返回的音频数据
     * @param mimeType 音频MIME类型
     * @return WAV音频返回true，否则返回false
     */
    private boolean isWaveAudio(byte[] audioData, String mimeType) {
        return "audio/wav".equalsIgnoreCase(mimeType)
                || "audio/wave".equalsIgnoreCase(mimeType)
                || (audioData.length >= 12
                && matchesAt(audioData, 0, 0x52, 0x49, 0x46, 0x46)
                && matchesAt(audioData, 8, 0x57, 0x41, 0x56, 0x45));
    }

    /**
     * 为裸PCM数据补充标准WAV文件头。
     *
     * @param pcmData 裸PCM音频数据
     * @return 可直接播放的WAV文件数据
     */
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

}
