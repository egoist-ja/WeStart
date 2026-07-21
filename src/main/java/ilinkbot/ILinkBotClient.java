package ilinkbot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class ILinkBotClient {
    // 初始化客户端（可自定义配置）
    private static ILinkClient client = ILinkClient.builder()
            .config(ILinkConfig.builder()
                    .connectTimeoutMs(35000)
                    .readTimeoutMs(35000)
                    .httpMaxRetries(3)
                    .heartbeatEnabled(true)
                    .build())
            .onLogin(new OnLoginListener() {
                @Override
                public void onLoginSuccess(LoginContext context) {
                    System.out.println("✅ 登录成功，botId = " + context.getBotId());
                }

                @Override
                public void onLoginFailure(Throwable throwable) {
                    System.err.println("❌ 登录失败: " + throwable.getMessage());
                }
            })
            .onMessage(new OnMessageListener() {
                @Override
                public void onMessages(List<WeixinMessage> messages) {
                    System.out.println("📨 收到 " + messages.size() + " 条消息");
                }
            })
            .build();
    private static String targetUserId, contextToken, cursor = "";

    public static void main(String[] args) {
        try {
            login();                    // 1. 二维码登录
            receiveMessage();           // 2. 接收消息（获取目标用户ID和contextToken）
            sendTextWithTyping();       // 3. 带输入态发送文本
            sendImage();                // 4. 发送图片
            sendFile();                 // 5. 发送文件
            sendVideo();                // 6. 发送视频
            sendVoice();                // 7. 发送语音
            downloadMedia();            // 8. 下载媒体消息
        } catch (Exception e) {
            System.err.println("❌ 程序异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 9. 释放资源（必做）
            try {
                client.close();
                System.out.println("✅ 资源已释放");
            } catch (Exception e) {
                System.err.println("⚠️ 释放资源失败: " + e.getMessage());
            }
        }
    }

    /**
     * 1. 二维码登录（自动轮询登录状态）
     */
    private static void login() throws Exception {
        try {
            // 获取二维码内容（自行渲染为二维码）
            String qrCodeContent = client.executeLogin();
            System.out.println(" 请将以下内容渲染为二维码后扫码登录：");
            System.out.println(qrCodeContent);
            
            // 阻塞等待登录完成，获取登录凭证
            LoginContext context = client.getLoginFuture().get();
            System.out.println("✅ 登录成功，botId = " + context.getBotId());
            
        } catch (Exception e) {
            System.err.println("❌ 登录异常: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 2. 接收消息（获取目标用户ID和contextToken，用于后续发送消息）
     */
    private static void receiveMessage() throws Exception {
        System.out.println("⏳ 等待接收消息...");
        int retryCount = 0;
        int maxRetries = 10; // 最多等待10次
        
        while (targetUserId == null && retryCount < maxRetries) {
            try {
                // 拉取消息（SDK自动管理cursor游标）
                List<WeixinMessage> messages = client.getUpdates();
                if (!messages.isEmpty()) {
                    WeixinMessage msg = messages.get(0);
                    targetUserId = msg.getFrom_user_id();
                    contextToken = msg.getContext_token();
                    System.out.println("✅ 获取目标用户ID：" + targetUserId);
                    System.out.println("✅ 获取上下文标识：" + contextToken);
                    return;
                }
                
                retryCount++;
                System.out.println(" 等待消息中... (" + retryCount + "/" + maxRetries + ")");
                Thread.sleep(3000);
                
            } catch (Exception e) {
                System.err.println("️ 接收消息异常: " + e.getMessage());
                retryCount++;
                Thread.sleep(3000);
            }
        }
        
        if (targetUserId == null) {
            throw new RuntimeException("超时：未收到消息，请先向机器人发送一条消息");
        }
    }

    /**
     * 3. 带输入态发送文本消息（模拟原生输入效果）
     */
    private static void sendTextWithTyping() throws Exception {
        try {
            System.out.println("📤 发送文本消息...");
            
            // 开启输入态
            client.startTyping(targetUserId);
            // 模拟输入延迟
            Thread.sleep(1500);
            
            // 发送文本消息（SDK自动使用缓存的contextToken）
            client.sendText(targetUserId, "微信iLink Bot SDK 2.0.0 测试消息");
            
            // 停止输入态
            client.stopTyping(targetUserId);
            System.out.println("✅ 文本消息发送成功");
            
        } catch (Exception e) {
            System.err.println("❌ 发送文本消息失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 4. 发送图片消息
     */
    private static void sendImage() {
        String filePath = "demo.png";
        if (!checkFileExists(filePath)) {
            System.out.println("⏭️ 跳过：文件不存在 - " + filePath);
            return;
        }
        
        try {
            System.out.println("🖼️ 发送图片消息...");
            byte[] imageBytes = Files.readAllBytes(Paths.get(filePath));
            client.sendImage(targetUserId, imageBytes, filePath, "测试图片");
            System.out.println("✅ 图片发送成功");
            
        } catch (Exception e) {
            System.err.println("❌ 发送图片失败: " + e.getMessage());
        }
    }

    /**
     * 5. 发送文件消息
     */
    private static void sendFile() {
        String filePath = "demo.pdf";
        if (!checkFileExists(filePath)) {
            System.out.println("️ 跳过：文件不存在 - " + filePath);
            return;
        }
        
        try {
            System.out.println("📎 发送文件消息...");
            byte[] fileBytes = Files.readAllBytes(Paths.get(filePath));
            client.sendFile(targetUserId, fileBytes, filePath, "测试文件");
            System.out.println("✅ 文件发送成功");
            
        } catch (Exception e) {
            System.err.println("❌ 发送文件失败: " + e.getMessage());
        }
    }

    /**
     * 6. 发送视频消息
     */
    private static void sendVideo() {
        String filePath = "demo.mp4";
        if (!checkFileExists(filePath)) {
            System.out.println("⏭️ 跳过：文件不存在 - " + filePath);
            return;
        }
        
        try {
            System.out.println(" 发送视频消息...");
            byte[] videoBytes = Files.readAllBytes(Paths.get(filePath));
            // 参数：目标用户ID、视频字节、文件名、视频时长（ms）、视频描述
            client.sendVideo(targetUserId, videoBytes, filePath, 5000, "测试视频");
            System.out.println("✅ 视频发送成功");
            
        } catch (Exception e) {
            System.err.println("❌ 发送视频失败: " + e.getMessage());
        }
    }

    /**
     * 7. 发送语音消息（silk格式）
     */
    private static void sendVoice() {
        String filePath = "demo.silk";
        if (!checkFileExists(filePath)) {
            System.out.println("⏭️ 跳过：文件不存在 - " + filePath);
            return;
        }
        
        try {
            System.out.println("🎤 发送语音消息...");
            byte[] voiceBytes = Files.readAllBytes(Paths.get(filePath));
            // 参数：目标用户ID、语音字节、文件名、语音时长（ms）、采样率
            client.sendVoice(targetUserId, voiceBytes, filePath, 3000, 16000);
            System.out.println("✅ 语音发送成功");
            
        } catch (Exception e) {
            System.err.println("❌ 发送语音失败: " + e.getMessage());
        }
    }

    /**
     * 8. 下载媒体消息（从接收的消息中下载）
     */
    private static void downloadMedia() {
        try {
            System.out.println("⬇️ 下载媒体消息...");
            
            // 设置超时：最多等待5秒
            long startTime = System.currentTimeMillis();
            long timeout = 5000;
            List<WeixinMessage> messages = null;
            
            while (System.currentTimeMillis() - startTime < timeout) {
                messages = client.getUpdates();
                if (!messages.isEmpty()) break;
                Thread.sleep(500);
            }
            
            if (messages == null || messages.isEmpty()) {
                System.out.println("️ 没有可下载的媒体消息（超时）");
                return;
            }
            
            int downloadCount = 0;
            for (WeixinMessage msg : messages) {
                if (msg.getItem_list() == null) continue;
                
                for (MessageItem item : msg.getItem_list()) {
                    // 下载图片（自动AES解密）
                    if (item.getImage_item() != null) {
                        try {
                            byte[] imageBytes = client.downloadImageFromMessageItem(item);
                            String fileName = "download_" + System.currentTimeMillis() + ".png";
                            Files.write(Paths.get(fileName), imageBytes);
                            System.out.println("✅ 图片下载完成: " + fileName);
                            downloadCount++;
                        } catch (Exception e) {
                            System.err.println("⚠️ 下载图片失败: " + e.getMessage());
                        }
                    }
                }
            }
            
            if (downloadCount == 0) {
                System.out.println("⏭️ 没有找到可下载的媒体文件");
            }
            
        } catch (Exception e) {
            System.err.println("❌ 下载媒体失败: " + e.getMessage());
        }
    }
    
    /**
     * 检查文件是否存在
     */
    private static boolean checkFileExists(String filePath) {
        if (!Files.exists(Paths.get(filePath))) {
            System.out.println("⚠️ 文件不存在: " + filePath);
            System.out.println(" 请将测试文件放在项目根目录");
            return false;
        }
        return true;
    }
}
