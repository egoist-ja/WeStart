package com.westart.ai.westart.service;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import com.alibaba.dashscope.utils.OSSUtils;
import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 多模态识别服务
 */
@Slf4j
@Service
public class MultimodalService {

    @Value("${dashscope.api-key:sk-ws-H.EHIHHID.c8kw.MEUCICNo3drnBHOC9D1XvrVNErjlZZvPNj8kFj8NgMRXto5oAiEAusTVfdUymJwbqDIQj9p72nzYmU7_ssidcZTngnzlKBc}")
    private String apiKey;

    private static final String VISION_MODEL = "qwen-vl-max";

    private final HttpClient httpClient;

    @Autowired
    private LearningService learningService;

    public MultimodalService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 识别图片并返回回复信息
     */
    public String analyzeImage(String userId, byte[] imageBytes, String fileName) {
        try {
            log.info("开始图片识别: {}", fileName);
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String description = callVisionModel(base64Image);
            
            if (description.contains("风景") || description.contains("旅游")) {
                learningService.addPreference(userId, "旅游");
                return "TEXT:这张照片拍得真不错！\n\n" + description + "\n\n是个很美的地方呢～";
            } else if (description.contains("食物") || description.contains("美食")) {
                learningService.addPreference(userId, "美食");
                return "TEXT:哇，看起来好好吃！\n\n" + description + "\n\n我也想吃呢 😋";
            } else {
                return "TEXT:我看到了！\n\n" + description + "\n\n拍得真不错呢～";
            }
        } catch (Exception e) {
            log.error("图片识别失败", e);
            return "TEXT:抱歉，我无法识别这张图片。";
        }
    }

