package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import com.westart.ai.westart.util.UserFileCache;
import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * 邮件发送工具，负责将给定内容通过 SMTP 发送到指定邮箱。
 *
 * <p>支持 HTML 和纯文本两种正文格式，支持 Base64 编码的附件。</p>
 * <p>所有参数均由大模型根据用户对话自动填充，本工具不自行生成内容。</p>
 */
@Service("dailyHotTool")
@RequiredArgsConstructor
@Slf4j
public class DailyHotTool {

    /** Spring 邮件发送器，通过 yaml 中 spring.mail 配置连接 SMTP */
    private final JavaMailSender mailSender;

    /** 读取 SMTP 配置（host、username 等） */
    private final Environment environment;

    /**
     * 发送 HTML 或纯文本邮件（无附件）。
     *
     * @param toEmail 收件邮箱地址
     * @param subject 邮件主题
     * @param content 邮件正文（HTML 模式下换行自动转为 &lt;br&gt;）
     * @param isHtml  true=HTML格式，false=纯文本
     * @return 操作结果提示
     */
    @Tool(value = "发送HTML或纯文本邮件。isHtml为true时发送HTML格式，false时发送纯文本。所有参数必填。")
    public String sendEmail(
            @P("收件邮箱地址") String toEmail,
            @P("邮件主题") String subject,
            @P("邮件正文内容") String content,
            @P("是否HTML格式，true为HTML，false为纯文本") boolean isHtml) {
        long startTime = System.currentTimeMillis();
        log.info("[DailyHotTool] 开始执行，toEmail={}, isHtml={}", toEmail, isHtml);

        try {
            sendMail(toEmail, subject, content, isHtml);
            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("[DailyHotTool] 执行成功，总耗时={}ms", totalDuration);
            return "邮件已发送至 " + toEmail;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[DailyHotTool] 执行失败，toEmail={}，耗时={}ms", toEmail, duration, e);
            return "邮件发送失败，请稍后重试。";
        }
    }

    /**
     * 发送带附件的邮件（HTML 或纯文本）。
     *
     * @param toEmail          收件邮箱
     * @param subject          邮件主题
     * @param content          邮件正文
     * @param isHtml           是否 HTML 格式
     * @param attachmentBase64 附件的 Base64 编码数据
     * @param attachmentName   附件文件名（含扩展名，如 report.pdf）
     * @return 操作结果提示
     */
    @Tool(value = "发送携带附件的邮件（HTML或纯文本）。attachmentBase64为文件Base64编码，attachmentName为文件名含扩展名。所有参数必填。")
    public String sendEmailWithAttachment(
            @P("收件邮箱地址") String toEmail,
            @P("邮件主题") String subject,
            @P("邮件正文内容") String content,
            @P("是否HTML格式") boolean isHtml,
            @P("附件的Base64编码数据") String attachmentBase64,
            @P("附件文件名，含扩展名如 report.pdf") String attachmentName) {
        long startTime = System.currentTimeMillis();
        log.info("[DailyHotTool] 带附件发送，toEmail={}, isHtml={}, attach={}", toEmail, isHtml, attachmentName);

        try {
            sendMailWithAttachment(toEmail, subject, content, isHtml, attachmentBase64, attachmentName);
            long totalDuration = System.currentTimeMillis() - startTime;
            log.info("[DailyHotTool] 带附件发送成功，总耗时={}ms", totalDuration);
            return "带附件的邮件已发送至 " + toEmail;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[DailyHotTool] 带附件发送失败，toEmail={}，耗时={}ms", toEmail, duration, e);
            return "邮件发送失败，请稍后重试。";
        }
    }

    /**
     * 将用户在当前会话中上传的文件作为附件发送到指定邮箱（HTML 或纯文本）。
     *
     * <p>直接从 {@link UserFileCache} 读取缓存文件，无需传入 Base64 数据。</p>
     */
    @Tool(value = "将用户在当前会话中已上传的文件作为邮件附件发送。只需传入收件邮箱、主题和正文，文件自动从缓存读取。")
    public String sendEmailWithUserFile(
            @ToolMemoryId String userId,
            @P("收件邮箱地址") String toEmail,
            @P("邮件主题") String subject,
            @P("邮件正文内容") String content,
            @P("是否HTML格式") boolean isHtml) {
        UserFileCache.StoredFile file = UserFileCache.get(userId);
        if (file == null) {
            return "错误：没有可用的文件，请先发送文件后再试。";
        }
        log.info("[DailyHotTool] 发送用户文件，fileName={}, fileSize={} bytes", file.fileName(), file.data().length);
        String base64 = java.util.Base64.getEncoder().encodeToString(file.data());
        return sendEmailWithAttachment(toEmail, subject, content, isHtml, base64, file.fileName());
    }

