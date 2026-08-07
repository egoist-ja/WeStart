package com.westart.ai.westart.tool;

import com.westart.ai.westart.media.service.ai.ImageGenerator;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.image.Image;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerateTool {

    private static final int MAX_CONTEXT_LENGTH = 1_000;

    private final ImageGenerator imageGenerator;

    /**
     * 根据用户的图片生成描述生成图片。
     *
     * <p>仅当用户明确要求实际生成、绘制或设计图片时调用本工具。
     * 如果用户仅要求分析图片、描述图片内容或咨询图片生成技术，则不应调用。</p>
     *
     * @param context 忠实、完整的图片生成描述
     * @return 图片生成模型返回的不可变图片列表
     * @throws IllegalArgumentException 图片生成描述为空或超过长度限制时抛出
     * @throws IllegalStateException 图片生成失败或模型未返回有效图片时抛出
     */
    @Tool(returnBehavior = ReturnBehavior.IMMEDIATE,
            value = """
                    根据文字描述生成一张新图片，适用于绘画、海报、插图、视觉设计和场景图生成。
                    仅当用户明确要求实际生成图片时调用；分析已有图片、描述图片、编写提示词或咨询图片技术时不要调用。
                    context为完整图片需求，必须保留用户指定的主体、场景、风格、构图、用途和排除项。
                    """)
    public List<Image> generateImage(@P("完整、准确的图片生成描述，最多1000个字符") String context) {
        if (StringUtils.isBlank(context)) {
            log.warn("图片生成请求被拒绝，原因：图片生成描述为空");
            throw new IllegalArgumentException("图片生成描述不能为空");
        }

        String normalizedContext = context.trim();
        if (normalizedContext.length() > MAX_CONTEXT_LENGTH) {
            log.warn("图片生成请求被拒绝，原因：描述超过长度限制，contextLength={}，maxLength={}",
                    normalizedContext.length(),
                    MAX_CONTEXT_LENGTH);
            throw new IllegalArgumentException("图片生成描述不能超过" + MAX_CONTEXT_LENGTH + "个字符");
        }
        log.info("开始调用图片生成模型，contextLength={}", normalizedContext.length());
        List<Image> images;
        try {
            images = imageGenerator.generateImage(normalizedContext);
        } catch (RuntimeException exception) {
            log.error(
                    "调用图片生成模型失败，contextLength={}",
                    normalizedContext.length(),
                    exception);
            throw new IllegalStateException("调用图片生成模型失败", exception);
        }

        if (images == null || images.isEmpty()) {
            log.error(
                    "图片生成模型未返回有效图片，contextLength={}",
                    normalizedContext.length());
            throw new IllegalStateException("图片生成模型未返回有效图片");
        }
        if (images.stream().anyMatch(Objects::isNull)) {
            log.error(
                    "图片生成模型返回了空图片元素，contextLength={}，imageCount={}",
                    normalizedContext.length(),
                    images.size());
            throw new IllegalStateException("图片生成模型返回了无效图片");
        }

        List<Image> immutableImages = List.copyOf(images);
        log.info("图片生成成功，contextLength={}，imageCount={}",
                normalizedContext.length(),
                immutableImages.size());
        return immutableImages;
    }
}
