package com.westart.ai.westart;

import com.westart.ai.westart.service.ai.AudioDetector;
import dev.langchain4j.data.message.AudioContent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class AudioDetectorTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AudioDetectorTest.class);
    private static final Path TEST_AUDIO_PATH = Path.of(
            "debug-audio", "tts-original-1784691830675.wav");

    @Autowired
    private AudioDetector audioDetector;

    @Test
    public void testAudioDetect() {
        assertTrue(Files.isRegularFile(TEST_AUDIO_PATH),
                () -> "测试音频文件不存在：" + TEST_AUDIO_PATH.toAbsolutePath());

        AudioContent audioContent = AudioContent.from(TEST_AUDIO_PATH, "audio/wav");
        String transcription = audioDetector.detectAudio(audioContent);

        assertFalse(transcription == null || transcription.isBlank(), "音频识别结果不能为空");
        LOGGER.info("音频识别结果：{}", transcription);
    }
}
