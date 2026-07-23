package com.westart.ai.westart.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 管理微信用户发送图片的磁盘暂存。
 *
 * <p>图片以 {@code temp/images/{userId}/{uuid}.{ext}} 结构保存，
 * 支持跨消息批次的图片编辑场景。每个用户最多保留 {@value #MAX_IMAGES_PER_USER} 张图片，
 * 超出时自动删除最旧的图片。</p>
 */
@Service
public class ImageStoreService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageStoreService.class);
    private static final int MAX_IMAGES_PER_USER = 5;

    private final Path baseDir;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<ImageMeta>> userImages = new ConcurrentHashMap<>();

    /**
     * 内部记录，保存已持久化图片的元数据。
     */
    private record ImageMeta(
            String imageId,
            Path filePath,
            String mimeType,
            long savedAtMillis) {
    }

    public ImageStoreService(@Value("${app.temp.images.dir:./temp/images}") String baseDirPath) {
        this.baseDir = Path.of(baseDirPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseDir);
            LOGGER.info("图片临时目录已初始化，baseDir={}", this.baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建图片临时目录：" + this.baseDir, e);
        }
    }

    /**
     * 将图片字节写入磁盘并按用户建立索引。
     *
     * @param userId     微信用户 ID
     * @param imageBytes 图片原始字节
     * @param mimeType   图片 MIME 类型
     */
    public void save(String userId, byte[] imageBytes, String mimeType) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes 不能为空");
        }
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "image/png";
        }

        String imageId = UUID.randomUUID().toString();
        String extension = resolveExtension(mimeType);
        Path userDir = baseDir.resolve(userId);

        synchronized (getUserLock(userId)) {
            try {
                Files.createDirectories(userDir);
                Path filePath = userDir.resolve(imageId + "." + extension);
                Files.write(filePath, imageBytes);
                LOGGER.info("图片已暂存，userId={}，imageId={}，size={}bytes，path={}",
                        userId, imageId, imageBytes.length, filePath);

                Deque<ImageMeta> metas = userImages.computeIfAbsent(
                        userId, k -> new ConcurrentLinkedDeque<>());
                metas.addLast(new ImageMeta(imageId, filePath, mimeType, System.currentTimeMillis()));

                // 超出上限时删除最旧的
                while (metas.size() > MAX_IMAGES_PER_USER) {
                    ImageMeta oldest = metas.pollFirst();
                    if (oldest != null) {
                        deleteFile(oldest.filePath());
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("图片暂存失败，userId={}，不影响当前批次处理", userId, e);
            }
        }
    }

    /**
     * 获取用户最近暂存的图片。
     *
     * @param userId 微信用户 ID
     * @return 最近一张图片的元数据；用户无暂存图片时返回空
     */
    public Optional<ImageMeta> getLatest(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        Deque<ImageMeta> metas = userImages.get(userId);
        if (metas == null || metas.isEmpty()) {
            return Optional.empty();
        }
        ImageMeta latest = metas.peekLast();
        if (latest != null && Files.exists(latest.filePath())) {
            return Optional.of(latest);
        }
        // 文件已不存在则清理引用
        if (latest != null) {
            metas.removeLastOccurrence(latest);
        }
        return Optional.empty();
    }

    /**
     * 读取用户最近暂存图片的 Base64 编码字符串。
     *
     * @param userId 微信用户 ID
     * @return Base64 字符串和 MIME 类型；用户无暂存图片或读取失败时返回空
     */
    public Optional<ImageData> readLatestAsBase64(String userId) {
        Optional<ImageMeta> latest = getLatest(userId);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        ImageMeta meta = latest.get();
        try {
            byte[] bytes = Files.readAllBytes(meta.filePath());
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return Optional.of(new ImageData(base64, meta.mimeType()));
        } catch (IOException e) {
            LOGGER.warn("读取暂存图片失败，userId={}，path={}", userId, meta.filePath(), e);
            return Optional.empty();
        }
    }

    /**
     * 图片数据载体，供调用方使用。
     */
    public record ImageData(String base64Data, String mimeType) {
    }

    /**
     * 删除用户的所有暂存图片及其索引。
     *
     * @param userId 微信用户 ID
     */
    public void deleteAll(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        synchronized (getUserLock(userId)) {
            Deque<ImageMeta> metas = userImages.remove(userId);
            if (metas != null) {
                for (ImageMeta meta : metas) {
                    deleteFile(meta.filePath());
                }
                LOGGER.info("用户暂存图片已全部删除，userId={}，count={}", userId, metas.size());
            }

            // 尝试删除空的用户目录
            Path userDir = baseDir.resolve(userId);
            try {
                if (Files.exists(userDir) && isEmptyDir(userDir)) {
                    Files.deleteIfExists(userDir);
                }
            } catch (IOException e) {
                LOGGER.debug("清理用户目录失败，userId={}", userId, e);
            }
        }
    }

    /**
     * 应用关闭时清理所有暂存图片。
     */
    @PreDestroy
    public void cleanUp() {
        LOGGER.info("正在清理所有暂存图片，userCount={}", userImages.size());
        userImages.keySet().forEach(this::deleteAll);
        // 尝试删除基础目录（仅当为空时）
        try {
            if (Files.exists(baseDir) && isEmptyDir(baseDir)) {
                Files.deleteIfExists(baseDir);
            }
        } catch (IOException e) {
            LOGGER.debug("清理基础目录失败", e);
        }
    }

    // ---- 私有辅助方法 ----

    /**
     * 返回用于 synchronize 的锁定对象，锁粒度按 userId。
     */
    private String getUserLock(String userId) {
        return userId.intern();
    }

    /**
     * 根据 MIME 类型推导文件扩展名。
     */
    private String resolveExtension(String mimeType) {
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }

    /**
     * 删除文件，失败时仅记录日志。
     */
    private void deleteFile(Path filePath) {
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            LOGGER.warn("删除暂存图片文件失败，path={}", filePath, e);
        }
    }

    /**
     * 判断目录是否为空（不含任何文件或子目录）。
     */
    private boolean isEmptyDir(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }
}
