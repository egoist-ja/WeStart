package com.westart.ai.westart.infra;

import com.westart.ai.westart.entity.ToolEntity;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.service.tool.search.ToolSearchResult;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.List;

/**
 * 业务类型与 LangChain4j 类型之间的适配器。
 *
 * <p>提供业务层 DTO 与 LangChain4j 类型之间的标准化转换，
 * 实现类型系统的解耦，使业务层不直接依赖 LangChain4j 的具体类型。</p>
 */
public interface LangChain4jAdapter {

    // ==================== 业务 -> LangChain4j ====================

    /**
     * 将纯文本转换为 LangChain4j 的 TextContent。
     *
     * @param text 纯文本内容
     * @return LangChain4j TextContent
     */
    TextContent toTextContent(String text);

    /**
     * 将图片字节转换为 LangChain4j 的 ImageContent（Base64 编码）。
     *
     * @param imageBytes 图片二进制数据
     * @param mimeType   图片 MIME 类型
     * @return LangChain4j ImageContent
     */
    ImageContent toImageContent(byte[] imageBytes, String mimeType);

    /**
     * 将语音转写文本转换为 LangChain4j 的 TextContent。
     *
     * @param transcription 语音转写结果
     * @return LangChain4j TextContent
     */
    TextContent toVoiceContent(String transcription);

    /**
     * 将 LangChain4j 工具定义转换为业务 ToolEntity。
     *
     * @param spec LangChain4j 工具定义
     * @return 业务工具实体
     */
    ToolEntity toToolEntity(ToolSpecification spec);

    /**
     * 将 MCP 客户端信息转换为业务 ToolEntity。
     *
     * @param clientKey  MCP 客户端标识
     * @param serverName MCP 服务器名称
     * @param instructions MCP 服务器指令描述
     * @return 业务工具实体
     */
    ToolEntity toToolEntity(String clientKey, String serverName, String instructions);

    /**
     * 将业务 ToolEntity 转换为 LangChain4j 的 TextSegment。
     *
     * @param entity 业务工具实体
     * @return LangChain4j TextSegment
     */
    TextSegment toTextSegment(ToolEntity entity);

    // ==================== LangChain4j -> 业务 ====================

    /**
     * 将 LangChain4j Image 转换为 byte[]（支持 Base64 解码和 URL 下载）。
     *
     * @param image LangChain4j Image
     * @return 图片二进制数据
     * @throws IllegalStateException 图片数据获取失败时抛出
     */
    byte[] toBytes(Image image);

    /**
     * 将 LangChain4j AiMessage 中的音频转换为 byte[]。
     *
     * @param aiMessage LangChain4j AiMessage
     * @return 音频二进制数据
     * @throws IllegalStateException 音频数据获取失败时抛出
     */
    byte[] toBytes(AiMessage aiMessage);

    /**
     * 将 Milvus 搜索结果转换为 LangChain4j 的 EmbeddingMatch。
     *
     * @param result Milvus 搜索结果
     * @return LangChain4j EmbeddingMatch
     */
    EmbeddingMatch<ToolEntity> toEmbeddingMatch(SearchResp.SearchResult result);

    /**
     * 将业务工具搜索结果转换为 LangChain4j 的 ToolSearchResult。
     *
     * @param matchedTools    业务工具搜索结果
     * @param searchableTools 当前调用中的可搜索工具
     * @return LangChain4j ToolSearchResult
     */
    ToolSearchResult toToolSearchResult(
            List<ToolEntity> matchedTools,
            List<ToolSpecification> searchableTools);
}