    public String generateImage(String prompt) {
        try {
            log.info("开始生成图片: {}", prompt);

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", "qwen-image-2.0-pro");

            JsonObject input = new JsonObject();
            JsonArray messages = new JsonArray();
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject textObj = new JsonObject();
            textObj.addProperty("text", prompt);
            content.add(textObj);
            message.add("content", content);
            messages.add(message);
            input.add("messages", messages);
            requestBody.add("input", input);

            JsonObject parameters = new JsonObject();
            parameters.addProperty("size", "2048*2048");
            parameters.addProperty("n", 1);
            parameters.addProperty("prompt_extend", true);
            parameters.addProperty("watermark", false);
            parameters.addProperty("negative_prompt", "低分辨率，低画质，肢体畸形，手指畸形，画面过饱和，蜡像感，人脸无细节，过度光滑，画面具有AI感。构图混乱。文字模糊，扭曲。");
            requestBody.add("parameters", parameters);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray choices = result.getAsJsonObject("output").getAsJsonArray("choices");
                if (choices != null && choices.size() > 0) {
                    JsonArray contentList = choices.get(0).getAsJsonObject()
                            .getAsJsonObject("message").getAsJsonArray("content");
                    for (JsonElement elem : contentList) {
                        JsonObject obj = elem.getAsJsonObject();
                        if (obj.has("image")) {
                            String imageUrl = obj.get("image").getAsString();
                            byte[] imageBytes = downloadImageBytes(imageUrl);
                            Path tempFile = Files.createTempFile("generated_", ".png");
                            Files.write(tempFile, imageBytes);
                            log.info("图片生成成功: {}", tempFile.toAbsolutePath());
                            return "IMAGE:" + tempFile.toAbsolutePath() + "|" + prompt;
                        }
                    }
                }
            }

            log.error("图片生成API调用失败: HTTP {}, body: {}", response.statusCode(), response.body());
            return "TEXT:抱歉，我现在没办法画图呢，请稍后再试～";
        } catch (Exception e) {
            log.error("图片生成失败", e);
            return "TEXT:抱歉，图片生成失败了，请稍后再试。";
        }
    }

    private byte[] downloadImageBytes(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 200) {
            return response.body();
        }
        throw new IOException("下载图片失败: HTTP " + response.statusCode());
    }

    /**
     * 识别语音（调用通义千问 Paraformer 模型）
     */
    public String analyzeAudio(String userId, byte[] audioBytes, String fileName) {
        Path wavFile = null;
        try {
            log.info("开始语音识别: {} ({} bytes)", fileName, audioBytes.length);

            Path audioFile = Files.createTempFile("voice_", ".raw");
            Files.write(audioFile, audioBytes);
            wavFile = convertToWav(audioFile);
            Files.deleteIfExists(audioFile);

            if (wavFile != null) {
                String text = callAudioModel(wavFile.toAbsolutePath().toString());
                if (!"语音识别失败".equals(text)) {
                    learningService.addPreference(userId, "语音");
                    log.info("语音识别结果: {}", text);
                    return text;
                }
            }

            return null;

        } catch (Exception e) {
            log.error("语音识别失败", e);
            return null;
        } finally {
            if (wavFile != null) {
                try { Files.deleteIfExists(wavFile); } catch (IOException ignored) {}
            }
        }
    }

    private Path convertToWav(Path audioFile) {
        String ffmpeg = resolveFFmpeg();
        String decoder = resolveSilkDecoder();
        if (decoder != null) {
            try {
            Path pcmFile = Files.createTempFile("voice_", ".pcm");
                ProcessBuilder pb = new ProcessBuilder(decoder, audioFile.toAbsolutePath().toString(), pcmFile.toAbsolutePath().toString());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor(30, TimeUnit.SECONDS);
                long pcmSize = Files.size(pcmFile);
                log.info("Silk解码: exit={}, pcmSize={}", p.exitValue(), pcmSize);
                if (p.exitValue() == 0 && pcmSize > 50000) {
                    Path wavFile = Files.createTempFile("voice_", ".wav");
                    ProcessBuilder pb2 = new ProcessBuilder(ffmpeg, "-y", "-f", "s16le", "-ar", "16000", "-ac", "1",
                            "-i", pcmFile.toAbsolutePath().toString(), wavFile.toAbsolutePath().toString());
                    pb2.redirectErrorStream(true);
                    Process p2 = pb2.start();
                    if (p2.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p2.exitValue() == 0 && Files.size(wavFile) > 10000) {
                        Files.deleteIfExists(pcmFile);
                        log.info("Silk解码+WAV封装成功: pcm={}, wav={}", pcmSize, Files.size(wavFile));
                        return wavFile;
                    }
                    Files.deleteIfExists(wavFile);
                }
                Files.deleteIfExists(pcmFile);
            } catch (Exception e) {
                log.warn("Silk解码失败: {}", e.getMessage());
            }
        }
        // fallback: FFmpeg 硬解
        String[][] strategies = {{}, {"-f", "s16le", "-ar", "16000", "-ac", "1"}, {"-f", "amr"}, {"-f", "silk"}};
        Path bestFile = null;
        long bestSize = 0;
        for (String[] opts : strategies) {
            try {
                Path wavFile = Files.createTempFile("voice_", ".wav");
                List<String> cmd = new ArrayList<>();
                cmd.add(ffmpeg);
                cmd.add("-y");
                for (String opt : opts) cmd.add(opt);
                cmd.add("-i");
                cmd.add(audioFile.toAbsolutePath().toString());
                cmd.add("-ar");
                cmd.add("16000");
                cmd.add("-ac");
                cmd.add("1");
                cmd.add(wavFile.toAbsolutePath().toString());
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    long size = Files.size(wavFile);
                    if (size > bestSize) {
                        if (bestFile != null) Files.deleteIfExists(bestFile);
                        bestFile = wavFile;
                        bestSize = size;
                        log.info("FFmpeg 转换: opts={}, size={}", Arrays.toString(opts), size);
                    } else {
                        Files.deleteIfExists(wavFile);
                    }
                } else {
                    Files.deleteIfExists(wavFile);
                }
            } catch (Exception ignored) {}
        }
        return bestFile;
    }

    private String resolveSilkDecoder() {
        String workDir = System.getProperty("user.dir");
        String[] paths = {"decoder.exe", "silk-decoder.exe", "silk-v3-decoder.exe"};
        for (String name : paths) {
            if (Files.exists(Path.of(workDir, name))) {
                log.info("找到 Silk 解码器: {}", name);
                return Path.of(workDir, name).toAbsolutePath().toString();
            }
        }
        return null;
    }

    public byte[] textToSpeech(String text) {
        try {
            if (text.length() > 300) {
                text = text.substring(0, 300);
            }
            log.info("开始语音合成 ({} chars): {}", text.length(), text);

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", "qwen-audio-3.0-tts-flash");
            JsonObject inputObj = new JsonObject();
            inputObj.addProperty("text", text);
            inputObj.addProperty("voice", "longanhuan_v3.6");
            inputObj.addProperty("format", "mp3");
            requestBody.add("input", inputObj);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject output = result.getAsJsonObject("output");
                if (output != null && output.has("audio")) {
                    JsonObject audio = output.getAsJsonObject("audio");
                    if (audio.has("url")) {
                        String audioUrl = audio.get("url").getAsString();
                        byte[] audioBytes = downloadImageBytes(audioUrl);
                        log.info("语音合成成功: {} bytes", audioBytes.length);
                        return audioBytes;
                    }
                }
            }

            log.error("语音合成失败: HTTP {}, body: {}", response.statusCode(), response.body());
            return null;
        } catch (Exception e) {
            log.error("语音合成失败", e);
            return null;
        }
    }

    /**
     * 识别视频（降级方案：无需 FFmpeg）
     */
    public String analyzeVideo(String userId, byte[] videoBytes, String fileName) {
        try {
            log.info("开始视频识别: {} ({} bytes)", fileName, videoBytes.length);

            String size = String.format("%.1f MB", videoBytes.length / 1024.0 / 1024.0);
            String duration = estimateDuration(videoBytes.length);

            Path tempVideo = Files.createTempFile("video_", "_" + fileName);
            Files.write(tempVideo, videoBytes);

            List<Path> frames = extractVideoKeyFrames(tempVideo);

            StringBuilder description = new StringBuilder();
            if (!frames.isEmpty()) {
                for (int i = 0; i < frames.size(); i++) {
                    try {
                        byte[] frameBytes = Files.readAllBytes(frames.get(i));
                        String base64Frame = Base64.getEncoder().encodeToString(frameBytes);
                        String frameDesc = callVisionModel(base64Frame);
                        description.append("第").append(i + 1).append("帧：").append(frameDesc).append("\n");
                    } catch (Exception e) {
                        log.warn("分析第{}帧失败", i + 1, e);
                    }
                }
                for (Path frame : frames) {
                    try { Files.deleteIfExists(frame); } catch (IOException ignored) {}
                }
            }
            try { Files.deleteIfExists(tempVideo); } catch (IOException ignored) {}

            String videoDesc = description.length() > 0
                    ? description.toString()
                    : "未能提取视频画面内容（请确保已安装 FFmpeg: scoop install ffmpeg）";

            String reply = "TEXT:收到你的视频啦！\n\n" +
                    "📹 文件大小：" + size + "\n" +
                    "⏱️ 预计时长：" + duration + "\n\n" +
                    "🎬 AI分析：\n" + videoDesc;

            learningService.addPreference(userId, "视频");
            learningService.recordConversation(userId, "[视频] " + fileName, reply);

            return reply;

        } catch (Exception e) {
            log.error("视频识别失败", e);
            return "TEXT:抱歉，视频处理失败，请稍后再试。";
        }
    }

    private List<Path> extractVideoKeyFrames(Path videoPath) {
        List<Path> frames = new ArrayList<>();
        String ffmpeg = resolveFFmpeg();
        try {
            Path outputDir = Files.createTempDirectory("video_frames_");
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg, "-i", videoPath.toAbsolutePath().toString(),
                    "-vf", "select=eq(n\\,0)+eq(n\\,30)+eq(n\\,60)+eq(n\\,90)+eq(n\\,120)",
                    "-vsync", "vfr",
                    outputDir.resolve("frame_%03d.jpg").toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            if (Files.exists(outputDir)) {
                Files.list(outputDir)
                        .filter(p -> p.toString().endsWith(".jpg"))
                        .sorted()
                        .limit(5)
                        .forEach(frames::add);
            }
            if (frames.isEmpty()) {
                ProcessBuilder pb2 = new ProcessBuilder(
                        ffmpeg, "-i", videoPath.toAbsolutePath().toString(),
                        "-vframes", "1",
                        outputDir.resolve("frame_001.jpg").toAbsolutePath().toString()
                );
                pb2.redirectErrorStream(true);
                Process p2 = pb2.start();
                p2.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                Path singleFrame = outputDir.resolve("frame_001.jpg");
                if (Files.exists(singleFrame)) {
                    frames.add(singleFrame);
                }
            }
        } catch (IOException e) {
            log.warn("FFmpeg 未安装或不可用，无法提取视频帧。请安装 FFmpeg 以启用视频内容识别。", e);
        } catch (InterruptedException e) {
            log.warn("视频帧提取超时", e);
            Thread.currentThread().interrupt();
        }
        return frames;
    }

    private String resolveFFmpeg() {
        String home = System.getProperty("user.home");
        String workDir = System.getProperty("user.dir");
        List<String> candidates = List.of(
                workDir + "\\ffmpeg.exe",
                home + "\\scoop\\shims\\ffmpeg.exe",
                home + "\\scoop\\apps\\ffmpeg\\current\\bin\\ffmpeg.exe",
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe"
        );
        for (String path : candidates) {
            if (Files.exists(Path.of(path))) {
                return path;
            }
        }
        return "ffmpeg";
    }

    /**
     * 估算视频时长（基于文件大小）
     */
    private String estimateDuration(long fileSize) {
        // 假设 1MB ≈ 10秒视频（720p）
        long seconds = fileSize / (1024 * 1024) * 10;
        if (seconds < 60) {
            return seconds + "秒";
        } else {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return minutes + "分" + secs + "秒";
        }
    }

    /**
     * 调用视觉模型
     */
    private String callVisionModel(String base64Image) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", VISION_MODEL);

        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");

        JsonArray content = new JsonArray();

        JsonObject imageObj = new JsonObject();
        imageObj.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:image/jpeg;base64," + base64Image);
        imageObj.add("image_url", imageUrl);
        content.add(imageObj);

        JsonObject textObj = new JsonObject();
        textObj.addProperty("type", "text");
        textObj.addProperty("text", "请描述这张图片的内容");
        content.add(textObj);

        message.add("content", content);
        messages.add(message);
        requestBody.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
            return result.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        }

        return "图片内容识别中...";
    }

    /**
     * 调用语音识别模型（Paraformer）
     */
    private String callAudioModel(String audioFilePath) throws Exception {
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";

        String ossUrl = OSSUtils.upload("paraformer-v2", audioFilePath, apiKey);
        log.info("WAV 上传成功: {}", ossUrl);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "paraformer-v2");
        JsonObject input = new JsonObject();
        JsonArray fileUrls = new JsonArray();
        fileUrls.add(ossUrl);
        input.add("file_urls", fileUrls);
        requestBody.add("input", input);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("format", "wav");
        parameters.addProperty("sample_rate", 16000);
        JsonArray lang = new JsonArray();
        lang.add("zh");
        parameters.add("language_hints", lang);
        requestBody.add("parameters", parameters);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-OssResourceResolve", "enable")
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject output = result.getAsJsonObject("output");
            String taskStatus = output.get("task_status").getAsString();
            String taskId = output.get("task_id").getAsString();
            if ("SUCCEEDED".equals(taskStatus)) {
                return extractTranscription(output);
            }
            return pollTranscriptionResult(taskId);
        }

        log.error("语音识别失败: HTTP {}, body: {}", response.statusCode(), response.body());
        return "语音识别失败";
    }

    private String pollTranscriptionResult(String taskId) throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1500);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET().timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject r = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonObject output = r.getAsJsonObject("output");
                String status = output.get("task_status").getAsString();
                if ("SUCCEEDED".equals(status)) {
                    return extractTranscription(output);
                } else if ("FAILED".equals(status)) {
                    log.error("语音转写失败: {}", output);
                    return "语音识别失败";
                }
            }
        }
        return "语音识别失败";
    }

    private String extractTranscription(JsonObject output) {
        if (output.has("text")) return output.get("text").getAsString();
        if (output.has("results")) {
            JsonArray results = output.getAsJsonArray("results");
            if (results != null && results.size() > 0) {
                JsonObject first = results.get(0).getAsJsonObject();
                if (first.has("transcription_url")) {
                    try {
                        String url = first.get("transcription_url").getAsString();
                        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET()
                                .timeout(Duration.ofSeconds(10)).build();
                        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() == 200) {
                            JsonObject t = JsonParser.parseString(resp.body()).getAsJsonObject();
                            if (t.has("transcripts")) {
                                JsonArray transcripts = t.getAsJsonArray("transcripts");
                                if (transcripts != null && transcripts.size() > 0) {
                                    return transcripts.get(0).getAsJsonObject().get("text").getAsString();
                                }
                            }
                            if (t.has("text")) return t.get("text").getAsString();
                        }
                    } catch (Exception ignored) {}
                }
                if (first.has("text")) return first.get("text").getAsString();
            }
        }
        return "语音识别失败";
    }

    /**
     * 生成语音回复
     */
    private String generateAudioReply(String userId, String text) {
        if (text.contains("你好") || text.contains("您好")) {
            return "TEXT:收到你的语音消息！\n\n你说的是：\"" + text + "\"\n\n你好呀～有什么可以帮你的？";
        } else if (text.contains("天气")) {
            learningService.addPreference(userId, "天气");
            return "TEXT:我听到你问天气！\n\n你说的是：\"" + text + "\"\n\n需要我帮你查询天气吗？";
        } else {
            return "TEXT:收到你的语音！\n\n你说的是：\"" + text + "\"\n\n我记下来啦～";
        }
    }

    /**
     * 生成视频回复
     */
    private String generateVideoReply(String userId, String description, String size) {
        if (description.contains("风景") || description.contains("旅游")) {
            return "TEXT:这个视频拍得真不错！(" + size + ")\n\n" + description + "\n\n风景很美呢～";
        } else if (description.contains("食物") || description.contains("美食")) {
            return "TEXT:看起来好好吃！(" + size + ")\n\n" + description + "\n\n我也想尝尝 😋";
        } else {
            return "TEXT:收到你的视频！(" + size + ")\n\n" + description + "\n\n内容很精彩呢～";
        }
    }
}
