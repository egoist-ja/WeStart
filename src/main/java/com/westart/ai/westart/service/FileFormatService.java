package com.westart.ai.westart.service;

import java.io.IOException;

/**
 * 文件格式转换服务接口。
 * <p>
 * 提供文档和音频文件的格式互转能力，包括：
 * <ul>
 *   <li>TXT / Markdown / Word / PDF 文档互转</li>
 *   <li>Markdown → PDF / HTML 转换</li>
 *   <li>音频文件（MP3/M4A）→ WAV 转换</li>
 * </ul>
 * 部分转换依赖本地库（PDFBox、POI、jcodec），部分依赖 uapis.cn 云 API。
 */
public interface FileFormatService {

    /**
     * 将音频文件转换为 WAV 格式。
     *
     * @param srcData  源音频文件字节
     * @param srcMime  源音频 MIME 类型（audio/wav、audio/mpeg、audio/mp4）
     * @return WAV 文件字节；参数无效时返回 null
     */
    byte[] toWav(byte[] srcData, String srcMime) throws IOException;

    /**
     * 将 Word 文档（.docx）转换为 PDF 文件。
     * <p>内部先将 docx 解析为 Markdown，再调用 uapis.cn API 渲染为 PDF。</p>
     *
     * @param srcData  .docx 文件字节
     * @param srcMime  源 MIME 类型（仅支持 application/vnd.openxmlformats-officedocument.wordprocessingml.document）
     * @return PDF 文件字节
     */
    byte[] toPdf(byte[] srcData, String srcMime) throws IOException;

    /**
     * 将文件转换为 Word 文档（.docx）。
     * <p>支持的源格式：PDF（PDFBox 提取文本 → docx4j 生成）、
     * TXT（直接写入段落）、Markdown（解析标题/列表 → 带样式 docx）。</p>
     *
     * @param srcData  源文件字节
     * @param srcMime  源 MIME 类型（application/pdf、text/plain、text/markdown）
     * @return .docx 文件字节
     */
    byte[] toDocx(byte[] srcData, String srcMime) throws IOException;

    /**
     * 将文件转换为纯文本（TXT）。
     * <p>支持的源格式：Word docx（POI 提取段落文本）、PDF（PDFBox 提取）、
     * Markdown（剥离语法标记）、TXT（直接透传）。</p>
     *
     * @param srcData  源文件字节
     * @param srcMime  源 MIME 类型（application/vnd.openxmlformats-officedocument.wordprocessingml.document、
     *                 application/pdf、text/markdown、text/plain）
     * @return 纯文本内容字符串
     */
    String toTxt(byte[] srcData, String srcMime) throws IOException;

    /**
     * 将文件转换为 Markdown 文本。
     * <p>支持的源格式：Word docx（POI 解析样式生成 Markdown）、
     * TXT（直接透传）、PDF（PDFBox 提取后转为 Markdown）。</p>
     *
     * @param srcData  源文件字节
     * @param srcMime  源 MIME 类型（application/vnd.openxmlformats-officedocument.wordprocessingml.document、
     *                 text/plain、application/pdf）
     * @return Markdown 格式文本
     */
    String toMarkdown(byte[] srcData, String srcMime) throws IOException;

    /**
     * 将 Markdown 文本直接转换为 PDF 字节流。
     * <p>调用 uapis.cn 的 /text/markdown-to-pdf 接口。</p>
     *
     * @param markdownText Markdown 格式的文本
     * @param theme        PDF 主题（github / minimal / light / dark，传 null 使用默认 github）
     * @param paperSize    纸张大小（A4 / Letter，传 null 使用默认 A4）
     * @return PDF 文件字节
     */
    byte[] markdownToPdf(String markdownText, String theme, String paperSize) throws IOException;

    /**
     * 将 Markdown 文本转换为 HTML。
     * <p>调用 uapis.cn 的 /text/markdown-to-html 接口。</p>
     *
     * @param markdownText Markdown 格式的文本
     * @param completePage true 返回完整 HTML 页面（含 DOCTYPE 和内联样式），
     *                     false 返回 HTML 片段（不含外层页面结构）
     * @return HTML 内容字符串
     */
    String markdownToHtml(String markdownText, boolean completePage) throws IOException;

}
