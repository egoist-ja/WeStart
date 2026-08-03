package com.westart.ai.westart.infra;

import com.google.gson.Gson;
import com.westart.ai.westart.entity.ToolEntity;
import com.westart.ai.westart.entity.ToolType;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.service.tool.search.ToolSearchResult;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.milvus.v2.service.vector.response.SearchResp;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 业务类型与 LangChain4j 类型之间的适配器实现。
 *
 * <p>负责所有业务 DTO 与 LangChain4j 类型之间的标准化转换，
 * 每个方法只做纯类型转换，不包含业务逻辑。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jAdapterImpl implements LangChain4jAdapter {

    private static final String EMPTY_INPUT_SCHEMA = "{}";
    private static final String LOCAL_ID_PREFIX = "LOCAL:";
    private static final String MCP_ID_PREFIX = "MCP:";
    private static final String MCP_TOOL_NAME_SEPARATOR = "__";
    private static final String GENERATED_AUDIOS_ATTRIBUTE = "generated_audios";

    private final Gson gson;
    private final OkHttpClient okHttpClient;

    // ==================== 业务 -> LangChain4j ====================

    @Override
    public TextContent toTextContent(String text) {
        return TextContent.from(text.trim());
    }

    @Override
    public ImageContent toImageContent(byte[] imageBytes, String mimeType) {
        String base64Data = Base64.getEncoder().encodeToString(imageBytes);
        return ImageContent.from(base64Data, mimeType);
    }

    @Override
    public TextContent toVoiceContent(String transcription) {
        return TextContent.from(transcription.trim());
    }

    @Override
    public ToolEntity toToolEntity(ToolSpecification spec) {
        String toolName = spec.name();
        String description = spec.description();
        if (description == null || description.isBlank()) {
            description = toolName;
        }
        String inputSchema = spec.parameters() == null
                ? EMPTY_INPUT_SCHEMA
                : gson.toJson(spec.parameters());
        return new ToolEntity(
                stableId(LOCAL_ID_PREFIX + toolName),
                ToolType.LOCAL,
                toolName,
                description,
                inputSchema);
    }

    @Override
    public ToolEntity toToolEntity(String clientKey, String serverName, String instructions) {
        if (serverName == null || serverName.isBlank()) {
            serverName = clientKey;
        }
        String description = instructions == null || instructions.isBlank()
                ? serverName + " MCP服务"
                : serverName + "：" + instructions;
        return new ToolEntity(
                stableId(MCP_ID_PREFIX + clientKey),
                ToolType.MCP,
                clientKey,
                description,
                EMPTY_INPUT_SCHEMA);
    }

    @Override
    public TextSegment toTextSegment(ToolEntity entity) {
        return TextSegment.from(entity.description());
    }

    // ==================== LangChain4j -> 业务 ====================

    @Override
    public byte[] toBytes(Image image) {
        if (!StringUtils.isBlank(image.base64Data())) {
            try {
                return Base64.getDecoder().decode(image.base64Data());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("图片数据无效的Base64编码", exception);
            }
        }
        if (image.url() == null) {
            throw new IllegalStateException("图片不包含URL或Base64数据");
        }

        Request request = new Request.Builder()
                .url(image.url().toString())
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                throw new IllegalStateException(
                        "下载图片失败，HTTP状态码=" + response.code());
            }
            byte[] imageBytes = responseBody.bytes();
            if (imageBytes.length == 0) {
                throw new IllegalStateException("下载图片失败，图片内容为空");
            }
            return imageBytes;
        } catch (IOException exception) {
            throw new IllegalStateException("下载图片失败，url=" + image.url(), exception);
        }
    }

    @Override
    public byte[] toBytes(AiMessage aiMessage) {
        if (aiMessage == null) {
            throw new IllegalStateException("语音模型未返回有效响应");
        }
        Object generatedAudios = aiMessage.attributes()
                .get(GENERATED_AUDIOS_ATTRIBUTE);
        if (!(generatedAudios instanceof List<?> audioList) || audioList.isEmpty()) {
            throw new IllegalStateException("语音模型响应中不包含生成音频");
        }
        Object firstAudio = audioList.getFirst();
        if (!(firstAudio instanceof dev.langchain4j.data.audio.Audio audio)) {
            throw new IllegalStateException("语音模型返回了不支持的音频结果类型");
        }
        return resolveAudioBytes(audio);
    }

    @Override
    public EmbeddingMatch<ToolEntity> toEmbeddingMatch(SearchResp.SearchResult result) {
        Map<String, Object> fields = result.getEntity();

        ToolEntity tool = new ToolEntity(
                result.getPrimaryKey(),
                ToolType.valueOf(String.valueOf(fields.get("type"))),
                String.valueOf(fields.get("name")),
                String.valueOf(fields.get("description")),
                String.valueOf(fields.get("inputSchema"))
        );

        return new EmbeddingMatch<>(
                result.getScore().doubleValue(),
                result.getPrimaryKey(),
                null,
                tool
        );
    }

    @Override
    public ToolSearchResult toToolSearchResult(
            List<ToolEntity> matchedTools,
            List<ToolSpecification> searchableTools) {
        if (matchedTools == null
                || matchedTools.isEmpty()
                || searchableTools == null
                || searchableTools.isEmpty()) {
            return new ToolSearchResult(List.of(), "没有找到符合需求的工具");
        }

        Set<String> availableToolNames = new LinkedHashSet<>();
        for (ToolSpecification searchableTool : searchableTools) {
            if (searchableTool != null && searchableTool.name() != null) {
                availableToolNames.add(searchableTool.name());
            }
        }

        Set<String> foundToolNames = new LinkedHashSet<>();
        for (ToolEntity matchedTool : matchedTools) {
            if (matchedTool == null) {
                continue;
            }
            if (matchedTool.type() == ToolType.LOCAL) {
                if (availableToolNames.contains(matchedTool.name())) {
                    foundToolNames.add(matchedTool.name());
                }
                continue;
            }

            String mcpToolNamePrefix = matchedTool.name()
                    + MCP_TOOL_NAME_SEPARATOR;
            availableToolNames.stream()
                    .filter(name -> name.startsWith(mcpToolNamePrefix))
                    .forEach(foundToolNames::add);
        }

        String resultMessage = foundToolNames.isEmpty()
                ? "没有找到符合需求的工具"
                : "已找到可用工具：" + String.join("、", foundToolNames);
        return new ToolSearchResult(List.copyOf(foundToolNames), resultMessage);
    }

    // ==================== 内部方法 ====================

    /**
     * 根据工具业务标识生成长度固定的稳定主键。
     *
     * @param toolKey 工具业务标识
     * @return 由业务标识生成的32位主键
     */
    private static String stableId(String toolKey) {
        return UUID.nameUUIDFromBytes(
                        toolKey.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    /**
     * 获取音频模型返回的音频字节，优先读取二进制和Base64数据，否则下载音频URL。
     *
     * @param audio 语音生成结果
     * @return 完整音频字节
     */
    private byte[] resolveAudioBytes(dev.langchain4j.data.audio.Audio audio) {
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
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                throw new IllegalStateException(
                        "下载生成语音失败，HTTP状态码=" + response.code());
            }
            byte[] audioBytes = responseBody.bytes();
            if (audioBytes.length == 0) {
                throw new IllegalStateException("下载生成语音失败，音频内容为空");
            }
            return audioBytes;
        } catch (IOException exception) {
            throw new IllegalStateException("下载生成语音失败，url=" + audio.url(), exception);
        }
    }
}
