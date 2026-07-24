package com.westart.ai.westart.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.westart.ai.westart.service.ImageGenerateService;
import com.westart.ai.westart.service.ai.ImageGenerator;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 图片生成服务实现，负责调用图片模型并将生成结果发送给微信用户。
 */
@Service
@RequiredArgsConstructor
public class ImageGenerateServiceImpl implements ImageGenerateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageGenerateServiceImpl.class);
    private static final String GENERATED_IMAGE_FILE_NAME_PREFIX = "generated-image-";
    private static final String GENERATED_IMAGE_FILE_EXTENSION = ".png";

    private final ImageGenerator imageGenerator;
    private final OkHttpClient okHttpClient;

    /**
     * 根据消息内容生成图片，并将生成结果发送给指定微信用户。
     *
     * @param client 当前消息所属的iLink客户端
     * @param userId 微信用户ID
     * @param contents 当前图片任务引用的原始消息内容
     * @param context 路由模型补充的图片生成描述
     */
    @Override
    public void generateAndSendImages(
            ILinkClient client,
            String userId,
            List<Content> contents,
            String context) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (contents == null || contents.isEmpty()) {
            throw new IllegalArgumentException("图片生成内容不能为空");
        }

        List<Content> imageContents = prepareImageGenerationContents(contents, context);
        List<Image> images = imageGenerator.generateImage(imageContents);
        sendGeneratedImages(client, userId, images);
    }

    /**
     * 组装图片生成模型输入，优先使用路由模型补全的任务描述，并保留参考图片。
     *
     * @param contents 当前图片任务引用的原始消息内容
     * @param context 路由模型补充的图片生成描述
     * @return 可直接交给图片生成模型的不可变内容列表
     */
    private List<Content> prepareImageGenerationContents(
            List<Content> contents,
            String context) {
        if (StringUtils.isBlank(context)) {
            return List.copyOf(contents);
        }

        List<Content> imageContents = new ArrayList<>(contents.size() + 1);
        imageContents.add(TextContent.from(context));
        contents.stream()
                .filter(ImageContent.class::isInstance)
                .forEach(imageContents::add);
        return List.copyOf(imageContents);
    }

    /**
     * 将图片模型返回的全部图片发送给微信用户。
     *
     * @param client 当前消息所属的iLink客户端
     * @param userId 微信用户ID
     * @param images 图片生成结果
     */
    private void sendGeneratedImages(
            ILinkClient client,
            String userId,
            List<Image> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalStateException("图片生成模型未返回图片");
        }

        for (int index = 0; index < images.size(); index++) {
            byte[] imageBytes = resolveGeneratedImageBytes(images.get(index));
            String fileName = GENERATED_IMAGE_FILE_NAME_PREFIX
                    + (index + 1)
                    + GENERATED_IMAGE_FILE_EXTENSION;
            try {
                client.sendImage(userId, imageBytes, fileName, null);
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
        if (!StringUtils.isBlank(image.base64Data())) {
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

    /**
     * 校验图片下载响应并读取完整响应体。
     *
     * @param response 图片下载响应
     * @return 图片原始字节
     * @throws IOException 读取响应体失败时抛出
     */
    private byte[] getBytes(Response response) throws IOException {
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
}