    /**
     * 构建并发送无附件邮件。
     *
     * <p>HTML 模式：纯文本拼入预设样式模板，换行转 &lt;br&gt;。</p>
     * <p>纯文本模式：直接发送，不做渲染。</p>
     */
    private void sendMail(String to, String subject, String content, boolean isHtml) {
        // 校验 SMTP 配置
        String mailHost = environment.getProperty("spring.mail.host", "");
        String mailUsername = environment.getProperty("spring.mail.username", "");
        if (mailHost.isBlank() || mailUsername.isBlank()) {
            log.error("邮件发送失败，邮件服务未配置，MAIL_HOST={}，MAIL_USERNAME={}", mailHost, mailUsername);
            throw new RuntimeException("邮件发送失败：邮件服务未配置，请稍后重试。");
        }
        try {
            // 构建 MIME 邮件，multipart 开关跟随 isHtml
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, isHtml, "UTF-8");
            helper.setFrom(mailUsername);
            helper.setTo(to);
            helper.setSubject(subject);
            if (isHtml) {
                // 纯文本 → HTML：换行转 <br>，套字体和间距样式
                helper.setText("<html><body style='font-family: Microsoft YaHei, sans-serif; padding: 20px;'>"
                        + "<h3>" + subject + "</h3>"
                        + "<div style='line-height: 1.8;'>"
                        + content.replace("\n", "<br>")
                        + "</div></body></html>", true);
            } else {
                helper.setText(content);
            }
            mailSender.send(message);
            log.debug("[DailyHotTool] sendMail 完成");
        } catch (MessagingException e) {
            log.error("[DailyHotTool] 邮件发送失败，to={}, subject={}", to, subject, e);
            throw new RuntimeException("邮件发送失败，请稍后重试。", e);
        }
    }

    /**
     * 构建并发送带附件的邮件。
     *
     * <p>附件通过 Base64 解码后以 ByteArrayDataSource 挂载。</p>
     */
    private void sendMailWithAttachment(
            String to, String subject, String content, boolean isHtml,
            String attachmentBase64, String attachmentName) {
        String mailHost = environment.getProperty("spring.mail.host", "");
        String mailUsername = environment.getProperty("spring.mail.username", "");
        if (mailHost.isBlank() || mailUsername.isBlank()) {
            log.error("邮件发送失败，邮件服务未配置，MAIL_HOST={}，MAIL_USERNAME={}", mailHost, mailUsername);
            throw new RuntimeException("邮件发送失败：邮件服务未配置，请稍后重试。");
        }
        try {
            // Base64 解码附件数据
            byte[] fileData = Base64.getDecoder().decode(attachmentBase64);
            DataSource dataSource = new ByteArrayDataSource(fileData, "application/octet-stream");

            // 构建 multipart 邮件（true = 支持附件）
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailUsername);
            helper.setTo(to);
            helper.setSubject(subject);
            // 正文（HTML 或纯文本）
            if (isHtml) {
                helper.setText("<html><body style='font-family: Microsoft YaHei, sans-serif; padding: 20px;'>"
                        + "<h3>" + subject + "</h3>"
                        + "<div style='line-height: 1.8;'>"
                        + content.replace("\n", "<br>")
                        + "</div></body></html>", true);
            } else {
                helper.setText(content);
            }
            // 挂载附件
            helper.addAttachment(attachmentName, dataSource);
            mailSender.send(message);
            log.debug("[DailyHotTool] 带附件发送完成");
        } catch (MessagingException e) {
            log.error("[DailyHotTool] 邮件发送失败，to={}, subject={}", to, subject, e);
            throw new RuntimeException("邮件发送失败，请稍后重试。", e);
        }
    }
}
